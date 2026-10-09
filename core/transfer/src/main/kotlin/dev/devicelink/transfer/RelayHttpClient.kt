package dev.devicelink.transfer

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Signed requests over system-validated TLS. Loopback HTTP is allowed only in debug apps. */
internal class RelayHttpClient(
    private val baseUrl: String,
    private val identity: IdentityStore,
    private val enrollmentToken: String,
) {
    private val active = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false

    suspend fun request(method: String, path: String, body: ByteArray = byteArrayOf()): ByteArray = withContext(Dispatchers.IO) {
        check(!closed)
        val connection = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
        active.add(connection)
        try {
            check(!closed)
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 35_000
            val time = System.currentTimeMillis().toString()
            val nonce = UUID.randomUUID().toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val canonical = "DeviceLink relay request v1\n$method\n$path\n$time\n$nonce\n$hash"
            connection.setRequestProperty("X-Device-Key", identity.publicKey)
            connection.setRequestProperty("X-Device-Time", time)
            connection.setRequestProperty("X-Device-Nonce", nonce)
            connection.setRequestProperty("X-Device-Signature", identity.sign(canonical.toByteArray(Charsets.UTF_8)))
            if (path == "/v1/register" && enrollmentToken.isNotBlank()) connection.setRequestProperty("X-Enrollment-Token", enrollmentToken)
            if (body.isNotEmpty()) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw RelayHttpException(status)
            // Poll requests one envelope at a time to bound Android heap use.
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16_384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 25 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } finally { active.remove(connection); connection.disconnect() }
    }

    fun close() { closed = true; active.forEach(HttpURLConnection::disconnect); active.clear() }

    companion object {
        fun normalizeUrl(raw: String, debuggable: Boolean): String {
            val uri = URI(raw.trim())
            require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.host != null)
            require(uri.path.isNullOrEmpty() || uri.path == "/")
            require(uri.scheme == "https" || (debuggable && uri.scheme == "http" && uri.host == "127.0.0.1"))
            return raw.trim().trimEnd('/')
        }
    }
}

internal class RelayHttpException(val status: Int) : Exception("Relay request failed ($status)")
