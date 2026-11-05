package app.yonto.sync

import java.net.Inet6Address
import java.net.InetAddress

/** Counts each client's requests in flight, so one client cannot hold every connection the server has. */
class InFlight(private val perClient: Int) {
    private val counts = HashMap<String, Int>()

    /** False when [client] already has [perClient] requests in flight; otherwise it has one more until [leave]. */
    @Synchronized
    fun enter(client: String): Boolean {
        val count = counts[client] ?: 0
        if (count >= perClient) return false
        counts[client] = count + 1
        return true
    }

    @Synchronized
    fun leave(client: String) {
        val count = (counts[client] ?: 0) - 1
        if (count > 0) counts[client] = count else counts.remove(client)
    }
}

/**
 * The client an address stands for: an IPv4 address itself, and an IPv6 one by its /64, which one household or one
 * host is given whole and so could otherwise count as endless clients.
 */
fun clientOf(address: String): String {
    if (address.isEmpty() || !address.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) return address
    val parsed = runCatching { InetAddress.ofLiteral(address.substringBefore('%')) }.getOrNull() ?: return address
    if (parsed !is Inet6Address) return parsed.hostAddress
    val bytes = parsed.address
    return (0 until 4).joinToString(":", postfix = "::/64") { i ->
        "%x".format((bytes[2 * i].toInt() and 0xff shl 8) or (bytes[2 * i + 1].toInt() and 0xff))
    }
}
