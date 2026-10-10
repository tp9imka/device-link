package dev.devicelink.sdk

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ClipDirection { RECEIVED, SENT }
enum class ClipState { PENDING, COPIED, DELIVERED, SHARE_NEEDED, FAILED }

/** One entry of the local clip list shown by apps. Stored privately, pruned after 24 hours. */
data class ClipRecord(
    val id: String,
    val direction: ClipDirection,
    val peerName: String,
    val kind: String,
    val text: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val name: String? = null,
    val at: Long,
    val state: ClipState,
    val html: String? = null,
    /** Sender marked it sensitive: never shown in notifications, masked in lists. */
    val sensitive: Boolean = false,
)

internal class ClipHistory(context: Context, private val limit: Int = 50) {
    private val file = File(context.filesDir, "devicelink/history.json")
    private val mutable = MutableStateFlow(load())
    val records: StateFlow<List<ClipRecord>> = mutable.asStateFlow()

    @Synchronized fun add(record: ClipRecord) = commit(listOf(record) + mutable.value.filterNot { it.id == record.id })

    @Synchronized fun update(id: String, state: ClipState) =
        commit(mutable.value.map { if (it.id == id && it.state != ClipState.COPIED) it.copy(state = state) else it })

    @Synchronized fun remove(id: String) = commit(mutable.value.filterNot { it.id == id })
    @Synchronized fun clear() = commit(emptyList())

    private fun commit(records: List<ClipRecord>) {
        val now = System.currentTimeMillis()
        val kept = records.filter { now - it.at < ReceivedFiles.RETENTION_MILLIS }.take(limit)
        mutable.value = kept
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "history.json.tmp")
            temporary.writeText(JSONArray().apply { kept.forEach { put(it.toJson()) } }.toString())
            temporary.renameTo(file)
        }
    }

    private fun load(): List<ClipRecord> = runCatching {
        val array = JSONArray(file.readText())
        val now = System.currentTimeMillis()
        List(array.length()) { array.getJSONObject(it).toRecord() }.filter { now - it.at < ReceivedFiles.RETENTION_MILLIS }
    }.getOrDefault(emptyList())

    private fun ClipRecord.toJson() = JSONObject().put("id", id).put("direction", direction.name).put("peer", peerName)
        .put("kind", kind).put("text", text).put("uri", uri).put("mime", mime).put("name", name).put("at", at).put("state", state.name)
        .put("html", html).put("sensitive", sensitive)

    private fun JSONObject.toRecord() = ClipRecord(getString("id"), ClipDirection.valueOf(getString("direction")), getString("peer"),
        getString("kind"), optString("text").takeIf { has("text") }, optString("uri").takeIf { has("uri") },
        optString("mime").takeIf { has("mime") }, optString("name").takeIf { has("name") }, getLong("at"), ClipState.valueOf(getString("state")),
        optString("html").takeIf { has("html") }, optBoolean("sensitive", false))
}
