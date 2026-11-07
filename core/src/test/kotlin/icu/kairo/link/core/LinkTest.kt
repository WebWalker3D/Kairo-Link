package icu.kairo.link.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

private val CODE = "7K2Q-M9XD-4TPA-0R3V"

/** Starts TCP servers on loopback and hands out a client/relay mux pair joined by a socket. */
class Harness : Closeable {
    private val closeables = mutableListOf<Closeable>()
    fun <T : Closeable> track(c: T) = c.also { closeables += it }

    fun echoServer(): Int {
        val ss = track(ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
        thread(isDaemon = true) {
            while (true) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) {
                    s.use {
                        val buf = ByteArray(8192)
                        val input = it.getInputStream(); val out = it.getOutputStream()
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        it.shutdownOutput()
                    }
                }
            }
        }
        return ss.localPort
    }

    /** Server that blasts [size] bytes at every client and then waits. */
    fun firehose(size: Int): Int {
        val ss = track(ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
        thread(isDaemon = true) {
            while (true) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                track(s)
                thread(isDaemon = true) { try { s.getOutputStream().write(ByteArray(size)) } catch (_: IOException) {} }
            }
        }
        return ss.localPort
    }

    fun linkPair(
        relayCode: String = CODE,
        bodyCode: String = CODE,
        allow: Allowlist = Allowlist.KAIRO,
        allowRules: List<String> = emptyList(),
    ): Pair<Mux, Mux> {
        val ss = track(ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        val relayMux = AtomicReference<Any>()
        val accepted = CountDownLatch(1)
        thread(isDaemon = true) {
            val s = track(ss.accept())
            try {
                relayMux.set(
                    RelaySession(PairingCode.secret(relayCode), allow + allowRules)
                        .accept(s.getInputStream(), s.getOutputStream(), s),
                )
            } catch (e: Exception) {
                relayMux.set(e)
            }
            accepted.countDown()
        }
        val c = track(Socket())
        c.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), ss.localPort))
        val body = try {
            LinkClient.connect(c.getInputStream(), c.getOutputStream(), c, PairingCode.secret(bodyCode))
        } catch (e: Exception) {
            accepted.await(5, TimeUnit.SECONDS)
            throw e
        }
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        val relay = relayMux.get()
        if (relay is Exception) throw relay
        track(body); track(relay as Mux)
        return body to relay
    }

    override fun close() { closeables.reversed().forEach { try { it.close() } catch (_: Exception) {} } }
}

/** An endpoint backed by in-memory pipes so the test plays the "app" side of a stream. */
class TestEndpoint : Endpoint {
    private val toLink = ByteQueue(1 shl 20)
    private val fromLink = ByteQueue(1 shl 20)
    override val input = toLink.input
    override val output = fromLink.output
    override fun shutdownOutput() = fromLink.output.close()
    override fun close() { toLink.output.close(); fromLink.output.close() }

    fun send(b: ByteArray) { toLink.output.write(b); toLink.output.flush() }
    fun finishSending() = toLink.output.close()
    fun readExactly(n: Int): ByteArray = ByteArray(n).also { DataInputStream(fromLink.input).readFully(it) }
    fun readToEnd(): ByteArray = fromLink.input.readBytes()
}

class LinkTest {
    private val h = Harness()

    @After fun tearDown() = h.close()

    @Test(timeout = 20_000)
    fun echoRoundTripThroughRelay() {
        val port = h.echoServer()
        val (body, _) = h.linkPair(allowRules = listOf("localhost:$port"))
        val ep = TestEndpoint()
        body.open("localhost", port, ep)
        val payload = Random(1).nextBytes(300_000)
        thread(isDaemon = true) { ep.send(payload); ep.finishSending() }
        assertArrayEquals(payload, ep.readToEnd())
    }

    @Test(timeout = 20_000)
    fun manyConcurrentStreams() {
        val port = h.echoServer()
        val (body, _) = h.linkPair(allowRules = listOf("localhost:$port"))
        val errors = AtomicReference<Throwable>()
        val threads = (1..12).map { i ->
            thread {
                try {
                    val ep = TestEndpoint()
                    body.open("localhost", port, ep)
                    val payload = Random(i).nextBytes(20_000 * i)
                    thread(isDaemon = true) { ep.send(payload); ep.finishSending() }
                    assertArrayEquals(payload, ep.readToEnd())
                } catch (t: Throwable) { errors.set(t) }
            }
        }
        threads.forEach { it.join() }
        errors.get()?.let { throw it }
    }

    @Test(timeout = 20_000)
    fun stalledStreamDoesNotBlockOthers() {
        val hose = h.firehose(2_000_000)
        val echo = h.echoServer()
        val (body, _) = h.linkPair(allowRules = listOf("localhost:$hose", "localhost:$echo"))
        // Nobody ever reads this stream; the firehose would wedge a mux without per-stream credit.
        body.open("localhost", hose, TestEndpoint())
        Thread.sleep(300)
        val ep = TestEndpoint()
        body.open("localhost", echo, ep)
        ep.send("still alive".toByteArray())
        assertEquals("still alive", String(ep.readExactly(11)))
    }

    @Test(timeout = 20_000)
    fun halfCloseKeepsResponseFlowing() {
        val (body, _) = h.linkPair()
        val ep = TestEndpoint()
        body.open("sink-999999999.kairo-link.diag", 1, ep)
        ep.send(ByteArray(123_456))
        ep.finishSending()
        val count = DataInputStream(ByteArrayInputStream(ep.readToEnd())).readLong()
        assertEquals(123_456L, count)
    }

    @Test(timeout = 20_000)
    fun sinkAnswersAtExpectedSizeWithoutHalfClose() {
        val (body, _) = h.linkPair()
        val ep = TestEndpoint()
        body.open("sink-5000.kairo-link.diag", 1, ep)
        ep.send(ByteArray(5000))
        assertEquals(5000L, DataInputStream(ByteArrayInputStream(ep.readExactly(8))).readLong())
    }

    @Test(timeout = 30_000)
    fun selfTestRunsThroughProxy() {
        val (body, _) = h.linkPair()
        val proxy = h.track(ConnectProxy(0, Allowlist.KAIRO + Diagnostics.ALLOW) { _, _ -> ConnectProxy.Route.Tunnel(body) }.start())
        val report = SelfTest.run(proxy.boundPort, https = null, rounds = 5)
        assertTrue(report, report.contains("round trip") && report.contains("200 KB") && !report.contains("FAILED"))
    }

    @Test(timeout = 20_000)
    fun diagnosticSourceDeliversExactCount() {
        val (body, _) = h.linkPair()
        val ep = TestEndpoint()
        body.open("source-500000.kairo-link.diag", 1, ep)
        ep.finishSending()
        assertEquals(500_000, ep.readToEnd().size)
    }

    @Test(timeout = 20_000)
    fun relayRefusesDestinationsOutsideAllowlist() {
        val port = h.echoServer()
        val (body, relay) = h.linkPair()
        try {
            body.open("localhost", port, TestEndpoint())
            fail("expected refusal")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("not allowed"))
        }
        assertTrue(body.isOpen && relay.isOpen)
    }

    @Test(timeout = 20_000)
    fun refusedOpenLeavesCallerEndpointOpen() {
        val (body, _) = h.linkPair()
        val ep = TestEndpoint()
        try { body.open("nope.example.com", 443, ep); fail() } catch (_: IOException) {}
        ep.send(byteArrayOf(1)) // would throw if the mux had closed it
    }

    @Test(timeout = 20_000)
    fun wrongPairingCodeIsRejectedBothWays() {
        try {
            h.linkPair(relayCode = CODE, bodyCode = "0000-0000-0000-0000")
            fail("expected auth failure")
        } catch (e: AuthException) {
            assertTrue(e.message!!.contains("rejected"))
        }
    }

    @Test(timeout = 20_000)
    fun linkLossTearsDownStreams() {
        val port = h.echoServer()
        val (body, relay) = h.linkPair(allowRules = listOf("localhost:$port"))
        val ep = TestEndpoint()
        body.open("localhost", port, ep)
        relay.close()
        assertTrue(body.awaitClosed(5_000))
        assertFalse(body.isOpen)
        assertEquals(0, ep.readToEnd().size)
        try { body.open("localhost", port, TestEndpoint()); fail() } catch (_: IOException) {}
    }

    @Test(timeout = 20_000)
    fun idleLinkIsSilent() {
        val port = h.echoServer()
        val (body, _) = h.linkPair(allowRules = listOf("localhost:$port"))
        val ep = TestEndpoint()
        body.open("localhost", port, ep)
        ep.send(ByteArray(10_000)); ep.readExactly(10_000)
        Thread.sleep(300)
        val sent = body.bytesSent.get(); val received = body.bytesReceived.get()
        Thread.sleep(1_500)
        assertEquals("idle link must not send", sent, body.bytesSent.get())
        assertEquals("idle link must not receive", received, body.bytesReceived.get())
    }
}

class ProxyTest {
    private val h = Harness()
    @After fun tearDown() = h.close()

    private fun connect(proxyPort: Int, target: String): Pair<Socket, String> {
        val s = h.track(Socket(InetAddress.getLoopbackAddress(), proxyPort))
        s.getOutputStream().write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray())
        val head = ConnectProxy.readHead(s.getInputStream())!!
        return s to head.first()
    }

    @Test(timeout = 20_000)
    fun tunnelsThroughRelayEndToEnd() {
        val port = h.echoServer()
        val (body, _) = h.linkPair(allowRules = listOf("localhost:$port"))
        val proxy = h.track(ConnectProxy(0, Allowlist(listOf("localhost:$port"))) { _, _ -> ConnectProxy.Route.Tunnel(body) }.start())
        val (s, status) = connect(proxy.boundPort, "localhost:$port")
        assertEquals("HTTP/1.1 200 Connection established", status)
        s.getOutputStream().write("hello kairo".toByteArray())
        val back = ByteArray(11); DataInputStream(s.getInputStream()).readFully(back)
        assertEquals("hello kairo", String(back))
    }

    @Test(timeout = 20_000)
    fun directRouteSplices() {
        val port = h.echoServer()
        val proxy = h.track(ConnectProxy(0, Allowlist(listOf("localhost:$port"))) { _, _ -> ConnectProxy.Route.Direct(Dialer.DEFAULT) }.start())
        val (s, status) = connect(proxy.boundPort, "localhost:$port")
        assertEquals("HTTP/1.1 200 Connection established", status)
        s.getOutputStream().write("direct".toByteArray())
        val back = ByteArray(6); DataInputStream(s.getInputStream()).readFully(back)
        assertEquals("direct", String(back))
    }

    @Test(timeout = 20_000)
    fun refusesWhatItShould() {
        val proxy = h.track(ConnectProxy(0, Allowlist.KAIRO) { _, _ -> ConnectProxy.Route.Refuse(503, "relay not connected") }.start())
        assertEquals("HTTP/1.1 403 Destination not allowed", connect(proxy.boundPort, "evil.example.com:443").second)
        assertEquals("HTTP/1.1 403 Destination not allowed", connect(proxy.boundPort, "connect.kairo.icu:80").second)
        assertEquals("HTTP/1.1 503 relay not connected", connect(proxy.boundPort, "connect.kairo.icu:443").second)

        val s = h.track(Socket(InetAddress.getLoopbackAddress(), proxy.boundPort))
        s.getOutputStream().write("GET http://connect.kairo.icu/ HTTP/1.1\r\n\r\n".toByteArray())
        assertEquals("HTTP/1.1 405 Only CONNECT is supported", ConnectProxy.readHead(s.getInputStream())!!.first())
    }

    @Test(timeout = 20_000)
    fun relayFailureBecomes502() {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).run { localPort.also { close() } }
        val (body, _) = h.linkPair(allowRules = listOf("localhost:$dead"))
        val proxy = h.track(ConnectProxy(0, Allowlist(listOf("localhost:$dead"))) { _, _ -> ConnectProxy.Route.Tunnel(body) }.start())
        // Allowed on both sides, but nothing listens there: the relay's connect fails.
        val status = connect(proxy.boundPort, "localhost:$dead").second
        assertTrue(status, status.startsWith("HTTP/1.1 502 Kairo-Link relay:"))
    }
}

class SecurityTest {
    @Test fun pairingCodesAreWellFormedAndDistinct() {
        val a = PairingCode.generate(); val b = PairingCode.generate()
        assertTrue(a, Regex("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}").matches(a))
        assertFalse(a == b)
        assertNotNull(PairingCode.normalize(a))
    }

    @Test fun normalizationForgivesTypingMistakes() {
        assertEquals(PairingCode.normalize("7K2Q-M9XD-4TPA-0R3V"), PairingCode.normalize(" 7k2q m9xd 4tpa or3v "))
        assertArrayEquals(PairingCode.secret("7K2QM9XD4TPA0R3V"), PairingCode.secret("7k2q-m9xd-4tpa-0r3v"))
        assertEquals(PairingCode.normalize("1111-1111-1111-1111"), PairingCode.normalize("IlLi-1111-1111-1111"))
        assertEquals(null, PairingCode.normalize("7K2Q-M9XD-4TPA"))
        assertEquals(null, PairingCode.normalize("7K2Q-M9XD-4TPA-0R3U"))
    }

    @Test fun allowlistMatching() {
        val a = Allowlist.KAIRO
        assertTrue(a.permits("connect.kairo.icu", 443))
        assertTrue(a.permits("CONNECT.Kairo.ICU.", 443))
        assertTrue(a.permits("kairo.icu", 443))
        assertFalse(a.permits("connect.kairo.icu", 80))
        assertFalse(a.permits("evilkairo.icu", 443))
        assertFalse(a.permits("kairo.icu.evil.com", 443))
        assertFalse(a.permits("connect.kairo.icu/x", 443))
        assertFalse(a.permits("", 443))
    }

    @Test fun handshakeRejectsGarbage() {
        val out = ByteArrayOutputStream()
        val ok = Handshake.relay(ByteArrayInputStream(ByteArray(53) { 7 }), out, PairingCode.secret(CODE))
        assertFalse(ok)
        try {
            Handshake.client(ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".toByteArray()), ByteArrayOutputStream(), PairingCode.secret(CODE))
            fail()
        } catch (e: AuthException) {
            assertTrue(e.message!!.contains("not a Kairo-Link relay"))
        }
    }

    @Test fun framesRoundTripAndRejectOversize() {
        val buf = ByteArrayOutputStream()
        FrameWriter(buf).write(FrameType.DATA, 513, byteArrayOf(1, 2, 3))
        val f = FrameReader(ByteArrayInputStream(buf.toByteArray())).read()!!
        assertEquals(FrameType.DATA, f.type); assertEquals(513, f.sid); assertArrayEquals(byteArrayOf(1, 2, 3), f.payload)
        val bad = byteArrayOf(3, 0, 1, 0xFF.toByte(), 0xFF.toByte())
        try { FrameReader(ByteArrayInputStream(bad)).read(); fail() } catch (_: java.net.ProtocolException) {}
    }
}
