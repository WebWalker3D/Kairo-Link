package icu.kairo.link.core.rover

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors: novexai docs/superpowers/specs/2026-11-18-kairo-rover-protocol.md §5. */
class RoverAuthTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val code = "7K2M-9QXR-4TVB-H8ND"
    private val secret = hex("bdf0176336d94df0c61bb3e20e3cf243bd3ee9a968a9363f4f662f2df42df2b8")
    private val nonceR = hex("000102030405060708090a0b0c0d0e0f")
    private val nonceC = hex("101112131415161718191a1b1c1d1e1f")
    private val sessionKey = hex("bc4a932afe0adb1f1348c679c1c4b682e04ae314d9c6865f01f55cf8e34388a0")
    private val roverChallenge = "KRVR".toByteArray() + byteArrayOf(1) + nonceR
    private val roverProof = byteArrayOf(0) + hex("a98d1206768de13bc17ff9a1e193a918ccfdecd7c3518ee975ae54395d357d3f")

    @Test fun secret_matches_vector() {
        assertArrayEquals(secret, RoverAuth.secret(code))
        assertArrayEquals(secret, RoverAuth.secret("7k2m 9qxr 4tvb h8nd"))
    }

    @Test fun client_hello_matches_vector() {
        val hello = RoverAuth.clientHello(secret, roverChallenge, nonceC)
        assertEquals(53, hello.size)
        assertEquals("KRVR", String(hello.copyOfRange(0, 4)))
        assertEquals(1, hello[4].toInt())
        assertArrayEquals(nonceC, hello.copyOfRange(5, 21))
        assertEquals("17809f5c75b32e108fb9dafec505689eddbaa4a8b1163f19b39c6d1aa59f96b9", hello.copyOfRange(21, 53).hex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun client_hello_rejects_non_rover_challenge() {
        RoverAuth.clientHello(secret, "KLNK".toByteArray() + byteArrayOf(1) + nonceR, nonceC)
    }

    @Test fun rover_proof_accepted() {
        assertTrue(RoverAuth.verifyRoverProof(secret, roverProof, nonceC, nonceR))
    }

    @Test fun client_rejects_bad_rover_proof() {
        val forged = roverProof.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(RoverAuth.verifyRoverProof(secret, forged, nonceC, nonceR))
        assertFalse(RoverAuth.verifyRoverProof(secret, byteArrayOf(1), nonceC, nonceR))
    }

    @Test fun lockout_reply_is_not_a_rejection() {
        assertTrue(RoverAuth.isLockedOut(byteArrayOf(2)))
        assertFalse(RoverAuth.isLockedOut(byteArrayOf(1)))
        assertFalse(RoverAuth.isLockedOut(roverProof))
        assertFalse(RoverAuth.verifyRoverProof(secret, byteArrayOf(2), nonceC, nonceR))
    }

    @Test fun session_key_matches_vector() {
        assertArrayEquals(sessionKey, RoverAuth.sessionKey(secret, nonceR, nonceC))
    }
}
