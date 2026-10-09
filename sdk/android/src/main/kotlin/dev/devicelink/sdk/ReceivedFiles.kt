package dev.devicelink.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.devicelink.sdk.core.Payload
import java.io.File
import java.io.FileNotFoundException

/** Private storage for received images/files, exposed read-only through [DeviceLinkFileProvider]. */
internal object ReceivedFiles {
    const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    private val ID = Regex("[0-9a-f-]{36}")

    fun root(context: Context) = File(context.filesDir, "devicelink/received")
    fun authority(context: Context) = "${context.packageName}.devicelink.files"

    fun store(context: Context, id: String, name: String, mime: String, bytes: ByteArray): Uri {
        require(id.matches(ID) && Payload.validFileName(name))
        val directory = File(root(context), id).apply { mkdirs() }
        val file = File(directory, name)
        val temporary = File(directory, ".part")
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        check(temporary.renameTo(file))
        File(directory, ".mime").writeText(mime)
        return uri(context, id, name)
    }

    fun uri(context: Context, id: String, name: String): Uri =
        Uri.Builder().scheme("content").authority(authority(context)).appendPath(id).appendPath(name).build()

    /** Resolves only `/<uuid>/<name>` inside the private root; everything else is not found. */
    fun resolve(context: Context, uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size != 2 || !segments[0].matches(ID) || !Payload.validFileName(segments[1])) return null
        val file = File(File(root(context), segments[0]), segments[1])
        return file.takeIf { it.isFile && it.canonicalPath.startsWith(root(context).canonicalPath + File.separator) }
    }

    fun mime(file: File): String =
        runCatching { File(file.parentFile, ".mime").readText() }.getOrNull()?.takeIf { it.length <= 127 } ?: Mime.forName(file.name)

    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        root(context).listFiles()?.filter { now - it.lastModified() > RETENTION_MILLIS }?.forEach { it.deleteRecursively() }
    }

    fun clear(context: Context) { root(context).deleteRecursively() }
}

/** Minimal read-only provider; `grantUriPermissions` lets the clipboard and share sheet hand out reads. */
class DeviceLinkFileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val file = ReceivedFiles.resolve(requireNotNull(context), uri) ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() })
        }
    }

    override fun getType(uri: Uri): String? = ReceivedFiles.resolve(requireNotNull(context), uri)?.let(ReceivedFiles::mime)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val file = ReceivedFiles.resolve(requireNotNull(context), uri) ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
}

internal object Mime {
    private val byExtension = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "pdf" to "application/pdf", "txt" to "text/plain", "zip" to "application/zip")

    fun forName(name: String): String =
        byExtension[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    fun extension(mime: String): String = byExtension.entries.firstOrNull { it.value == mime }?.key ?: "bin"
}
