package app.yonto.sync

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlowClientTest {
    private fun behindProxy() = Harness({ it.copy(behindProxy = true) })

    @Test
    fun `clients trickling bodies the server reads, bodies it refuses, or headers hold nobody else up`() = behindProxy().use { h ->
        val slow = (1..SLOW_CLIENTS).flatMap { i ->
            listOf(
                "PUT /v1/account HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 10.1.0.$i\r\nYonto-Account: lic_slow$i\r\nContent-Length: 60000\r\n\r\n{\"verifier\":",
                "POST /v1/records HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 10.2.0.$i\r\nYonto-Account: $ACCOUNT\r\nContent-Length: 60000\r\n\r\n{\"records\":",
                "GET /v1/account HTTP/1.1\r\nHost: x\r\nX-Slow: 1\r\n",
            ).map { opening -> h.socket().also { it.send(opening) } }
        }
        try {
            h.assertAnswersQuickly("10.3.0.1")
        } finally {
            slow.forEach(Socket::close)
        }
    }

    @Test
    fun `a client past its requests in flight is answered busy and cut off, and nobody else is`() = behindProxy().use { h ->
        val opening = "PUT /v1/account HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 10.9.9.9\r\nYonto-Account: lic_slow\r\nContent-Length: 60000\r\n\r\n{"
        val held = (1..SyncServer.MAX_IN_FLIGHT_PER_CLIENT).map { h.socket().also { it.send(opening) } }
        Thread.sleep(500)
        try {
            h.assertAnswersQuickly("10.9.9.10")
            h.socket().use { over ->
                over.send(opening)
                val reader = over.reader()
                assertEquals("HTTP/1.1 429", reader.readLine().trim())
                val started = System.nanoTime()
                while (reader.readLine() != null) Unit
                assertTrue(System.nanoTime() - started < 2_000_000_000, "the connection was held, not cut off")
            }
        } finally {
            held.forEach(Socket::close)
        }
        Thread.sleep(500)
        h.assertAnswersQuickly("10.9.9.9")
    }

    @Test
    fun `a connection stays open after a request with no body and after a refused one sent whole`() = Harness().use { h ->
        val body = "{\"records\":[]}"
        h.socket().use { socket ->
            val reader = socket.reader()
            socket.send("GET /v1/account HTTP/1.1\r\nHost: x\r\nYonto-Account: $ACCOUNT\r\n\r\n")
            assertEquals("HTTP/1.1 404 Not Found", reader.answerStatus())
            socket.send("POST /v1/records HTTP/1.1\r\nHost: x\r\nYonto-Account: $ACCOUNT\r\nContent-Length: ${body.length}\r\n\r\n$body")
            assertEquals("HTTP/1.1 404 Not Found", reader.answerStatus())
            socket.send("GET /v1/account HTTP/1.1\r\nHost: x\r\nYonto-Account: $ACCOUNT\r\n\r\n")
            assertEquals("HTTP/1.1 404 Not Found", reader.answerStatus())
        }
    }

    private fun Harness.assertAnswersQuickly(client: String) {
        val started = System.nanoTime()
        socket().use { socket ->
            socket.send("GET /v1/account HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: $client\r\nYonto-Account: $ACCOUNT\r\n\r\n")
            assertEquals("HTTP/1.1 404 Not Found", socket.reader().readLine())
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 2000, "answered only after $millis ms")
    }

    private fun Harness.socket() = Socket("127.0.0.1", server.port).apply { soTimeout = 5000 }

    private fun Socket.send(text: String) {
        getOutputStream().apply { write(text.toByteArray()); flush() }
    }

    private fun Socket.reader() = BufferedReader(InputStreamReader(getInputStream(), Charsets.ISO_8859_1))

    /** Reads one answer through its body and gives its status line. */
    private fun BufferedReader.answerStatus(): String {
        val status = readLine()
        var length = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            if (line.startsWith("Content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        repeat(length) { read() }
        return status
    }

    private companion object {
        /** Of each kind: together far more than any pool of platform threads the server used to have. */
        const val SLOW_CLIENTS = 100
    }
}
