package dev.devicelink.transfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.devicelink.model.Peer
import dev.devicelink.model.Transfer
import dev.devicelink.model.TransferDirection
import dev.devicelink.model.TransferKind
import dev.devicelink.model.TransferStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Private keys never leave Android Keystore. Preferences contain public identity and tray metadata only. */
internal class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("device_link_identity", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!keyStore.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
    }
    val publicKey: String get() = Base64.encodeToString(keyStore.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)
    fun sign(bytes: ByteArray): String = Base64.encodeToString(Signature.getInstance("SHA256withECDSA").run {
        initSign(keyStore.getKey(ALIAS, null) as PrivateKey); update(bytes); sign()
    }, Base64.NO_WRAP)
    var deviceName: String
        get() = prefs.getString("name", android.os.Build.MODEL)?.take(48).orEmpty().ifBlank { "Android" }
        set(value) { prefs.edit().putString("name", value).apply() }
    fun trustedPeers(): List<Peer> = runCatching {
        val array = JSONArray(prefs.getString("peers", "[]"))
        List(array.length()) { index -> array.getJSONObject(index).let { Peer(it.getString("id"), it.getString("name")) } }
    }.getOrDefault(emptyList())
    fun savePeers(peers: List<Peer>) {
        prefs.edit().putString("peers", JSONArray().apply {
            peers.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }.toString()).apply()
    }
    fun receivedFiles(context: Context): List<Transfer> = runCatching {
        val array = JSONArray(prefs.getString("received", "[]"))
        List(array.length()) { array.getJSONObject(it) }.mapNotNull { item ->
            val id = item.getString("id")
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || !File(context.filesDir, "received/$id").isFile) null else Transfer(
                id = id, name = item.getString("name"), kind = TransferKind.FILE,
                direction = TransferDirection.INCOMING, status = TransferStatus.COMPLETE,
                totalBytes = item.getLong("size"), transferredBytes = item.getLong("size"),
                mimeType = item.getString("mime"), localUri = item.getString("uri"))
        }
    }.getOrDefault(emptyList())
    fun saveFiles(files: List<Transfer>) {
        prefs.edit().putString("received", JSONArray().apply {
            files.filter { it.kind == TransferKind.FILE && it.direction == TransferDirection.INCOMING && it.status == TransferStatus.COMPLETE }
                .forEach { put(JSONObject().put("id", it.id).put("name", it.name)
                    .put("size", it.totalBytes).put("mime", it.mimeType).put("uri", it.localUri)) }
        }.toString()).apply()
    }
    private companion object { const val ALIAS = "devicelink.identity.v1" }
}
