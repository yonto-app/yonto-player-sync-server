package app.yonto.sync

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Caps the bytes of request bodies held at once, so clients sending large bodies together cannot run the heap out.
 * Each body's first [freeBytes] are its own, so a small request is never turned away for want of room, and one client
 * holds at most [perClient] of the rest; past either a request is 503 `unavailable` until others finish.
 */
class BodyBudget(val bytes: Int, private val perClient: Int, private val freeBytes: Int = FREE_BYTES) {
    private var left = bytes
    private val byClient = HashMap<String, Int>()

    /** Reads [input] whole, at most [max] bytes, and gives it to [use] while its bytes still count against [client]. */
    fun <T> read(input: InputStream, max: Int, client: String, use: (ByteArray) -> T): T {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var held = 0
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val size = body.size()
                if (size + read > max) throw Refused(413, "too_large")
                val counted = maxOf(0, size + read - freeBytes) - maxOf(0, size - freeBytes)
                if (counted > 0 && !take(client, counted)) throw Refused(503, "unavailable", RETRY_SECONDS)
                held += counted
                body.write(buffer, 0, read)
            }
            return use(body.toByteArray())
        } finally {
            if (held > 0) give(client, held)
        }
    }

    @Synchronized
    private fun take(client: String, count: Int): Boolean {
        val mine = byClient[client] ?: 0
        if (count > left || mine + count > perClient) return false
        left -= count
        byClient[client] = mine + count
        return true
    }

    @Synchronized
    private fun give(client: String, count: Int) {
        left += count
        val mine = (byClient[client] ?: 0) - count
        if (mine > 0) byClient[client] = mine else byClient.remove(client)
    }

    companion object {
        const val RETRY_SECONDS = 5L
        const val FREE_BYTES = 16 * 1024

        /**
         * A sixth of the heap, and never less than four bodies of [maxBodyBytes]; one client may hold a quarter of it,
         * and never less than one whole body.
         */
        fun forHeap(maxBodyBytes: Int): BodyBudget {
            val bytes = maxOf(Runtime.getRuntime().maxMemory() / 6, 4L * maxBodyBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return BodyBudget(bytes, perClient = maxOf(bytes / 4, maxBodyBytes))
        }
    }
}
