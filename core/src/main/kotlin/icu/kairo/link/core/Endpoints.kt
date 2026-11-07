package icu.kairo.link.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One end of a byte stream the mux can carry: a TCP socket, a local proxy client, a test fixture. */
interface Endpoint : Closeable {
    val input: InputStream
    val output: OutputStream
    /** Signal end-of-data to the reader of [output] while still reading [input]. */
    fun shutdownOutput()
}

class SocketEndpoint(private val socket: Socket, override val input: InputStream = socket.getInputStream()) : Endpoint {
    override val output: OutputStream = socket.getOutputStream()
    override fun shutdownOutput() { try { socket.shutdownOutput() } catch (_: IOException) {} }
    override fun close() { try { socket.close() } catch (_: IOException) {} }
}

/** Opens TCP connections; the Android side swaps in a network-bound factory. */
fun interface Dialer {
    @Throws(IOException::class)
    fun dial(host: String, port: Int): Socket

    companion object {
        val DEFAULT = Dialer { host, port ->
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), 10_000)
            }
        }
    }
}

/** A bounded in-memory pipe whose ends are usable from different threads. */
class ByteQueue(capacity: Int = 64 * 1024) {
    private val lock = Object()
    private val data = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var writerClosed = false
    private var readerClosed = false

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(lock) {
                while (size == 0) {
                    if (readerClosed) throw IOException("queue closed")
                    if (writerClosed) return -1
                    lock.wait()
                }
                val n = minOf(len, size, data.size - head)
                System.arraycopy(data, head, b, off, n)
                head = (head + n) % data.size
                size -= n
                lock.notifyAll()
                return n
            }
        }

        override fun close() { synchronized(lock) { readerClosed = true; lock.notifyAll() } }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                var o = off
                var remaining = len
                while (remaining > 0) {
                    if (readerClosed || writerClosed) throw IOException("queue closed")
                    if (size == data.size) { lock.wait(); continue }
                    val tail = (head + size) % data.size
                    val n = minOf(remaining, data.size - size, data.size - tail)
                    System.arraycopy(b, o, data, tail, n)
                    size += n; o += n; remaining -= n
                    lock.notifyAll()
                }
            }
        }

        override fun close() { synchronized(lock) { writerClosed = true; lock.notifyAll() } }
    }
}

/** Copies both directions between two endpoints, half-closing as each side finishes. */
object Splice {
    fun start(a: Endpoint, b: Endpoint, onActivity: () -> Unit = {}) {
        val done = java.util.concurrent.atomic.AtomicInteger()
        fun finish() { if (done.incrementAndGet() == 2) { a.close(); b.close() } }
        fun pump(from: Endpoint, to: Endpoint, name: String) = thread(name = name, isDaemon = true) {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = from.input.read(buf)
                    if (n < 0) break
                    to.output.write(buf, 0, n)
                    to.output.flush()
                    onActivity()
                }
                to.shutdownOutput()
                finish()
            } catch (_: IOException) {
                a.close(); b.close()
            }
        }
        pump(a, b, "kairo-link-splice-up")
        pump(b, a, "kairo-link-splice-down")
    }
}

object Timeouts {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kairo-link-timeouts").apply { isDaemon = true }
    }

    /** Runs [block]; if it takes longer than [timeoutMs], [onTimeout] runs (typically closing the transport). */
    fun <T> guard(timeoutMs: Long, onTimeout: () -> Unit, block: () -> T): T {
        val pending = scheduler.schedule({ onTimeout() }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            pending.cancel(false)
        }
    }
}
