package dev.devicelink.sdk

import android.content.Context
import java.io.File

/**
 * Where the SDK keeps decrypted data (clip history, received files). It lives in `noBackupFilesDir`
 * so a host app with the default `allowBackup="true"` never uploads clipboard content, names or
 * files to cloud backup or device transfer.
 */
internal object PrivateStorage {
    /** Entries earlier versions wrote under `filesDir/devicelink/`, which is backed up by default. */
    private val LEGACY = listOf("history.json", "history.json.tmp", "received")

    fun root(context: Context) = File(context.noBackupFilesDir, "devicelink")

    @Volatile private var migrated = false

    /** One-time move from `filesDir`; cheap after the first call. */
    fun migrate(context: Context) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            runCatching { migrate(context.filesDir, context.noBackupFilesDir) }
            migrated = true
        }
    }

    /**
     * Moves legacy entries from [filesDir] to [noBackupDir] (same volume, so a rename). Data already in
     * the new place wins; the backed-up copy is removed either way. Other files are not touched.
     */
    fun migrate(filesDir: File, noBackupDir: File) {
        val old = File(filesDir, "devicelink")
        if (!old.isDirectory) return
        val target = File(noBackupDir, "devicelink").apply { mkdirs() }
        for (name in LEGACY) {
            val source = File(old, name)
            if (!source.exists()) continue
            val destination = File(target, name)
            if (destination.exists() || !source.renameTo(destination)) source.deleteRecursively()
        }
        old.delete() // only succeeds when nothing else is left in it
    }
}
