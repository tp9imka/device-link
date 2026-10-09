package dev.devicelink.receiver

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import dev.devicelink.sdk.ClipDirection
import dev.devicelink.sdk.ClipRecord
import dev.devicelink.sdk.ClipState

/** Clipboard-style list of received and sent clips. */
class ClipAdapter(private val context: Context) : BaseAdapter() {
    var items: List<ClipRecord> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_clip, parent, false)
        val record = items[position]
        val received = record.direction == ClipDirection.RECEIVED
        val time = DateUtils.getRelativeTimeSpanString(record.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        view.findViewById<TextView>(R.id.meta).text = context.getString(if (received) R.string.from else R.string.to, record.peerName) + " · " + time
        view.findViewById<TextView>(R.id.content).text = record.text ?: record.name ?: context.getString(R.string.image)
        val thumb = view.findViewById<ImageView>(R.id.thumb)
        val image = record.uri?.takeIf { record.mime?.startsWith("image/") == true }
        thumb.visibility = if (image != null) View.VISIBLE else View.GONE
        if (image != null) thumb.setImageBitmap(runCatching {
            context.contentResolver.openInputStream(Uri.parse(image))?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
        }.getOrNull())
        val state = view.findViewById<TextView>(R.id.state)
        state.text = when {
            received && record.state == ClipState.COPIED -> context.getString(R.string.state_copied_in)
            received -> context.getString(R.string.state_share)
            record.state == ClipState.PENDING -> context.getString(R.string.state_pending_out)
            record.state == ClipState.COPIED -> context.getString(R.string.state_copied_out, record.peerName)
            record.state == ClipState.DELIVERED -> context.getString(R.string.state_delivered_out, record.peerName)
            else -> context.getString(R.string.state_failed)
        }
        state.setTextColor(context.getColor(when (record.state) {
            ClipState.COPIED, ClipState.DELIVERED -> R.color.ok
            ClipState.FAILED -> R.color.warn
            else -> R.color.muted
        }))
        return view
    }
}
