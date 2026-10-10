package dev.devicelink.sdk

/**
 * One pairing link waiting for its activity to be resumed (for example opened from the camera while
 * the phone was locked). Memory only: the link carries the QR secret and must never touch disk.
 */
internal class PendingPairing<C> {
    private var target: Class<*>? = null
    private var link: String? = null
    private var callback: C? = null
    private var at = 0L

    @Synchronized fun put(target: Class<*>, link: String, callback: C, now: Long) {
        this.target = target; this.link = link; this.callback = callback; at = now
    }

    /** Returns and clears the link when [resumed] is the activity it was opened for and it is still fresh. */
    @Synchronized fun take(resumed: Class<*>, now: Long): Pair<String, C>? {
        val value = link ?: return null
        if (now - at > MAX_AGE_MILLIS) { clear(); return null }
        if (resumed != target) return null
        val result = value to callback!!
        clear()
        return result
    }

    @Synchronized fun isEmpty() = link == null

    private fun clear() { target = null; link = null; callback = null }

    companion object {
        /** Pairing codes live at most 10 minutes (relay cap); an older link cannot work. */
        const val MAX_AGE_MILLIS = 10 * 60_000L
    }
}
