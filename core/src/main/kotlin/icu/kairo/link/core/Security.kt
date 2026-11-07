package icu.kairo.link.core

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AuthException(message: String) : IOException(message)

/**
 * A 16-character Crockford base32 code (80 random bits) shown on the relay and
 * typed into the body, e.g. `7K2Q-M9XD-4TPA-0R3V`. Both phones derive the same
 * link secret from it; the code itself never crosses the radio.
 */
object PairingCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 16

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(10).also(random::nextBytes)
        val out = StringBuilder(LENGTH)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        return format(out.toString())
    }

    /** Uppercases, drops separators and maps look-alikes (O->0, I/L->1); null if invalid. */
    fun normalize(code: String): String? {
        val s = buildString {
            for (c in code.uppercase()) {
                when {
                    c == '-' || c.isWhitespace() -> {}
                    c == 'O' -> append('0')
                    c == 'I' || c == 'L' -> append('1')
                    else -> append(c)
                }
            }
        }
        return if (s.length == LENGTH && s.all { it in ALPHABET }) s else null
    }

    fun format(normalized: String) = normalized.chunked(4).joinToString("-")

    fun secret(code: String): ByteArray {
        val n = normalize(code) ?: throw IllegalArgumentException("invalid pairing code")
        return MessageDigest.getInstance("SHA-256").digest("kairo-link/1:$n".toByteArray(Charsets.US_ASCII))
    }
}

/**
 * Mutual challenge-response over the fresh channel, before any stream opens.
 *
 *     relay  -> client : "KLNK" v nonceR[16]
 *     client -> relay  : "KLNK" v nonceC[16] HMAC(secret, "client"|nonceR|nonceC)
 *     relay  -> client : 0x00 HMAC(secret, "relay"|nonceC|nonceR)   (or 0x01 = rejected)
 *
 * Kairo traffic is already TLS end to end; this only keeps strangers from using
 * the relay and keeps the body from talking to an impostor relay.
 */
object Handshake {
    const val VERSION = 1
    private val MAGIC = "KLNK".toByteArray(Charsets.US_ASCII)
    private const val NONCE = 16
    private const val MAC = 32
    private val CLIENT_LABEL = "kairo-link/1 client".toByteArray(Charsets.US_ASCII)
    private val RELAY_LABEL = "kairo-link/1 relay".toByteArray(Charsets.US_ASCII)

    /** Returns true when the peer proved the shared secret. */
    fun relay(input: InputStream, output: OutputStream, secret: ByteArray, random: SecureRandom = SecureRandom()): Boolean {
        val din = DataInputStream(input)
        val nonceR = ByteArray(NONCE).also(random::nextBytes)
        output.write(MAGIC); output.write(VERSION); output.write(nonceR); output.flush()

        val hello = ByteArray(MAGIC.size + 1 + NONCE + MAC)
        din.readFully(hello)
        val nonceC = hello.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + NONCE)
        val mac = hello.copyOfRange(MAGIC.size + 1 + NONCE, hello.size)
        val ok = hello.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) &&
            hello[MAGIC.size].toInt() == VERSION &&
            MessageDigest.isEqual(mac, hmac(secret, CLIENT_LABEL, nonceR, nonceC))
        if (!ok) {
            output.write(1); output.flush()
            return false
        }
        output.write(0); output.write(hmac(secret, RELAY_LABEL, nonceC, nonceR)); output.flush()
        return true
    }

    /** Throws [AuthException] unless both sides proved the shared secret. */
    fun client(input: InputStream, output: OutputStream, secret: ByteArray, random: SecureRandom = SecureRandom()) {
        val din = DataInputStream(input)
        val head = ByteArray(MAGIC.size + 1 + NONCE)
        din.readFully(head, 0, MAGIC.size)
        if (!head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw AuthException("peer is not a Kairo-Link relay")
        din.readFully(head, MAGIC.size, head.size - MAGIC.size)
        if (head[MAGIC.size].toInt() != VERSION) throw AuthException("relay speaks protocol v${head[MAGIC.size]}, expected v$VERSION")
        val nonceR = head.copyOfRange(MAGIC.size + 1, head.size)
        val nonceC = ByteArray(NONCE).also(random::nextBytes)
        output.write(MAGIC); output.write(VERSION); output.write(nonceC)
        output.write(hmac(secret, CLIENT_LABEL, nonceR, nonceC)); output.flush()

        val status = din.read()
        if (status != 0) throw AuthException("relay rejected the pairing code")
        val proof = ByteArray(MAC)
        din.readFully(proof)
        if (!MessageDigest.isEqual(proof, hmac(secret, RELAY_LABEL, nonceC, nonceR))) {
            throw AuthException("relay could not prove the pairing code")
        }
    }

    private fun hmac(secret: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }
}

/** Destinations the link will carry. Exact `host:port` or `*.suffix:port`. */
class Allowlist(rules: Collection<String>) {
    private class Rule(val host: String, val wildcard: Boolean, val port: Int)

    private val rules = rules.map { rule ->
        val idx = rule.lastIndexOf(':')
        require(idx > 0) { "rule needs host:port: $rule" }
        val port = rule.substring(idx + 1).toInt()
        val raw = rule.substring(0, idx)
        val wildcard = raw.startsWith("*.")
        val host = normalizeHost(if (wildcard) raw.substring(2) else raw)
            ?: throw IllegalArgumentException("bad host in rule: $rule")
        Rule(host, wildcard, port)
    }

    fun permits(host: String, port: Int): Boolean {
        val h = normalizeHost(host) ?: return false
        return rules.any { it.port == port && (if (it.wildcard) h.endsWith("." + it.host) else h == it.host) }
    }

    operator fun plus(more: Collection<String>) = Allowlist(rawRules + more)

    private val rawRules = rules.toList()

    companion object {
        /** Everything the Kairo body talks to. */
        val KAIRO = Allowlist(listOf("kairo.icu:443", "*.kairo.icu:443"))

        fun normalizeHost(host: String): String? {
            val h = host.trim().lowercase().removeSuffix(".")
            if (h.isEmpty() || h.length > 253) return null
            val ok = h.split('.').all { label ->
                label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }
            return if (ok) h else null
        }
    }
}
