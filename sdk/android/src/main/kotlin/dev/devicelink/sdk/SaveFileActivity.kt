package dev.devicelink.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible activity behind "Save…" on a received file: opens the system "save as" picker
 * (`ACTION_CREATE_DOCUMENT`) and copies the file there. Received files otherwise live in
 * app-private storage and are pruned after 24 hours. Needs no storage permission on any API level.
 */
class SaveFileActivity : Activity() {
    private var record: ClipRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = DeviceLink.get(this)
        val id = intent.getStringExtra(EXTRA_ID)
        record = link.history.value.firstOrNull { it.id == id && it.uri != null }
        val found = record?.let { ReceivedFiles.resolve(this, Uri.parse(it.uri)) }
        if (found == null) { done(R.string.dl_save_gone); return }
        // After a configuration change the picker is already open; its result still arrives here.
        if (savedInstanceState != null) return
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(record?.mime ?: ReceivedFiles.mime(found)).putExtra(Intent.EXTRA_TITLE, found.name)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(create, PICK)
        } catch (_: ActivityNotFoundException) {
            done(R.string.dl_save_failed)
        }
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val target = data?.data
        val source = record?.uri?.let { ReceivedFiles.resolve(this, Uri.parse(it)) }
        if (requestCode != PICK || resultCode != RESULT_OK || target == null) { finish(); return }
        if (source == null) { done(R.string.dl_save_gone); return }
        val resolver = contentResolver
        DeviceLink.get(this).scope.launch(Dispatchers.IO) {
            val saved = runCatching {
                source.inputStream().use { input ->
                    (resolver.openOutputStream(target, "w") ?: error("No output")).use { input.copyTo(it, 64 * 1024) }
                }
            }.isSuccess
            // Do not leave an empty or partial document behind.
            if (!saved) runCatching { DocumentsContract.deleteDocument(resolver, target) }
            withContext(Dispatchers.Main) { done(if (saved) R.string.dl_save_done else R.string.dl_save_failed) }
        }
    }

    private fun done(message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    internal companion object {
        private const val PICK = 1
        private const val EXTRA_ID = "dev.devicelink.sdk.extra.ID"

        /** Null when the record carries no file. */
        fun intent(context: Context, record: ClipRecord): Intent? = record.uri?.let {
            Intent(context, SaveFileActivity::class.java).putExtra(EXTRA_ID, record.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
