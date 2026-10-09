package dev.devicelink

import android.content.Context
import dev.devicelink.feature.link.SampleChatMessage
import dev.devicelink.feature.link.SampleChatImage
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
    private val imageClipboard = ImageClipboard(context)
    private val preferences = context.getSharedPreferences("sample_chat", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableMessages = MutableStateFlow(load())
    val messages = mutableMessages.asStateFlow()

    suspend fun append(text: String, image: SampleChatImage? = null): Boolean = mutex.withLock {
        if ((text.isBlank() && image == null) || text.toByteArray(Charsets.UTF_8).size > WireCodec.MAX_TEXT_BYTES || (image != null && !imageClipboard.owns(image))) return false
        val next = (mutableMessages.value + SampleChatMessage(UUID.randomUUID().toString(), text, image?.uri, image?.mime)).takeLast(50)
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val array = JSONArray()
                next.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("imageUri", it.imageUri).put("imageMime", it.imageMime)) }
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
                val image = if (item.has("imageUri") && !item.isNull("imageUri")) SampleChatImage(item.getString("imageUri"), item.optString("imageMime")) else null
                if ((text.isNotBlank() || image != null) && text.toByteArray(Charsets.UTF_8).size <= WireCodec.MAX_TEXT_BYTES && (image == null || imageClipboard.owns(image))) {
                    add(SampleChatMessage(item.getString("id"), text, image?.uri, image?.mime))
                }
            }
        }
    }.getOrDefault(emptyList())
}
