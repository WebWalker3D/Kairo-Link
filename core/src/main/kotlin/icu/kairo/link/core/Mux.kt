package icu.kairo.link.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Carries many TCP-like streams over one ordered byte channel (the BLE L2CAP socket).
 *
 * Each stream has its own credit window, so one stalled consumer can never block
 * the shared channel for the others. Nothing is sent while streams are idle: no
 * keepalives, no polling - an idle link costs only the radio's own connection events.
 */
class Mux(
    private val rawInput: InputStream,
    private val rawOutput: OutputStream,
    private val opener: Opener? = null,
    private val listener: Listener = object : Listener {},
    private val transport: Closeable? = null,
) : Closeable {

    /** Relay side: connect a destination for an OPEN frame. Runs on its own thread; throw to refuse. */
    fun interface Opener {
        @Throws(IOException::class)
        fun open(host: String, port: Int): Endpoint
    }

    interface Listener {
        /** Called for every frame sent or received - keep it cheap. */
        fun onActivity() {}
        fun onClosed(cause: Throwable?) {}
    }

    companion object {
        const val WINDOW = 64 * 1024
        private val EOF_MARK = Any()
        private val RESET_MARK = Any()
    }

    private val reader = FrameReader(rawInput)
    private val writer = FrameWriter(rawOutput)
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextSid = AtomicInteger(1)
    private val closed = AtomicBoolean(false)
    private val closedLatch = CountDownLatch(1)

    /** Bytes put on / taken off the link, framing included. */
    val bytesSent = AtomicLong()
    val bytesReceived = AtomicLong()
    val streamCount: Int get() = streams.size
    val isOpen: Boolean get() = !closed.get()

    fun start(): Mux {
        thread(name = "kairo-link-mux", isDaemon = true) { readLoop() }
        return this
    }

    fun awaitClosed(timeoutMs: Long) = closedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Client side: open a stream to [host]:[port] through the relay and splice it to [endpoint].
     * [onOpened] runs once the relay has connected, before any data flows (the proxy uses it to
     * answer `200 Connection established`). Throws if the relay refuses or times out; in that case
     * [endpoint] is untouched and still belongs to the caller.
     */
    fun open(host: String, port: Int, endpoint: Endpoint, timeoutMs: Long = 20_000, onOpened: () -> Unit = {}) {
        if (closed.get()) throw IOException("link is down")
        val sid = allocateSid()
        val stream = Stream(sid)
        streams[sid] = stream
        send(FrameType.OPEN, sid, encodeOpen(host, port))
        if (!stream.openedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            stream.resetLocal("open timed out")
            throw IOException("relay did not answer within ${timeoutMs}ms")
        }
        stream.openError?.let { throw IOException(it) }
        if (!stream.attach(endpoint)) throw IOException(stream.openError ?: "stream closed")
        try {
            onOpened()
        } catch (e: Exception) {
            stream.resetLocal("client went away")
            throw IOException("could not confirm stream", e)
        }
        stream.startPumps()
    }

    override fun close() = shutdown(null)

    private fun allocateSid(): Int {
        while (true) {
            val id = nextSid.getAndIncrement() and 0xFFFF
            if (id != 0 && !streams.containsKey(id)) return id
        }
    }

    private fun readLoop() {
        var cause: Throwable? = null
        try {
            while (!closed.get()) {
                val frame = reader.read() ?: break
                bytesReceived.addAndGet((HEADER_SIZE + frame.payload.size).toLong())
                listener.onActivity()
                dispatch(frame)
            }
        } catch (e: Throwable) {
            cause = e
        }
        shutdown(cause)
    }

    private fun dispatch(f: Frame) {
        when (f.type) {
            FrameType.OPEN -> handleOpen(f)
            FrameType.OPENED -> streams[f.sid]?.openedLatch?.countDown()
            FrameType.DATA -> streams[f.sid]?.onData(f.payload)
            FrameType.CREDIT -> streams[f.sid]?.addCredit(bytesInt(f.payload))
            FrameType.EOF -> streams[f.sid]?.onRemoteEof()
            FrameType.RESET -> streams[f.sid]?.onRemoteReset(String(f.payload, Charsets.UTF_8))
            else -> throw ProtocolException("unknown frame type ${f.type}")
        }
    }

    private fun handleOpen(f: Frame) {
        val sid = f.sid
        if (opener == null) { sendQuietly(FrameType.RESET, sid, "not a relay".toByteArray()); return }
        if (sid == 0 || streams.containsKey(sid)) throw ProtocolException("duplicate stream $sid")
        val (host, port) = decodeOpen(f.payload)
        val stream = Stream(sid)
        streams[sid] = stream
        thread(name = "kairo-link-open-$sid", isDaemon = true) {
            val endpoint = try {
                opener.open(host, port)
            } catch (e: Exception) {
                stream.resetLocal(e.message ?: e.javaClass.simpleName)
                return@thread
            }
            if (!stream.attach(endpoint)) { endpoint.close(); return@thread }
            try {
                send(FrameType.OPENED, sid)
            } catch (_: IOException) {
                return@thread
            }
            stream.startPumps()
        }
    }

    private fun send(type: Int, sid: Int, payload: ByteArray = ByteArray(0), length: Int = payload.size) {
        if (closed.get()) throw IOException("link is down")
        try {
            writer.write(type, sid, payload, length)
        } catch (e: IOException) {
            shutdown(e)
            throw e
        }
        bytesSent.addAndGet((HEADER_SIZE + length).toLong())
        listener.onActivity()
    }

    private fun sendQuietly(type: Int, sid: Int, payload: ByteArray) {
        try { send(type, sid, payload) } catch (_: IOException) {}
    }

    private fun shutdown(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        try { transport?.close() } catch (_: IOException) {}
        try { rawInput.close() } catch (_: IOException) {}
        try { rawOutput.close() } catch (_: IOException) {}
        streams.values.toList().forEach { it.teardown("link closed") }
        streams.clear()
        closedLatch.countDown()
        listener.onClosed(cause)
    }

    private inner class Stream(val sid: Int) {
        @Volatile private var endpoint: Endpoint? = null
        private val inbound = LinkedBlockingQueue<Any>()
        private val inboundBytes = AtomicInteger()
        private val creditLock = Object()
        private var credit = WINDOW
        val openedLatch = CountDownLatch(1)
        @Volatile var openError: String? = null
        private val dead = AtomicBoolean(false)
        private val finishedPumps = AtomicInteger()

        fun attach(ep: Endpoint): Boolean = synchronized(this) {
            if (dead.get()) false else { endpoint = ep; true }
        }

        fun onData(b: ByteArray) {
            if (inboundBytes.addAndGet(b.size) > WINDOW) { resetLocal("peer exceeded flow-control window"); return }
            inbound.offer(b)
        }

        fun onRemoteEof() { inbound.offer(EOF_MARK) }

        fun onRemoteReset(reason: String) {
            if (openError == null) openError = reason.ifEmpty { "reset by peer" }
            teardown(reason)
        }

        fun addCredit(n: Int) = synchronized(creditLock) { credit += n; creditLock.notifyAll() }

        fun startPumps() {
            thread(name = "kairo-link-up-$sid", isDaemon = true) { uplink() }
            thread(name = "kairo-link-down-$sid", isDaemon = true) { downlink() }
        }

        /** Local endpoint -> link, never more than the peer has granted. */
        private fun uplink() {
            val ep = endpoint ?: return
            val buf = ByteArray(MAX_PAYLOAD)
            try {
                while (true) {
                    val allowance = synchronized(creditLock) {
                        while (credit <= 0 && !dead.get()) creditLock.wait()
                        credit
                    }
                    if (dead.get()) return
                    val n = ep.input.read(buf, 0, minOf(allowance, buf.size))
                    if (dead.get()) return
                    if (n < 0) { send(FrameType.EOF, sid); break }
                    synchronized(creditLock) { credit -= n }
                    send(FrameType.DATA, sid, buf, n)
                }
            } catch (e: Exception) {
                resetLocal("upstream: ${e.message ?: e.javaClass.simpleName}")
                return
            }
            finishPump()
        }

        /** Link -> local endpoint, returning credit in batches so idle streams stay silent. */
        private fun downlink() {
            val ep = endpoint ?: return
            var unacked = 0
            try {
                while (true) {
                    if (unacked > 0 && inbound.isEmpty()) {
                        send(FrameType.CREDIT, sid, intBytes(unacked)); unacked = 0
                    }
                    when (val item = inbound.take()) {
                        is ByteArray -> {
                            ep.output.write(item)
                            ep.output.flush()
                            inboundBytes.addAndGet(-item.size)
                            unacked += item.size
                            if (unacked >= WINDOW / 4) { send(FrameType.CREDIT, sid, intBytes(unacked)); unacked = 0 }
                        }
                        EOF_MARK -> { ep.shutdownOutput(); break }
                        else -> return
                    }
                }
            } catch (e: Exception) {
                resetLocal("downstream: ${e.message ?: e.javaClass.simpleName}")
                return
            }
            finishPump()
        }

        private fun finishPump() {
            if (finishedPumps.incrementAndGet() == 2 && dead.compareAndSet(false, true)) {
                streams.remove(sid, this)
                endpoint?.close()
            }
        }

        fun resetLocal(reason: String) {
            if (dead.get()) return
            if (!closed.get()) sendQuietly(FrameType.RESET, sid, reason.take(200).toByteArray(Charsets.UTF_8))
            if (openError == null) openError = reason
            teardown(reason)
        }

        fun teardown(reason: String) {
            synchronized(this) { if (!dead.compareAndSet(false, true)) return }
            if (openError == null) openError = reason
            openedLatch.countDown()
            streams.remove(sid, this)
            endpoint?.close()
            inbound.clear()
            inbound.offer(RESET_MARK)
            synchronized(creditLock) { creditLock.notifyAll() }
        }
    }
}
