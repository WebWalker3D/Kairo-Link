package icu.kairo.link.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

const val HANDSHAKE_TIMEOUT_MS = 10_000L

/** The phone with internet. Authenticates the body, then dials allowlisted destinations for it. */
class RelaySession(
    private val secret: ByteArray,
    private val allowlist: Allowlist = Allowlist.KAIRO,
    private val dialer: Dialer = Dialer.DEFAULT,
) {
    /** Authenticates the peer on a fresh channel and returns its running mux. Throws on failure. */
    fun accept(input: InputStream, output: OutputStream, transport: Closeable, listener: Mux.Listener = object : Mux.Listener {}): Mux {
        val ok = Timeouts.guard(HANDSHAKE_TIMEOUT_MS, { closeQuietly(transport) }) {
            Handshake.relay(input, output, secret)
        }
        if (!ok) throw AuthException("body failed authentication")
        return Mux(input, output, opener = ::open, listener = listener, transport = transport).start()
    }

    private fun open(host: String, port: Int): Endpoint {
        Diagnostics.endpoint(host, port)?.let { return it }
        if (!allowlist.permits(host, port)) throw IOException("destination $host:$port is not allowed")
        return SocketEndpoint(dialer.dial(host, port))
    }
}

/** The body side: authenticates the relay and returns the running mux. */
object LinkClient {
    fun connect(input: InputStream, output: OutputStream, transport: Closeable, secret: ByteArray, listener: Mux.Listener = object : Mux.Listener {}): Mux {
        Timeouts.guard(HANDSHAKE_TIMEOUT_MS, { closeQuietly(transport) }) {
            Handshake.client(input, output, secret)
        }
        return Mux(input, output, listener = listener, transport = transport).start()
    }
}

/**
 * Built-in relay targets for measuring the link itself, reachable only on port 1 under
 * `.kairo-link.diag`; they never touch the network:
 *
 * - `echo.kairo-link.diag` echoes bytes back (round-trip latency)
 * - `source-N.kairo-link.diag` sends N bytes then EOF (relay -> body throughput)
 * - `sink-N.kairo-link.diag` swallows N bytes (or until EOF), then answers the count as u64 (body -> relay).
 *   Answering at N matters: adb port forwarding cannot carry a half-close.
 */
object Diagnostics {
    const val SUFFIX = ".kairo-link.diag"
    const val PORT = 1
    val ALLOW = listOf("*.kairo-link.diag:1")
    private const val MAX_SOURCE = 16 * 1024 * 1024

    fun endpoint(host: String, port: Int): Endpoint? {
        val h = Allowlist.normalizeHost(host) ?: return null
        if (port != PORT || !h.endsWith(SUFFIX)) return null
        val name = h.removeSuffix(SUFFIX)
        return when {
            name == "echo" -> echo()
            name.startsWith("sink-") -> name.removePrefix("sink-").toLongOrNull()?.takeIf { it > 0 }?.let(::sink)
            name.startsWith("source-") -> name.removePrefix("source-").toIntOrNull()
                ?.takeIf { it in 0..MAX_SOURCE }?.let(::source)
            else -> null
        } ?: throw IOException("unknown diagnostic target $h")
    }

    private fun echo(): Endpoint {
        val q = ByteQueue()
        return object : Endpoint {
            override val input = q.input
            override val output = q.output
            override fun shutdownOutput() = q.output.close()
            override fun close() { q.output.close(); q.input.close() }
        }
    }

    private fun source(total: Int): Endpoint = object : Endpoint {
        private var left = total
        override val input = object : InputStream() {
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len, left)
                java.util.Arrays.fill(b, off, off + n, 0x5A)
                left -= n
                return n
            }
        }
        override val output = OutputStream.nullOutputStream()
        override fun shutdownOutput() {}
        override fun close() { left = 0 }
    }

    private fun sink(expected: Long): Endpoint {
        val answer = ByteQueue(16)
        var count = 0L
        var answered = false
        fun reply() {
            if (answered) return
            answered = true
            val c = count
            answer.output.write(ByteArray(8) { i -> (c ushr (56 - 8 * i)).toByte() })
            answer.output.close()
        }
        return object : Endpoint {
            override val input = answer.input
            override val output = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) { count += len; if (count >= expected) reply() }
            }
            override fun shutdownOutput() = reply()
            override fun close() { answer.output.close(); answer.input.close() }
        }
    }
}

internal fun closeQuietly(c: Closeable) { try { c.close() } catch (_: IOException) {} }
