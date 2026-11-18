package icu.kairo.link.core.rover

import java.util.UUID

/** GATT identifiers (novexai protocol §2): 7c5e000N-4b52-4f56-9a3e-6b6169726f76. */
object RoverUuids {
    private fun u(n: Int) = UUID.fromString("7c5e%04x-4b52-4f56-9a3e-6b6169726f76".format(n))
    val SERVICE: UUID = u(1)
    val AUTH: UUID = u(2)
    val DRIVE: UUID = u(3)
    val STOP: UUID = u(4)
    val STATUS: UUID = u(5)
    val LIVEVIEW: UUID = u(6)
}

enum class StopReason { NONE, BOOT, DEADMAN, EXPLICIT, DISCONNECT, OTA, FAULT, CONTROLLER_CHANGE, NOT_COMMISSIONED, UNKNOWN }

data class RoverStatus(
    val authenticated: Boolean,
    val motorsCommissioned: Boolean,
    val wifi: Boolean,
    val serverSession: Boolean,
    val driving: Boolean,
    val stopReason: StopReason,
    val speedCap: Int,
    val deadmanTrips: Int,
    val authFailures: Int,
    val ipv4: String,
    val fwBuild: Long,
) {
    companion object {
        /** The rover's 16-byte STATUS value; null unless it is exactly that, version 1. */
        fun parse(bytes: ByteArray): RoverStatus? {
            if (bytes.size != 16 || bytes[0].toInt() != 1) return null
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            val flags = u8(1)
            val reason = StopReason.entries.getOrNull(u8(2))?.takeIf { it != StopReason.UNKNOWN } ?: StopReason.UNKNOWN
            return RoverStatus(
                authenticated = flags and 0x01 != 0,
                motorsCommissioned = flags and 0x02 != 0,
                wifi = flags and 0x04 != 0,
                serverSession = flags and 0x08 != 0,
                driving = flags and 0x10 != 0,
                stopReason = reason,
                speedCap = u8(3),
                deadmanTrips = u8(4) or (u8(5) shl 8),
                authFailures = u8(6) or (u8(7) shl 8),
                ipv4 = "${u8(8)}.${u8(9)}.${u8(10)}.${u8(11)}",
                fwBuild = (u8(12) or (u8(13) shl 8) or (u8(14) shl 16)).toLong() or (u8(15).toLong() shl 24),
            )
        }
    }
}

object RoverProtocol {
    /** 14-byte DRIVE: seq LE, left, right, HMAC(sessionKey, "drive"|those 6 bytes)[0:8]. */
    fun drive(sessionKey: ByteArray, seq: Long, left: Int, right: Int): ByteArray {
        require(left in -100..100 && right in -100..100) { "drive values must be -100..100" }
        require(seq in 1..0xFFFF_FFFFL) { "sequence out of range" }
        val head = byteArrayOf(
            seq.toByte(), (seq shr 8).toByte(), (seq shr 16).toByte(), (seq shr 24).toByte(),
            left.toByte(), right.toByte(),
        )
        val tag = RoverAuth.hmac(sessionKey, "drive", head).copyOf(8)
        return head + tag
    }

    fun liveViewUrl(status: RoverStatus, token: ByteArray): String? {
        if (token.size != 16 || status.ipv4 == "0.0.0.0") return null
        return "http://${status.ipv4}:81/stream?t=" + token.joinToString("") { "%02x".format(it) }
    }
}
