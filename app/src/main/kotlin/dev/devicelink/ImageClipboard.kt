package dev.devicelink

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import dev.devicelink.feature.link.SampleChatImage
import dev.devicelink.model.WireCodec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Copies actual bounded image bytes into private stable storage; never coerces an image URI to text. */
class ImageClipboard(context: Context) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "sample_images")

    suspend fun importImage(raw: String): SampleChatImage? = importOwnedImageFile { own ->
        try {
            val uri = Uri.parse(raw)
            require(uri.scheme == "content")
            val existingMime = context.contentResolver.getType(uri)
            if (existingMime != null) {
                val existing = SampleChatImage(raw, existingMime)
                if (owns(existing)) return@importOwnedImageFile existing
            }
            synchronized(imageFilesLock) {
                directory.mkdirs()
                pruneImageFiles(directory, protectedNames(), System.currentTimeMillis())
                require((directory.listFiles()?.sumOf { it.length() } ?: 0) < 512L * 1024 * 1024)
            }
            val temporary = File(directory, "${UUID.randomUUID()}.part").also(own)
            val input = requireNotNull(context.contentResolver.openInputStream(uri))
            input.use { source -> temporary.outputStream().use { target ->
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (true) {
                    val count = source.read(buffer); if (count < 0) break
                    size += count; require(size <= WireCodec.MAX_CLIP_IMAGE_BYTES)
                    target.write(buffer, 0, count)
                }
                require(size > 0)
            } }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, options)
            val mime = options.outMimeType
            require(mime in WireCodec.CLIP_IMAGE_MIMES && options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 40_000_000)
            options.inJustDecodeBounds = false
            options.inSampleSize = Integer.highestOneBit((maxOf(options.outWidth, options.outHeight) / 256).coerceAtLeast(1))
            requireNotNull(BitmapFactory.decodeFile(temporary.path, options)).recycle()
            val extension = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
            val file = File(directory, "${UUID.randomUUID()}.$extension")
            check(temporary.renameTo(file))
            own(file)
            asset(file, mime)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** Call on Main immediately after the caller's current-session check. Android grants clipboard URI reads. */
    fun write(image: SampleChatImage): Boolean = runCatching {
        require(owns(image))
        require(context.contentResolver.getType(Uri.parse(image.uri)) == image.mime)
        val clip = ClipData(ClipDescription(context.getString(R.string.image_clipboard_label), arrayOf(image.mime)), ClipData.Item(Uri.parse(image.uri)))
        synchronized(imageFilesLock) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            // Our own successful writes only: never inspect the system clipboard.
            context.getSharedPreferences("image_clipboard", Context.MODE_PRIVATE).edit()
                .putString("latest_owned", Uri.parse(image.uri).lastPathSegment).apply()
        }
        true
    }.getOrDefault(false)

    fun owns(image: SampleChatImage): Boolean = synchronized(imageFilesLock) {
        val name = ownedName(image.uri) ?: return@synchronized false
        val file = File(directory, name)
        val valid = image.mime in WireCodec.CLIP_IMAGE_MIMES && file.isFile
        // A short ownership lease protects reuse while the caller commits its chat entry.
        if (valid) file.setLastModified(System.currentTimeMillis())
        valid
    }

    private fun ownedName(raw: String): String? {
        val uri = Uri.parse(raw)
        val name = uri.lastPathSegment ?: return null
        return name.takeIf {
            uri.scheme == "content" && uri.authority == "${context.packageName}.files" &&
                uri.pathSegments.size == 2 && uri.pathSegments.firstOrNull() == "sample_images" &&
                it.matches(Regex("[A-Za-z0-9_-]+\\.(png|jpg|webp|gif)"))
        }
    }

    private fun protectedNames(): Set<String> = buildSet {
        val stored = context.getSharedPreferences("sample_chat", Context.MODE_PRIVATE)
            .getString("messages", "[]") ?: "[]"
        // If history cannot be parsed, leave files intact instead of guessing ownership.
        val messages = JSONArray(stored)
        for (index in 0 until messages.length()) {
            ownedName(messages.getJSONObject(index).optString("imageUri"))?.let(::add)
        }
        context.getSharedPreferences("image_clipboard", Context.MODE_PRIVATE)
            .getString("latest_owned", null)?.let(::add)
    }

    suspend fun sample(): SampleChatImage = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val file = File(directory, "sample_landscape.png")
        if (!file.isFile) {
            val bitmap = createBitmap(640, 400)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(210, 235, 240))
            paint.color = Color.rgb(249, 175, 92); canvas.drawCircle(480f, 100f, 52f, paint)
            paint.color = Color.rgb(58, 124, 124)
            canvas.drawPath(Path().apply { moveTo(0f, 320f); lineTo(215f, 105f); lineTo(400f, 320f); close() }, paint)
            paint.color = Color.rgb(24, 78, 84)
            canvas.drawPath(Path().apply { moveTo(180f, 400f); lineTo(430f, 180f); lineTo(640f, 360f); lineTo(640f, 400f); close() }, paint)
            paint.color = Color.WHITE; paint.textSize = 28f
            canvas.drawText(context.getString(R.string.image_sample_caption), 28f, 365f, paint)
            val staged = File(directory, "sample_landscape.part")
            try {
                staged.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                check(staged.renameTo(file))
            } finally { bitmap.recycle(); staged.delete() }
        }
        asset(file, "image/png")
    }

    private companion object { val imageFilesLock = Any() }

    private fun asset(file: File, mime: String) = SampleChatImage(FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString(), mime)
}

/** Owns newly created files across withContext's prompt-cancellation return boundary. */
internal suspend fun <T> importOwnedImageFile(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    produce: suspend (own: (File) -> Unit) -> T?,
): T? {
    var owned: File? = null
    var published = false
    try {
        val result = withContext(dispatcher) { produce { owned = it } }
        published = result != null
        return result
    } finally {
        if (!published) withContext(NonCancellable + Dispatchers.IO) { owned?.delete() }
    }
}

/** Only stale, unreferenced assets are eligible; recent files cover in-flight imports/reuse. */
internal fun pruneImageFiles(directory: File, protectedNames: Set<String>, now: Long) {
    val oldestRetained = now - 24L * 60 * 60 * 1000
    directory.listFiles()?.forEach { file ->
        if (file.isFile && file.name != "sample_landscape.png" && file.name !in protectedNames &&
            file.extension in setOf("png", "jpg", "webp", "gif", "part") && file.lastModified() < oldestRetained
        ) file.delete()
    }
}
