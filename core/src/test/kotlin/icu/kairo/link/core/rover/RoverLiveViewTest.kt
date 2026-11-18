package icu.kairo.link.core.rover

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RoverLiveViewTest {
    private val frame = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
    private fun await(latch: CountDownLatch) = assertTrue("worker deadline", latch.await(3, TimeUnit.SECONDS))

    @Test fun retries_failed_connection_without_a_new_bluetooth_url() {
        val attempts = AtomicInteger()
        val received = CountDownLatch(1)
        val states = java.util.concurrent.CopyOnWriteArrayList<RoverLiveView.State>()
        val view = RoverLiveView(connect = {
            val failed = attempts.incrementAndGet() == 1
            object : RoverLiveView.Connection {
                override fun input(): InputStream = if (failed) throw IOException("connection failed") else ByteArrayInputStream(frame)
                override fun close() {}
            }
        }, retryDelayMillis = 10, onFrame = { bytes, _ ->
            assertArrayEquals(frame, bytes); received.countDown()
        }, onState = { state, _ -> states.add(state) })
        try {
            view.start("http://rover/stream?t=private")
            await(received)
            assertTrue(attempts.get() >= 2)
            assertTrue(states.contains(RoverLiveView.State.RETRYING))
        } finally { view.stop() }
    }

    @Test fun retries_eof_instead_of_leaving_a_dead_preview() {
        val attempts = AtomicInteger()
        val received = CountDownLatch(1)
        val view = RoverLiveView(connect = {
            val bytes = if (attempts.incrementAndGet() == 1) byteArrayOf() else frame
            object : RoverLiveView.Connection {
                override fun input() = ByteArrayInputStream(bytes)
                override fun close() {}
            }
        }, retryDelayMillis = 10, onFrame = { _, _ -> received.countDown() })
        try { view.start("http://rover/stream"); await(received); assertTrue(attempts.get() >= 2) }
        finally { view.stop() }
    }

    @Test fun stop_closes_blocked_read_and_invalidates_queued_callbacks() {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        var epoch = -1L
        val view = RoverLiveView(connect = {
            object : RoverLiveView.Connection {
                override fun input() = object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        // Real socket reads ignore Thread.interrupt(). Only
                        // closing the connection may unblock this fake.
                        while (closed.count != 0L) {
                            try { closed.await() } catch (_: InterruptedException) { }
                        }
                        return -1
                    }
                }
                override fun close() { closed.countDown() }
            }
        }, retryDelayMillis = 10, onFrame = { _, _ -> callbacks.incrementAndGet() },
            onState = { _, id -> epoch = id })
        view.start("http://rover/stream")
        await(reading)
        assertTrue(view.isCurrent(epoch))
        view.stop()
        await(closed)
        assertFalse(view.isCurrent(epoch))
        assertEquals(0, callbacks.get())
    }

    @Test fun restarting_same_url_cannot_make_an_old_frame_current_again() {
        val epochs = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val started = java.util.concurrent.Semaphore(0)
        val view = RoverLiveView(connect = {
            object : RoverLiveView.Connection {
                override fun input() = ByteArrayInputStream(byteArrayOf())
                override fun close() {}
            }
        }, retryDelayMillis = 10000, onFrame = { _, _ -> }, onState = { state, id ->
            if (state == RoverLiveView.State.CONNECTING) { epochs.add(id); started.release() }
        })
        try {
            view.start("http://rover/stream")
            assertTrue(started.tryAcquire(3, TimeUnit.SECONDS))
            val old = epochs.last()
            view.stop(); view.start("http://rover/stream")
            assertTrue(started.tryAcquire(3, TimeUnit.SECONDS))
            assertFalse(view.isCurrent(old)); assertTrue(view.isCurrent(epochs.last()))
        } finally { view.stop() }
    }

    @Test fun cancelled_transport_cannot_open_a_socket_later() {
        ServerSocket(0).use { server ->
            server.soTimeout = 150
            val connection = RoverLiveView.HttpConnection("http://127.0.0.1:${server.localPort}/stream")
            connection.close()
            try { connection.input(); fail("cancelled request succeeded") } catch (_: IOException) { }
            try {
                server.accept().close()
                fail("cancelled transport opened a socket")
            } catch (_: SocketTimeoutException) { }
        }
    }

    @Test fun late_bluetooth_url_cannot_restart_preview_after_screen_pause() {
        val opened = CountDownLatch(1)
        val view = RoverLiveView(connect = {
            opened.countDown()
            object : RoverLiveView.Connection {
                override fun input() = ByteArrayInputStream(frame)
                override fun close() {}
            }
        }, onFrame = { _, _ -> })
        view.pause()
        try {
            view.start("http://rover/stream")
            assertFalse("background preview started", opened.await(150, TimeUnit.MILLISECONDS))
        } finally { view.stop() }
    }
}
