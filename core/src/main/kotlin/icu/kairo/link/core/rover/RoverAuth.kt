package icu.kairo.link.core.rover

import icu.kairo.link.core.PairingCode
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Rover pairing handshake (novexai protocol §1–2). The rover's code is its own,
 * separate from the phone-to-phone Link code, but uses the same alphabet.
 *
 *     read AUTH   <- "KRVR" 1 nonceR[16]
 *     write AUTH  -> "KRVR" 1 nonceC[16] HMAC(secret, "kairo-rover/1 client"|nonceR|nonceC)
 *     read AUTH   <- 0x00 HMAC(secret, "kairo-rover/1 rover"|nonceC|nonceR)   (or 0x01)
 */
object RoverAuth {
    private val MAGIC = "KRVR".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1

    fun secret(code: String): ByteArray {
        val n = PairingCode.normalize(code) ?: throw IllegalArgumentException("invalid rover pairing code")
        return MessageDigest.getInstance("SHA-256").digest("kairo-rover/1:$n".toByteArray(Charsets.US_ASCII))
    }

    /** Builds the 53-byte hello from the rover's 21-byte challenge. */
    fun clientHello(secret: ByteArray, roverChallenge: ByteArray, nonceC: ByteArray): ByteArray {
        require(roverChallenge.size == 21 && roverChallenge.copyOfRange(0, 4).contentEquals(MAGIC) &&
            roverChallenge[4] == VERSION) { "peer is not a Kairo rover" }
        require(nonceC.size == 16)
        val nonceR = roverChallenge.copyOfRange(5, 21)
        return MAGIC + byteArrayOf(VERSION) + nonceC + hmac(secret, "kairo-rover/1 client", nonceR, nonceC)
    }

    fun nonceR(roverChallenge: ByteArray): ByteArray = roverChallenge.copyOfRange(5, 21)

    fun verifyRoverProof(secret: ByteArray, reply: ByteArray, nonceC: ByteArray, nonceR: ByteArray): Boolean =
        reply.size == 33 && reply[0].toInt() == 0 &&
            MessageDigest.isEqual(reply.copyOfRange(1, 33), hmac(secret, "kairo-rover/1 rover", nonceC, nonceR))

    /** 0x02: too many recent failures; wait a minute. Not a verdict on this code. */
    fun isLockedOut(reply: ByteArray): Boolean = reply.size == 1 && reply[0].toInt() == 2

    fun sessionKey(secret: ByteArray, nonceR: ByteArray, nonceC: ByteArray): ByteArray =
        hmac(secret, "kairo-rover/1 session", nonceR, nonceC)

    internal fun hmac(key: ByteArray, label: String, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.US_ASCII))
        parts.forEach(mac::update)
        return mac.doFinal()
    }
}
