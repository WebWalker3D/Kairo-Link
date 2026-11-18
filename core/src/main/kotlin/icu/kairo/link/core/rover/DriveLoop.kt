package icu.kairo.link.core.rover

sealed interface RoverWrite {
    class Drive(val payload: ByteArray) : RoverWrite
    object Stop : RoverWrite
}

/**
 * Decides what the phone sends each 100 ms tick. Drives only while a control is
 * held on a visible screen; release or pause sends exactly one STOP, then nothing.
 * After pause, holds are ignored until resume (a finger left on the glass when a
 * call slides in must not keep the car moving). The rover's own deadman covers
 * anything this misses.
 */
class DriveLoop(private val sessionKey: ByteArray, startSeq: Long = 1) {
    private var seq = startSeq
    private var held: Pair<Int, Int>? = null
    private var stopPending = false
    private var paused = false

    fun hold(left: Int, right: Int) {
        require(left in -100..100 && right in -100..100)
        if (paused) return
        held = left to right
        stopPending = false
    }

    fun release() {
        if (held != null) stopPending = true
        held = null
    }

    fun pause() {
        if (held != null) stopPending = true
        held = null
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun tick(): RoverWrite? {
        if (stopPending) {
            stopPending = false
            return RoverWrite.Stop
        }
        val (l, r) = held ?: return null
        return RoverWrite.Drive(RoverProtocol.drive(sessionKey, seq++, l, r))
    }
}
