package icu.kairo.link.core.rover

import java.io.IOException
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Screen-owned MJPEG lifecycle. A failed request is retried without a new BLE URL.
 * Epochs also fence callbacks already queued by a previous visit to the same URL.
 * URLs contain session credentials: never log them or raw transport exceptions.
 */
class RoverLiveView(
    private val connect: (String) -> Connection = { HttpConnection(it) },
    private val retryDelayMillis: Long = 1500,
    private val onFrame: (ByteArray, Long) -> Unit,
    private val onState: (State, Long) -> Unit = { _, _ -> },
) {
    enum class State { CONNECTING, RETRYING }
    interface Connection : AutoCloseable { fun input(): InputStream }

    private class Session(val epoch: Long, val url: String) {
        var connection: Connection? = null
        lateinit var worker: Thread
    }
    private val lock = Any()
    private var epoch = 0L
    private var session: Session? = null
    private var visible = true

    init { require(retryDelayMillis > 0) }

    fun isCurrent(id: Long): Boolean = synchronized(lock) { session?.epoch == id }

    fun resume() { synchronized(lock) { visible = true } }
    fun pause() {
        synchronized(lock) { visible = false }
        stop()
    }

    fun start(url: String) {
        stop()
        val next = synchronized(lock) {
            if (!visible) return
            Session(++epoch, url).also { s ->
                s.worker = Thread({ run(s) }, "rover-live-view").apply { isDaemon = true }
                session = s
            }
        }
        next.worker.start()
    }

    fun stop() {
        val previous = synchronized(lock) {
            session.also { session = null; epoch++ }
        } ?: return
        previous.worker.interrupt()
        // Interrupt alone cannot cancel an HTTP socket read. Close the actual
        // connection as well, outside the lifecycle lock.
        runCatching { previous.connection?.close() }
    }

    private fun run(s: Session) {
        while (isCurrent(s.epoch) && !Thread.currentThread().isInterrupted) {
            if (isCurrent(s.epoch)) onState(State.CONNECTING, s.epoch)
            var connection: Connection? = null
            try {
                connection = connect(s.url)
                val admitted = synchronized(lock) {
                    if (session === s) { s.connection = connection; true } else false
                }
                if (!admitted) break
                connection.input().use { input ->
                    val reader = MjpegReader(input)
                    while (isCurrent(s.epoch) && !Thread.currentThread().isInterrupted) {
                        val frame = reader.next() ?: break
                        if (isCurrent(s.epoch)) onFrame(frame, s.epoch)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // State is observable below; exception strings may expose the
                // session token embedded in the URL.
            } finally {
                synchronized(lock) { if (s.connection === connection) s.connection = null }
                runCatching { connection?.close() }
            }
            if (!isCurrent(s.epoch) || Thread.currentThread().isInterrupted) break
            onState(State.RETRYING, s.epoch)
            try { Thread.sleep(retryDelayMillis) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
    }

    internal class HttpConnection(url: String) : Connection {
        // Call.cancel() is permanent even before execute(). A disconnected
        // HttpURLConnection can otherwise reopen when input() races stop().
        private val call = http.newCall(Request.Builder().url(url).build())
        private val response = AtomicReference<Response?>()
        override fun input(): InputStream {
            val received = call.execute()
            response.set(received)
            if (call.isCanceled() || received.code != 200) {
                response.getAndSet(null)?.close()
                throw IOException("Camera request unavailable")
            }
            return received.body?.byteStream() ?: throw IOException("Camera response empty")
        }
        override fun close() {
            call.cancel()
            response.getAndSet(null)?.close()
        }
        companion object {
            private val http = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .build()
        }
    }
}
