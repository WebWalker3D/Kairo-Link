package icu.kairo.link.core

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Measures the link from the body phone itself, through the loopback diagnostic proxy, so
 * figures are not polluted by Wi-Fi or adb. Returns a human-readable report.
 *
 * "Cold" round trips start after the link has idled into its low-power interval - that is what
 * the first byte of a fresh Kairo request experiences. "Warm" round trips are back to back.
 */
object SelfTest {
    fun run(
        proxyPort: Int,
        https: String? = "connect.kairo.icu",
        rounds: Int = 20,
        coldIdleMs: Long = 6_000,
        progress: (String) -> Unit = {},
    ): String = buildString {
        fun line(s: String) { appendLine(s); progress(s) }
        fun attempt(label: String, block: () -> String) {
            progress("$label ...")
            try { line(block()) } catch (e: Throwable) { line("$label FAILED: ${e.message ?: e.javaClass.simpleName}") }
        }

        attempt("round trip") {
            tunnel(proxyPort, "echo.kairo-link.diag:1").use { s ->
                val warm = (1..rounds).map { ping(s) }.sorted()
                val cold = (1..3).map { Thread.sleep(coldIdleMs); ping(s) }.sorted()
                "round trip   warm median %.0f ms (min %.0f, max %.0f)   cold after idle median %.0f ms (max %.0f)"
                    .format(warm[warm.size / 2], warm.first(), warm.last(), cold[1], cold.last())
            }
        }
        for ((size, label) in listOf(30_000 to "30 KB snapshot", 200_000 to "200 KB")) {
            attempt("download $label") {
                val t = System.nanoTime()
                val got = tunnel(proxyPort, "source-$size.kairo-link.diag:1").use { s -> drain(s.getInputStream()) }
                if (got != size.toLong()) throw IOException("expected $size bytes, got $got")
                rate("relay -> body", label, size, t)
            }
            attempt("upload $label") {
                val t = System.nanoTime()
                val counted = tunnel(proxyPort, "sink-$size.kairo-link.diag:1").use { s ->
                    s.getOutputStream().apply { write(ByteArray(size)); flush() }
                    DataInputStream(s.getInputStream()).readLong()
                }
                if (counted != size.toLong()) throw IOException("relay counted $counted of $size bytes")
                rate("body -> relay", label, size, t)
            }
        }
        if (https != null) attempt("https") {
            val t = System.nanoTime()
            val raw = tunnel(proxyPort, "$https:443")
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, https, 443, true) as SSLSocket
            tls.use {
                it.startHandshake()
                it.getOutputStream().apply {
                    write("GET / HTTP/1.1\r\nHost: $https\r\nConnection: close\r\nUser-Agent: kairo-link-selftest\r\n\r\n".toByteArray())
                    flush()
                }
                val input = it.getInputStream()
                val head = ConnectProxy.readHead(input) ?: throw IOException("no response")
                val body = drain(input)
                "https://$https/ -> ${head.first()}, %d KB page in %.2f s incl. TLS handshake".format(body / 1024, secondsSince(t))
            }
        }
    }

    private fun tunnel(port: Int, target: String): Socket {
        val s = Socket(InetAddress.getLoopbackAddress(), port)
        s.tcpNoDelay = true
        s.soTimeout = 60_000
        s.getOutputStream().apply { write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray()); flush() }
        val head = ConnectProxy.readHead(s.getInputStream()) ?: throw IOException("proxy closed")
        if (!head.first().startsWith("HTTP/1.1 200")) { s.close(); throw IOException(head.first()) }
        return s
    }

    private fun ping(s: Socket): Double {
        val t = System.nanoTime()
        s.getOutputStream().apply { write(0x6B); flush() }
        if (s.getInputStream().read() != 0x6B) throw IOException("echo mismatch")
        return (System.nanoTime() - t) / 1e6
    }

    private fun drain(input: InputStream): Long {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) { val n = input.read(buf); if (n < 0) return total; total += n }
    }

    private fun secondsSince(t: Long) = (System.nanoTime() - t) / 1e9

    private fun rate(direction: String, label: String, size: Int, t: Long): String {
        val s = secondsSince(t)
        return "%s  %-15s %5.2f s  %6.1f KB/s".format(direction, label, s, size / s / 1024)
    }
}
