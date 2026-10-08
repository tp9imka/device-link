package dev.devicelink.transfer

import dev.devicelink.model.IncomingClip

/** One-shot capabilities for the application's synchronous clipboard write, scoped to a live session. */
internal class ClipboardDeliveries {
    private val pending = linkedMapOf<String, IncomingClip>()

    fun offer(id: String, text: String, session: Long): IncomingClip? {
        require(id !in pending)
        if (pending.size >= MAX_PENDING) return null
        return IncomingClip(id, text, session).also { pending[id] = it }
    }

    fun isPending(event: IncomingClip, session: Long, connected: Boolean): Boolean =
        connected && event.sessionToken == session && pending[event.id] == event

    fun consume(event: IncomingClip, session: Long, connected: Boolean): Boolean {
        if (!isPending(event, session, connected)) return false
        pending.remove(event.id)
        return true
    }

    fun discard(id: String) { pending.remove(id) }
    fun clear() { pending.clear() }

    companion object { const val MAX_PENDING = 20 }
}
