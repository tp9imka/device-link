package dev.devicelink

import android.content.Context
import dev.devicelink.feature.link.SampleChatMessage
import dev.devicelink.model.WireCodec
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Private, bounded sample history. This store never calls a transport. */
class SampleChatStore(context: Context) {
    private val preferences = context.getSharedPreferences("sample_chat", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableMessages = MutableStateFlow(load())
    val messages = mutableMessages.asStateFlow()

    suspend fun append(text: String): Boolean = mutex.withLock {
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > WireCodec.MAX_TEXT_BYTES) return false
        val next = (mutableMessages.value + SampleChatMessage(UUID.randomUUID().toString(), text)).takeLast(50)
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val array = JSONArray()
                next.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text)) }
                preferences.edit().putString("messages", array.toString()).commit()
            }.getOrDefault(false)
        }
        if (saved) mutableMessages.value = next
        saved
    }

    private fun load(): List<SampleChatMessage> = runCatching {
        val array = JSONArray(preferences.getString("messages", "[]"))
        buildList {
            for (index in maxOf(0, array.length() - 50) until array.length()) {
                val item = array.getJSONObject(index)
                val text = item.getString("text")
                if (text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= WireCodec.MAX_TEXT_BYTES) {
                    add(SampleChatMessage(item.getString("id"), text))
                }
            }
        }
    }.getOrDefault(emptyList())
}
