package app.yonto.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientsTest {
    @Test
    fun `an IPv4 address is its own client, and an IPv6 one counts by its 64`() {
        assertEquals("203.0.113.7", clientOf("203.0.113.7"))
        assertEquals("203.0.113.7", clientOf("::ffff:203.0.113.7"))
        assertEquals("2001:db8:1:2::/64", clientOf("2001:db8:1:2:aaaa::1"))
        assertEquals(clientOf("2001:db8:1:2::1"), clientOf("2001:0db8:0001:0002:ffff:ffff:ffff:ffff"))
        assertEquals("not an address", clientOf("not an address"))
        assertEquals("example.com", clientOf("example.com"))
    }

    @Test
    fun `a client has as many requests in flight as it may, and one more once one leaves`() {
        val inFlight = InFlight(2)
        assertTrue(inFlight.enter("a"))
        assertTrue(inFlight.enter("a"))
        assertFalse(inFlight.enter("a"))
        assertTrue(inFlight.enter("b"))
        inFlight.leave("a")
        assertTrue(inFlight.enter("a"))
    }
}
