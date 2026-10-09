package dev.devicelink.transfer

import android.content.Context
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dev.devicelink.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Device-local keys, settings and replay ledger. Backup is disabled at the app boundary. */
internal class RelayStore(context: Context, private val identity: IdentityStore) {
    private val prefs = context.getSharedPreferences("device_link_relay", Context.MODE_PRIVATE)
    private val manager by lazy {
        AndroidKeysetManager.Builder().withSharedPref(context, "keyset", "device_link_relay_keys")
            .withKeyTemplate(RelayCrypto.keyTemplate())
            .withMasterKeyUri("android-keystore://device_link_relay_master").build().also {
                check(it.isUsingKeystore) { "Relay requires Keystore key protection" }
            }
    }
    val bundle: RelayKeyBundle by lazy { RelayCrypto.createBundle(identity.publicKey, manager.keysetHandle, identity::sign) }
    val crypto by lazy { RelayCrypto(manager.keysetHandle, bundle, identity::sign) }
    val serverUrl get() = prefs.getString("url", "").orEmpty()
    val token get() = prefs.getString("token", "").orEmpty()
    val allowClipboard get() = prefs.getBoolean("clipboard", false)
    var selectedPeerId: String?
        get() = prefs.getString("selected", null)
        set(value) { check(prefs.edit().putString("selected", value).commit()) }

    fun configure(url: String, token: String, clipboard: Boolean) {
        check(prefs.edit().putString("url", url).putString("token", token).putBoolean("clipboard", clipboard).commit())
    }
    fun peers(): Map<String, RelayKeyBundle> = runCatching {
        val objectValue = JSONObject(prefs.getString("peers", "{}").orEmpty())
        objectValue.keys().asSequence().associateWith { RelayCodec.decodeBundle(objectValue.getString(it).toByteArray()) }
    }.getOrDefault(emptyMap())

    @Synchronized fun addPeer(bundle: RelayKeyBundle) {
        require(bundle.id !in pendingRevocations(serverUrl)) { "Peer revocation has not reached relay" }
        val existing = peers()[bundle.id]
        require(existing == null || existing.encryptionPublicKey == bundle.encryptionPublicKey)
        val peers = JSONObject(prefs.getString("peers", "{}").orEmpty())
        peers.put(bundle.id, RelayCodec.encodeBundle(bundle).toString(Charsets.UTF_8))
        check(prefs.edit().putString("peers", peers.toString()).putString("selected", selectedPeerId ?: bundle.id).commit())
    }
    @Synchronized fun removePeer(id: String) {
        val peers = JSONObject(prefs.getString("peers", "{}").orEmpty()).apply { remove(id) }
        val revocations = prefs.getStringSet("revocations", emptySet()).orEmpty().toMutableSet()
        if (serverUrl.isNotBlank()) revocations.add(revocationKey(serverUrl, id))
        val selected = if (selectedPeerId == id) peers.keys().asSequence().firstOrNull() else selectedPeerId
        check(prefs.edit().putString("peers", peers.toString()).putString("selected", selected)
            .putStringSet("revocations", revocations).commit())
    }
    @Synchronized fun pendingRevocations(url: String): Set<String> = prefs.getStringSet("revocations", emptySet()).orEmpty()
        .filter { it.startsWith("$url\n") }.map { it.substringAfter('\n') }.toSet()

    @Synchronized fun completeRevocation(url: String, id: String) {
        val pending = prefs.getStringSet("revocations", emptySet()).orEmpty().toMutableSet()
        pending.remove(revocationKey(url, id))
        check(prefs.edit().putStringSet("revocations", pending).commit())
    }
    private fun revocationKey(url: String, id: String) = "$url\n$id"
    @Synchronized fun nextSequence(): Long {
        val next = Math.addExact(prefs.getLong("sequence", 0), 1)
        check(prefs.edit().putLong("sequence", next).commit())
        return next
    }
    fun lastClipSequence(senderId: String): Long = prefs.getLong("clip_$senderId", 0)
    fun hasConsumed(id: String): Boolean = consumed().has(id)
    @Synchronized fun consume(id: String, expiresAt: Long, senderId: String? = null, sequence: Long? = null) {
        val ledger = consumed()
        val now = System.currentTimeMillis()
        ledger.keys().asSequence().toList().filter { ledger.getLong(it) < now }.forEach(ledger::remove)
        check(ledger.length() < 10_000 || ledger.has(id))
        ledger.put(id, expiresAt)
        val edit = prefs.edit().putString("consumed", ledger.toString())
        if (senderId != null && sequence != null) edit.putLong("clip_$senderId", maxOf(lastClipSequence(senderId), sequence))
        check(edit.commit())
    }
    private fun consumed() = JSONObject(prefs.getString("consumed", "{}").orEmpty())

    @Synchronized fun saveText(envelope: RelayEnvelope, text: String) {
        val current = JSONArray(prefs.getString("texts", "[]"))
        val next = JSONArray()
        next.put(JSONObject().put("id", envelope.id).put("text", text).put("expiresAt", envelope.expiresAt))
        repeat(current.length()) { index ->
            val item = current.getJSONObject(index)
            if (next.length() < 100 && item.getString("id") != envelope.id && item.getLong("expiresAt") > System.currentTimeMillis()) next.put(item)
        }
        check(prefs.edit().putString("texts", next.toString()).commit())
    }
    fun receivedTexts(): List<Transfer> = runCatching {
        val records = JSONArray(prefs.getString("texts", "[]"))
        List(records.length()) { records.getJSONObject(it) }.filter { it.getLong("expiresAt") > System.currentTimeMillis() }.map {
            Transfer(it.getString("id"), "", TransferKind.TEXT, TransferDirection.INCOMING, TransferStatus.COMPLETE, text = it.getString("text"), mimeType = "text/plain")
        }
    }.getOrDefault(emptyList())
}


/** Serializes ACL writes so a grant already in flight cannot overtake a later revocation. */
internal class RelayAclReconciler(
    private val pending: (String) -> Set<String>,
    private val completed: (String, String) -> Unit,
) {
    private val writes = kotlinx.coroutines.sync.Mutex()

    suspend fun grant(isCurrent: () -> Boolean, write: suspend () -> Unit) {
        writes.lock()
        try { if (isCurrent()) write() } finally { writes.unlock() }
    }

    suspend fun revokePending(url: String, isCurrent: () -> Boolean, remove: suspend (String) -> Unit) {
        writes.lock()
        try {
            for (id in pending(url)) {
                if (!isCurrent()) return
                remove(id)
                completed(url, id)
            }
        } finally { writes.unlock() }
    }
}
