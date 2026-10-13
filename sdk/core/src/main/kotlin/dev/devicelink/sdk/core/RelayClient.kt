package dev.devicelink.sdk.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class HttpResponse(val status: Int, val body: ByteArray)

/** Minimal transport seam so tests and platforms can swap the HTTP stack. */
fun interface HttpTransport {
    suspend fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray, timeoutMillis: Int): HttpResponse
}

/** java.net transport with platform TLS validation; works on the JVM and every Android version. */
class UrlConnectionTransport(private val maxResponseBytes: Int = 25 * 1024 * 1024) : HttpTransport {
    private val active = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray, timeoutMillis: Int): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            active.add(connection)
            try {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { connection.disconnect() }
                    val result = runCatching {
                        connection.requestMethod = method
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        // No total request timeout: an 18 MB upload on a slow link may take minutes. The
                        // read timeout starts once the body sits in the send buffer, so uploads pass a
                        // size-scaled value (uploadTimeoutMillis); writes are not time-capped here.
                        connection.connectTimeout = 15_000
                        connection.readTimeout = timeoutMillis
                        headers.forEach(connection::setRequestProperty)
                        if (body.isNotEmpty()) {
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(body.size)
                            connection.outputStream.use { it.write(body) }
                        }
                        val status = connection.responseCode
                        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                        HttpResponse(status, stream?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(output.size() + count <= maxResponseBytes) { "Relay response too large" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: ByteArray(0))
                    }
                    continuation.resumeWith(result)
                }
            } finally {
                active.remove(connection)
                connection.disconnect()
            }
        }

    fun cancelAll() { active.forEach(HttpURLConnection::disconnect) }
}

/** Wait for a relay answer once the request is sent (non-upload calls, and the margin after an upload body). */
internal const val RESPONSE_TIMEOUT_MILLIS = 30_000
/** Mirror the relay's body deadline: server/relay/config.py `body_grace_ms` and `body_min_rate` (bytes per second). */
internal const val RELAY_BODY_GRACE_MILLIS = 10_000L
internal const val RELAY_BODY_MIN_BYTES_PER_SECOND = 32 * 1024L

/**
 * How long to wait for the answer to an upload of [bodyBytes]. The write returns once the body is in the
 * socket's send buffer, which can hold megabytes still in flight, so the response timer must cover the
 * whole time the relay itself allows for reading that body (server/relay/app.py `read_body`), plus
 * [RESPONSE_TIMEOUT_MILLIS] for storing and answering. Giving up earlier reports a failure for an upload
 * the relay may still accept.
 */
internal fun uploadTimeoutMillis(bodyBytes: Int): Int =
    (RELAY_BODY_GRACE_MILLIS + bodyBytes * 1000L / RELAY_BODY_MIN_BYTES_PER_SECOND + RESPONSE_TIMEOUT_MILLIS).toInt()

class RelayException(val status: Int, val code: String) : Exception("Relay request failed: $status $code")

@Serializable
data class RelayInfo(
    val service: String = "devicelink-relay",
    val version: String = "",
    val maxLifetimeSeconds: Long = 600,
    val enrollment: String = "open",
    val push: List<String> = emptyList(),
)

@Serializable
data class DeviceMetadata(val platform: String, val model: String = "", val appVersion: String = "")

@Serializable
data class PairingStatus(val state: String, val joinerId: String? = null, val sealed: String? = null)

/**
 * Signed relay API. Every request carries a fresh nonce and timestamp and signs
 * method, raw path+query and the SHA-256 of the exact body (see server/README.md).
 */
class RelayClient(
    val baseUrl: String,
    private val identity: DeviceIdentity,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val publicKey = Encoding.b64(identity.publicKeyDer)

    suspend fun info(): RelayInfo = wireJson.decodeFromString(String(call("GET", "/v1/info", signed = false), Charsets.UTF_8))

    suspend fun register(metadata: DeviceMetadata, enrollmentToken: String? = null, pairingId: String? = null) {
        val extra = buildMap {
            if (!enrollmentToken.isNullOrEmpty()) put("X-Enrollment-Token", enrollmentToken)
            if (pairingId != null) put("X-Pairing-Id", pairingId)
        }
        call("POST", "/v1/register", wireJson.encodeToString(metadata).toByteArray(), extra)
    }

    suspend fun allow(peerId: String) { call("PUT", "/v1/peers/${checkId(peerId)}", EMPTY) }
    suspend fun revoke(peerId: String) { call("DELETE", "/v1/peers/${checkId(peerId)}") }

    suspend fun createPairing(pairingId: String, expiresAt: Long) {
        call("POST", "/v1/pairings", """{"id":"$pairingId","expiresAt":$expiresAt}""".toByteArray())
    }

    suspend fun joinPairing(pairingId: String, sealed: String) {
        call("POST", "/v1/pairings/$pairingId/join", """{"sealed":"$sealed"}""".toByteArray())
    }

    suspend fun confirmPairing(pairingId: String, sealed: String) {
        call("POST", "/v1/pairings/$pairingId/confirm", """{"sealed":"$sealed"}""".toByteArray())
    }

    suspend fun pollPairing(pairingId: String, waitSeconds: Int): PairingStatus =
        wireJson.decodeFromString(String(call("GET", "/v1/pairings/$pairingId?wait=$waitSeconds", timeoutMillis = (waitSeconds + 20) * 1000), Charsets.UTF_8))

    suspend fun cancelPairing(pairingId: String) { call("DELETE", "/v1/pairings/$pairingId") }

    /** Returns 201/200 for new/idempotent retry; both mean the relay durably accepted the envelope. */
    suspend fun upload(envelope: Envelope) {
        val body = envelope.encode()
        call("POST", "/v1/messages", body, timeoutMillis = uploadTimeoutMillis(body.size))
    }

    /** [exclude]: up to 20 envelope IDs the relay must skip (for example ones already processed this round). */
    suspend fun poll(waitSeconds: Int, limit: Int = 1, exclude: Collection<String> = emptyList()): List<Envelope> {
        require(exclude.size <= 20 && exclude.all { java.util.UUID.fromString(it).toString() == it })
        val skip = if (exclude.isEmpty()) "" else "&exclude=" + exclude.joinToString(",")
        return Envelope.decodeList(call("GET", "/v1/messages?wait=$waitSeconds&limit=$limit$skip", timeoutMillis = (waitSeconds + 20) * 1000))
    }

    /** One waiting envelope by ID (notification previews). Does not acknowledge it. */
    suspend fun fetch(id: String): Envelope = Envelope.decode(String(call("GET", "/v1/messages/$id"), Charsets.UTF_8))

    suspend fun acknowledge(id: String) { call("DELETE", "/v1/messages/$id") }

    /** Deletes this device and everything the relay holds for it (links, messages, pairings, push token). */
    suspend fun unregister() { call("DELETE", "/v1/devices/self") }

    suspend fun registerPush(token: String, environment: String, topic: String) {
        call("PUT", "/v1/push", """{"provider":"apns","token":"$token","environment":"$environment","topic":"$topic"}""".toByteArray())
    }

    private suspend fun call(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        extraHeaders: Map<String, String> = emptyMap(),
        signed: Boolean = true,
        timeoutMillis: Int = RESPONSE_TIMEOUT_MILLIS,
    ): ByteArray {
        val headers = LinkedHashMap<String, String>()
        if (body.isNotEmpty()) headers["Content-Type"] = "application/json"
        if (signed) headers.putAll(signatureHeaders(method, path, body))
        headers.putAll(extraHeaders)
        val response = transport.execute(method, baseUrl + path, headers, body, timeoutMillis)
        if (response.status !in 200..299) {
            val code = runCatching {
                wireJson.decodeFromString<JsonObject>(String(response.body, Charsets.UTF_8))["error"]!!.jsonPrimitive.content
            }.getOrDefault("http_${response.status}")
            throw RelayException(response.status, code)
        }
        return response.body
    }

    fun signatureHeaders(method: String, path: String, body: ByteArray): Map<String, String> {
        val time = clock().toString()
        val nonce = UUID.randomUUID().toString()
        val canonical = "DeviceLink relay request v1\n$method\n$path\n$time\n$nonce\n${Encoding.hex(Encoding.sha256(body))}"
        return mapOf(
            "X-Device-Key" to publicKey,
            "X-Device-Time" to time,
            "X-Device-Nonce" to nonce,
            "X-Device-Signature" to Encoding.b64(identity.sign(canonical.toByteArray(Charsets.UTF_8))),
        )
    }

    private fun checkId(id: String) = id.also { require(it.matches(Envelope.HEX64)) }

    private companion object { val EMPTY = "{}".toByteArray() }
}
