package icu.kairo.link.core.rover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveLoopTest {
    private val sk = ByteArray(32) { it.toByte() }

    private fun seqOf(w: RoverWrite?): Long {
        val p = (w as RoverWrite.Drive).payload
        return (p[0].toLong() and 0xFF) or ((p[1].toLong() and 0xFF) shl 8) or
            ((p[2].toLong() and 0xFF) shl 16) or ((p[3].toLong() and 0xFF) shl 24)
    }

    @Test fun held_sends_drive_each_tick_with_increasing_seq() {
        val loop = DriveLoop(sk)
        loop.hold(50, 50)
        val a = loop.tick(); val b = loop.tick(); val c = loop.tick()
        assertTrue(seqOf(a) < seqOf(b) && seqOf(b) < seqOf(c))
        assertEquals(50, (c as RoverWrite.Drive).payload[4].toInt())
    }

    @Test fun release_sends_single_stop_then_silence() {
        val loop = DriveLoop(sk)
        loop.hold(30, -30); loop.tick()
        loop.release()
        assertSame(RoverWrite.Stop, loop.tick())
        assertNull(loop.tick())
        assertNull(loop.tick())
    }

    @Test fun pause_sends_stop_and_halts() {
        val loop = DriveLoop(sk)
        loop.hold(40, 40); loop.tick()
        loop.pause()
        assertSame(RoverWrite.Stop, loop.tick())
        loop.hold(40, 40) // finger still down after a call notification
        assertNull(loop.tick())
        loop.resume(); loop.hold(40, 40)
        assertTrue(loop.tick() is RoverWrite.Drive)
    }

    @Test fun zero_speed_holds_without_motion() {
        val loop = DriveLoop(sk)
        loop.hold(0, 0)
        val w = loop.tick()
        assertTrue(w is RoverWrite.Drive)
        val p = (w as RoverWrite.Drive).payload
        assertEquals(0, p[4].toInt()); assertEquals(0, p[5].toInt())
    }

    @Test fun nothing_sent_before_first_hold() {
        assertNull(DriveLoop(sk).tick())
    }
}
