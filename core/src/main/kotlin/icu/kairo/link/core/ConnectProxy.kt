package icu.kairo.link.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A loopback-only HTTP `CONNECT` proxy. Apps on the body phone (OkHttp, HttpURLConnection,
 * curl) point at it; for each tunnel the [Router] decides whether to go out over this phone's
 * own network, over the Kairo-Link relay, or to refuse. TLS passes through untouched.
 */
class ConnectProxy(
    private val port: Int,
    private val allowlist: Allowlist,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val onActivity: () -> Unit = {},
    private val router: Router,
) : Closeable {

    sealed interface Route {
        class Tunnel(val mux: Mux) : Route
        class Direct(val dialer: Dialer) : Route
        class Refuse(val status: Int, val reason: String) : Route
    }

    fun interface Router {
        fun route(host: String, port: Int): Route
    }

    @Volatile private var server: ServerSocket? = null
    val boundPort: Int get() = server?.localPort ?: -1

    fun start(): ConnectProxy {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, port), 32)
        server = s
        thread(name = "kairo-link-proxy-${s.localPort}", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: IOException) { break }
                thread(name = "kairo-link-proxy-conn", isDaemon = true) { handle(client) }
            }
        }
        return this
    }

    override fun close() { server?.let(::closeQuietly) }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val lines = readHead(input) ?: return closeQuietly(client)
            val parts = lines.first().split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return respond(client, 400, "Bad Request")
            if (parts[0] != "CONNECT") return respond(client, 405, "Only CONNECT is supported")
            val (host, port) = parseAuthority(parts[1]) ?: return respond(client, 400, "Bad CONNECT target")
            if (!allowlist.permits(host, port)) return respond(client, 403, "Destination not allowed")
            client.soTimeout = 0
            val endpoint = SocketEndpoint(client, input)
            when (val route = router.route(host, port)) {
                is Route.Refuse -> respond(client, route.status, route.reason)
                is Route.Tunnel -> try {
                    route.mux.open(host, port, endpoint) { endpoint.output.write(ESTABLISHED); endpoint.output.flush() }
                } catch (e: IOException) {
                    respond(client, 502, "Kairo-Link relay: ${e.message}")
                }
                is Route.Direct -> {
                    val upstream = try {
                        route.dialer.dial(host, port)
                    } catch (e: IOException) {
                        return respond(client, 502, "Direct connection failed: ${e.message}")
                    }
                    endpoint.output.write(ESTABLISHED)
                    endpoint.output.flush()
                    Splice.start(endpoint, SocketEndpoint(upstream), onActivity)
                }
            }
        } catch (_: Exception) {
            closeQuietly(client)
        }
    }

    private fun respond(client: Socket, status: Int, reason: String) {
        val safe = reason.map { if (it in ' '..'~') it else ' ' }.joinToString("").take(200)
        try {
            client.getOutputStream().apply {
                write("HTTP/1.1 $status $safe\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
        } catch (_: IOException) {}
        closeQuietly(client)
    }

    companion object {
        private val ESTABLISHED = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII)
        private const val MAX_HEAD = 8192

        /** Reads the request head byte by byte so no tunnel bytes are consumed. Null on EOF/oversize. */
        internal fun readHead(input: InputStream): List<String>? {
            val buf = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0 || buf.size() >= MAX_HEAD) return null
                buf.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
            }
            return buf.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
        }

        internal fun parseAuthority(authority: String): Pair<String, Int>? {
            if (authority.startsWith("[")) return null
            val idx = authority.lastIndexOf(':')
            if (idx <= 0) return null
            val port = authority.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return authority.substring(0, idx) to port
        }
    }
}
