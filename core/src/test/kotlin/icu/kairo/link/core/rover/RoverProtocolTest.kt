package icu.kairo.link.core.rover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoverProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private val sessionKey = hex("bc4a932afe0adb1f1348c679c1c4b682e04ae314d9c6865f01f55cf8e34388a0")

    @Test fun drive_matches_vector() {
        assertEquals("07000000d837c67a9269c2b3f116", RoverProtocol.drive(sessionKey, 7, -40, 55).hex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun drive_rejects_out_of_range() {
        RoverProtocol.drive(sessionKey, 1, 101, 0)
    }

    private val statusBytes = byteArrayOf(1, 0x13, 2, 60, 0x02, 0x01, 0x04, 0x03, 10, 0, 0, 50, 0x0D, 0x0C, 0x0B, 0x0A)

    @Test fun status_parses_firmware_layout() {
        val s = RoverStatus.parse(statusBytes)!!
        assertTrue(s.authenticated && s.motorsCommissioned && s.driving)
        assertTrue(!s.wifi && !s.serverSession)
        assertEquals(StopReason.DEADMAN, s.stopReason)
        assertEquals(60, s.speedCap)
        assertEquals(0x0102, s.deadmanTrips)
        assertEquals(0x0304, s.authFailures)
        assertEquals("10.0.0.50", s.ipv4)
        assertEquals(0x0A0B0C0DL, s.fwBuild)
    }

    @Test fun status_parse_rejects_wrong_length_and_version() {
        assertNull(RoverStatus.parse(statusBytes.copyOf(15)))
        assertNull(RoverStatus.parse(statusBytes.copyOf().also { it[0] = 2 }))
    }

    @Test fun unknown_stop_reason_is_unknown_not_crash() {
        assertEquals(StopReason.UNKNOWN, RoverStatus.parse(statusBytes.copyOf().also { it[2] = 99 })!!.stopReason)
    }

    @Test fun live_view_url() {
        val s = RoverStatus.parse(statusBytes)!!
        assertEquals("http://10.0.0.50:81/stream?t=a56c9d776fff19a34811a0ff0ba7d22b",
            RoverProtocol.liveViewUrl(s, hex("a56c9d776fff19a34811a0ff0ba7d22b")))
        val noWifi = RoverStatus.parse(statusBytes.copyOf().also { for (i in 8..11) it[i] = 0 })!!
        assertNull(RoverProtocol.liveViewUrl(noWifi, hex("a56c9d776fff19a34811a0ff0ba7d22b")))
        assertNull(RoverProtocol.liveViewUrl(s, ByteArray(0)))
    }
}
