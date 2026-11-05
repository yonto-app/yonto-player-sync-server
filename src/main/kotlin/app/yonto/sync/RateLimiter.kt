package app.yonto.sync

/** A fixed window per key, a minute unless [windowMillis] says otherwise. */
class RateLimiter(private val perWindow: Int, private val clock: () -> Long, private val windowMillis: Long = MINUTE_MILLIS) {
    private var window = -1L
    private val counts = HashMap<String, Int>()

    /** Null when allowed, else the seconds until the window turns. */
    @Synchronized
    fun take(key: String): Long? {
        if (perWindow <= 0) return null
        val now = clock()
        val current = now / windowMillis
        if (current != window) {
            window = current
            counts.clear()
        }
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        if (count <= perWindow) return null
        val remaining = (current + 1) * windowMillis - now
        return (remaining + 999) / 1000
    }

    companion object {
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS
    }
}
