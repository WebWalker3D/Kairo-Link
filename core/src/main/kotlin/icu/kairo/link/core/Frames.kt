package icu.kairo.link.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException

/**
 * Wire format of the multiplexed link (after the handshake):
 *
 *     type:u8  stream:u16  length:u16  payload[length]
 *
 * Every frame is flushed as one write so the Bluetooth stack can send it as one
 * L2CAP SDU. Payloads are capped so a large upload never holds the link for long.
 */
object FrameType {
    const val OPEN = 1    // client -> relay: port:u16, host:utf8
    const val OPENED = 2  // relay -> client: destination connected
    const val DATA = 3    // payload bytes for a stream
    const val CREDIT = 4  // grant:u32 - receiver consumed that many bytes
    const val EOF = 5     // sender will send no more data on this stream (half close)
    const val RESET = 6   // stream aborted; payload is a short utf8 reason
}

const val HEADER_SIZE = 5
const val MAX_PAYLOAD = 4096

class Frame(val type: Int, val sid: Int, val payload: ByteArray)

class FrameWriter(output: OutputStream) {
    private val out = DataOutputStream(BufferedOutputStream(output, HEADER_SIZE + MAX_PAYLOAD))

    @Synchronized
    fun write(type: Int, sid: Int, payload: ByteArray, length: Int = payload.size) {
        require(length in 0..MAX_PAYLOAD) { "payload too large: $length" }
        require(sid in 0..0xFFFF) { "bad stream id: $sid" }
        out.writeByte(type)
        out.writeShort(sid)
        out.writeShort(length)
        out.write(payload, 0, length)
        out.flush()
    }
}

class FrameReader(input: InputStream) {
    private val din = DataInputStream(BufferedInputStream(input, HEADER_SIZE + MAX_PAYLOAD))

    /** Returns the next frame, or null on a clean end of stream between frames. */
    fun read(): Frame? {
        val type = din.read()
        if (type < 0) return null
        val sid = din.readUnsignedShort()
        val length = din.readUnsignedShort()
        if (length > MAX_PAYLOAD) throw ProtocolException("frame payload $length exceeds $MAX_PAYLOAD")
        val payload = ByteArray(length)
        din.readFully(payload)
        return Frame(type, sid, payload)
    }
}

internal fun intBytes(value: Int) = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

internal fun bytesInt(b: ByteArray): Int {
    if (b.size != 4) throw ProtocolException("expected 4-byte integer")
    return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
        ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}

internal fun encodeOpen(host: String, port: Int): ByteArray {
    val h = host.toByteArray(Charsets.UTF_8)
    return byteArrayOf((port ushr 8).toByte(), port.toByte()) + h
}

internal fun decodeOpen(b: ByteArray): Pair<String, Int> {
    if (b.size < 3) throw ProtocolException("short OPEN frame")
    val port = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    return String(b, 2, b.size - 2, Charsets.UTF_8) to port
}
