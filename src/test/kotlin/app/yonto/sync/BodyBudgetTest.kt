package app.yonto.sync

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BodyBudgetTest {
    /** A body that arrives a few bytes at a time, as one over a network does. */
    private fun body(size: Int, chunk: Int = 7) = object : InputStream() {
        private var left = size
        override fun read(): Int = if (left-- > 0) ' '.code else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len, chunk, left)
            left -= n
            return n
        }
    }

    private fun BodyBudget.refusal(size: Int, client: String = "a", max: Int = 1000) =
        assertFailsWith<Refused> { read(body(size), max, client) { } }.let { it.status to it.code }

    @Test
    fun `a body up to its limit reads whole, and one byte more is too large`() {
        val budget = BodyBudget(bytes = 1000, perClient = 1000, freeBytes = 0)
        budget.read(body(1000), max = 1000, client = "a") { assertEquals(1000, it.size) }
        assertEquals(413 to "too_large", budget.refusal(1001))
        budget.read(body(1000), max = 1000, client = "a") { }
    }

    @Test
    fun `bodies held at once past the budget are unavailable, and their bytes come back once read`() {
        val budget = BodyBudget(bytes = 300, perClient = 300, freeBytes = 0)
        budget.read(body(200), max = 1000, client = "a") {
            assertEquals(503 to "unavailable", budget.refusal(200, client = "b"))
            budget.read(body(100), max = 1000, client = "b") { }
        }
        budget.read(body(300), max = 1000, client = "b") { }
    }

    @Test
    fun `one client holds no more than its share, and the rest is left for others`() {
        val budget = BodyBudget(bytes = 400, perClient = 100, freeBytes = 0)
        budget.read(body(100), max = 1000, client = "greedy") {
            assertEquals(503 to "unavailable", budget.refusal(1, client = "greedy"))
            budget.read(body(100), max = 1000, client = "honest") { }
        }
    }

    @Test
    fun `a small body is never turned away, however full the budget`() {
        val budget = BodyBudget(bytes = 100, perClient = 100, freeBytes = 50)
        budget.read(body(150), max = 1000, client = "a") {
            budget.read(body(50), max = 1000, client = "a") { }
            budget.read(body(50), max = 1000, client = "b") { }
            assertEquals(503 to "unavailable", budget.refusal(51, client = "b"))
        }
    }
}
