package dev.devicelink.transfer

import dev.devicelink.model.SecureChannel
import java.nio.ByteBuffer

/** The authenticated offer's payload id binds every chunk and its explicit end marker. */
internal object FileFrames {
    data class Frame(val payloadId: Long, val bytes: ByteArray?)
    fun encode(payloadId: Long, bytes: ByteArray?): ByteArray {
        require(bytes == null || bytes.isNotEmpty())
        require((bytes?.size ?: 0) <= SecureChannel.MAX_PLAINTEXT_BYTES - 9)
        return ByteBuffer.allocate(9 + (bytes?.size ?: 0))
            .put(if (bytes == null) 3.toByte() else 2.toByte()).putLong(payloadId)
            .apply { if (bytes != null) put(bytes) }.array()
    }
    fun decode(frame: ByteArray): Frame {
        require(frame.size in 9..SecureChannel.MAX_PLAINTEXT_BYTES)
        val buffer = ByteBuffer.wrap(frame)
        return when (buffer.get().toInt()) {
            2 -> { require(buffer.remaining() > 8); Frame(buffer.long, frame.copyOfRange(9, frame.size)) }
            3 -> { require(buffer.remaining() == 8); Frame(buffer.long, null) }
            else -> throw IllegalArgumentException("Invalid file frame")
        }
    }
}
