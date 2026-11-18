package icu.kairo.link

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import icu.kairo.link.core.Mux
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

const val TAG = "KairoLink"

object LinkIds {
    /**
     * The relay advertises service data under this UUID: its L2CAP PSM (u16 little-endian) and the
     * protocol version (u8). The body's scan filters on it in the Bluetooth controller.
     */
    val SERVICE: UUID = UUID.fromString("4b4c0001-9a1e-4f6b-8c2a-6b6169726f31")
    const val PROXY_PORT = 8119
    /** Loopback diagnostic proxy: always tunnels, also reaches the `*.kairo-link.diag` test targets. */
    const val DIAG_PORT = 8120
}

enum class Role { NONE, RELAY, BODY }

/** How the body proxy routes Kairo traffic. */
enum class RouteMode { AUTO, LINK_ONLY, DIRECT_ONLY }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kairo_link", Context.MODE_PRIVATE)

    var role: Role
        get() = runCatching { Role.valueOf(sp.getString("role", null) ?: "NONE") }.getOrDefault(Role.NONE)
        set(v) = sp.edit().putString("role", v.name).apply()
    var code: String?
        get() = sp.getString("code", null)
        set(v) = sp.edit().putString("code", v).apply()
    var mode: RouteMode
        get() = runCatching { RouteMode.valueOf(sp.getString("mode", null) ?: "AUTO") }.getOrDefault(RouteMode.AUTO)
        set(v) = sp.edit().putString("mode", v.name).apply()
    /** Debug experiment knob: "dynamic" (default), or a fixed "low", "balanced" or "high" interval. */
    var interval: String
        get() = sp.getString("interval", null) ?: "dynamic"
        set(v) = sp.edit().putString("interval", v).apply()
    /** The rover's own pairing code (separate from the Link code). */
    var roverCode: String?
        get() = sp.getString("rover_code", null)
        set(v) = sp.edit().putString("rover_code", v).apply()
    /** True once the user started the link; it then comes back after reboots and updates. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()
}

/** Process-wide status for the UI and notification. Updated rarely: on state changes only. */
object LinkState {
    data class Snapshot(
        val phase: String = "Stopped",
        val detail: String = "",
        val peer: String? = null,
        val rssi: Int? = null,
        val phy: String? = null,
        val power: String? = null,
        val since: Long = SystemClock.elapsedRealtime(),
    )

    @Volatile var current = Snapshot()
        private set
    @Volatile var mux: Mux? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun update(f: (Snapshot) -> Snapshot) {
        synchronized(this) {
            val before = current
            val after = f(before)
            current = if (after.phase != before.phase) after.copy(since = SystemClock.elapsedRealtime()) else after
            if (after.phase != before.phase || after.detail != before.detail) Log.i(TAG, "${after.phase} — ${after.detail}")
        }
        listeners.forEach { it() }
    }

    fun phase(phase: String, detail: String = "") = update { it.copy(phase = phase, detail = detail) }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
}

/**
 * Keeps the CPU awake for a few seconds after link traffic so a frame that just arrived over
 * the radio is forwarded before the phone dozes off. Throttled to one binder call per second.
 */
class ActivityWakeLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kairo-link:traffic").apply { setReferenceCounted(false) }
    @Volatile private var last = 0L

    fun touch() {
        val now = SystemClock.elapsedRealtime()
        if (now - last < 1_000) return
        last = now
        lock.acquire(3_000)
    }

    fun release() { if (lock.isHeld) lock.release() }
}
