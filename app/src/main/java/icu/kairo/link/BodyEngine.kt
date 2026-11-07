package icu.kairo.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import icu.kairo.link.core.Allowlist
import icu.kairo.link.core.AuthException
import icu.kairo.link.core.ConnectProxy
import icu.kairo.link.core.ConnectProxy.Route
import icu.kairo.link.core.Diagnostics
import icu.kairo.link.core.Dialer
import icu.kairo.link.core.LinkClient
import icu.kairo.link.core.Mux
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * Runs on Kairo's body (the A16).
 *
 * Finds the relay with a hardware-filtered scan (its advertisement carries the channel number),
 * connects, opens the L2CAP channel and authenticates. The GATT connection is kept only as the
 * handle Android offers for connection interval, PHY and signal strength. While the link is up it sits at the low-power
 * connection interval; any traffic bumps it to the fast interval, and 2.5 s of quiet drops it
 * back. Signal strength is checked every 15 s to step between 2M and long-range (Coded) PHYs.
 *
 * Apps reach Kairo through a loopback CONNECT proxy on [LinkIds.PROXY_PORT].
 */
@SuppressLint("MissingPermission")
class BodyEngine(
    private val context: Context,
    private val secret: ByteArray,
    private val mode: RouteMode,
    private val wake: ActivityWakeLock,
    private val intervalPolicy: String = "dynamic",
) {
    private companion object {
        const val IDLE_MS = 2_500L
        const val RSSI_EVERY_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val FAST_SCAN_MS = 60_000L
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val ht = HandlerThread("kairo-link-body").apply { start() }
    private val h = Handler(ht.looper)

    // All fields below are touched only on [h], except the @Volatile ones.
    @Volatile private var running = false
    @Volatile private var mux: Mux? = null
    @Volatile private var burst = false
    private var wantedPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED
    private var priorityRetries = 0
    @Volatile private var lastActivity = 0L
    private var gatt: BluetoothGatt? = null
    private var channel: BluetoothSocket? = null
    private var generation = 0
    private var scanning = false
    private var fastScan = true
    private var failures = 0
    private var phyTier = 0
    private var pendingPsm = 0

    private val proxy = ConnectProxy(LinkIds.PROXY_PORT, Allowlist.KAIRO, onActivity = wake::touch, router = ::route)
    private val diagProxy = ConnectProxy(LinkIds.DIAG_PORT, Allowlist.KAIRO + Diagnostics.ALLOW) { _, _ ->
        mux?.let { Route.Tunnel(it) } ?: Route.Refuse(503, "Kairo-Link relay not connected")
    }

    fun start() {
        running = true
        proxy.start()
        diagProxy.start()
        h.post { startScan() }
    }

    fun stop() {
        running = false
        proxy.close()
        diagProxy.close()
        h.post {
            stopScan()
            dropLink()
            ht.quitSafely()
        }
        wake.release()
    }

    // ---- routing -------------------------------------------------------------------------------

    private fun route(host: String, port: Int): Route {
        val link = mux?.let { Route.Tunnel(it) }
        return when (mode) {
            RouteMode.LINK_ONLY -> link ?: Route.Refuse(503, "Kairo-Link relay not connected")
            RouteMode.DIRECT_ONLY -> direct() ?: Route.Refuse(503, "No Wi-Fi on this phone")
            RouteMode.AUTO -> direct() ?: link ?: Route.Refuse(503, "No Wi-Fi and Kairo-Link relay not connected")
        }
    }

    /** This phone's own Wi-Fi, when it actually reaches the internet. Cellular is deliberately not used. */
    private fun direct(): Route? {
        val net = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(net) ?: return null
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!wifi || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return null
        return Route.Direct(Dialer { host, port ->
            val address = net.getByName(host)
            net.socketFactory.createSocket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(address, port), 10_000)
            }
        })
    }

    // ---- discovery -----------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(ParcelUuid(LinkIds.SERVICE)) ?: return
            if (data.size < 2) return
            val psm = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            h.post { if (scanning) { stopScan(); connect(result, psm) } }
        }

        override fun onScanFailed(errorCode: Int) {
            h.post {
                scanning = false
                LinkState.phase("Searching for relay", "scan failed ($errorCode); retrying")
                h.postDelayed({ startScan() }, 10_000)
            }
        }
    }

    private val slowScan = Runnable {
        if (scanning) { stopScan(); fastScan = false; startScan() }
    }

    private fun startScan() {
        if (!running || scanning || mux != null || gatt != null) return
        if (!adapter.isEnabled) return LinkState.phase("Bluetooth is off", "turn Bluetooth on to reach the relay")
        val scanner = adapter.bluetoothLeScanner ?: run { h.postDelayed({ startScan() }, 5_000); return }
        val settings = ScanSettings.Builder()
            .setScanMode(if (fastScan) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
            .setLegacy(false)
            .setPhy(if (adapter.isLeCodedPhySupported) ScanSettings.PHY_LE_ALL_SUPPORTED else BluetoothDevice.PHY_LE_1M)
            .build()
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(LinkIds.SERVICE), ByteArray(0)).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        LinkState.update { it.copy(phase = "Searching for relay", detail = if (fastScan) "scanning" else "low-power scanning", rssi = null, phy = null, power = null, peer = null) }
        if (fastScan) h.postDelayed(slowScan, FAST_SCAN_MS)
    }

    private fun stopScan() {
        h.removeCallbacks(slowScan)
        if (!scanning) return
        scanning = false
        try { adapter.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    // ---- connection ----------------------------------------------------------------------------

    private fun connect(result: ScanResult, psm: Int) {
        pendingPsm = psm
        val gen = ++generation
        val coded = result.primaryPhy == BluetoothDevice.PHY_LE_CODED
        LinkState.update { it.copy(phase = "Connecting", detail = "relay found (${result.rssi} dBm${if (coded) ", long-range" else ""})", rssi = result.rssi) }
        val mask = if (coded) BluetoothDevice.PHY_LE_CODED_MASK else BluetoothDevice.PHY_LE_1M_MASK
        gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE, mask, h)
        h.postDelayed({ if (gen == generation && mux == null) lost("connection attempt timed out") }, CONNECT_TIMEOUT_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (channel == null) openChannel(g.device, pendingPsm, generation)
                BluetoothProfile.STATE_DISCONNECTED -> lost("Bluetooth link lost (status $status)")
            }
        }

        /** Hidden framework callback; plain virtual dispatch still reaches it. Logged for tuning. */
        @Suppress("unused")
        fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            Log.d(TAG, "connection interval ${interval * 1.25} ms, latency $latency, status $status")
            if (g === gatt && status != BluetoothGatt.GATT_SUCCESS && priorityRetries++ < 10) h.postDelayed(retryPriority, 400)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (g !== gatt || status != BluetoothGatt.GATT_SUCCESS) return
            Log.d(TAG, "rssi $rssi dBm (phy tier $phyTier)")
            LinkState.update { it.copy(rssi = rssi) }
            adjustPhy(rssi)
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = reportPhy(g, txPhy, status)
        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = reportPhy(g, txPhy, status)
    }

    private fun reportPhy(g: BluetoothGatt, phy: Int, status: Int) {
        if (g !== gatt || status != BluetoothGatt.GATT_SUCCESS) return
        val name = when (phy) {
            BluetoothDevice.PHY_LE_1M -> "1M"
            BluetoothDevice.PHY_LE_2M -> "2M"
            BluetoothDevice.PHY_LE_CODED -> if (phyTier == 2) "long-range S8" else "long-range"
            else -> "phy $phy"
        }
        Log.d(TAG, "phy now $name")
        LinkState.update { it.copy(phy = name) }
    }

    private fun openChannel(device: BluetoothDevice, psm: Int, gen: Int) {
        LinkState.phase("Connecting", "opening channel $psm")
        thread(name = "kairo-link-l2cap", isDaemon = true) {
            var sock: BluetoothSocket? = null
            try {
                sock = device.createInsecureL2capChannel(psm)
                sock.connect()
                Log.d(TAG, "channel open: max tx ${sock.maxTransmitPacketSize} B, max rx ${sock.maxReceivePacketSize} B")
                val m = LinkClient.connect(sock.inputStream, sock.outputStream, sock, secret, muxListener(gen))
                val s = sock
                h.post {
                    if (gen != generation || !running) { m.close(); return@post }
                    channel = s
                    mux = m
                    LinkState.mux = m
                    onLinkUp(device)
                }
            } catch (e: IOException) {
                try { sock?.close() } catch (_: IOException) {}
                h.post {
                    if (gen != generation) return@post
                    if (e is AuthException) lost("pairing code mismatch: ${e.message}", backoffMs = 60_000)
                    else lost("channel failed: ${e.message}")
                }
            }
        }
    }

    private fun muxListener(gen: Int) = object : Mux.Listener {
        override fun onActivity() {
            lastActivity = SystemClock.elapsedRealtime()
            wake.touch()
            if (!burst) h.post(boost)
        }

        override fun onClosed(cause: Throwable?) {
            h.post { if (gen == generation && running) lost("relay closed the link${cause?.message?.let { ": $it" } ?: ""}") }
        }
    }

    private fun onLinkUp(device: BluetoothDevice) {
        failures = 0
        fastScan = true
        phyTier = -1
        val g = gatt ?: return
        val fixed = when (intervalPolicy) {
            "low" -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            "balanced" -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            "high" -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else -> null
        }
        // A fixed policy pins the interval: pretend we're already bursting so boost never fires.
        burst = fixed != null
        // PHY first, interval second: issuing both at once collides (LL status 0x23) on these phones
        // and the controller silently keeps the old interval.
        setPhyTier(0)
        val initial = fixed ?: BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        h.postDelayed({ if (gatt === g) applyPriority(initial) }, 800)
        LinkState.update { it.copy(phase = "Connected", detail = "Kairo traffic goes through the relay", peer = device.address, power = if (fixed == null) "low-power" else "fixed $intervalPolicy") }
        h.postDelayed(rssiPoll, RSSI_EVERY_MS)
    }

    // ---- power ---------------------------------------------------------------------------------

    private val boost = Runnable {
        val g = gatt
        if (!burst && g != null && mux != null) {
            burst = true
            applyPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            LinkState.update { it.copy(power = "burst") }
            h.removeCallbacks(relax)
            h.postDelayed(relax, IDLE_MS)
        }
    }

    /** Requests [priority] and keeps it requested: a busy GATT queue or an LL collision gets retried. */
    private fun applyPriority(priority: Int) {
        wantedPriority = priority
        priorityRetries = 0
        requestWanted()
    }

    private val retryPriority = Runnable { requestWanted() }

    private fun requestWanted() {
        val g = gatt ?: return
        h.removeCallbacks(retryPriority)
        if (!g.requestConnectionPriority(wantedPriority) && priorityRetries++ < 10) h.postDelayed(retryPriority, 300)
    }

    private val relax: Runnable = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            val idle = SystemClock.elapsedRealtime() - lastActivity
            if (idle < IDLE_MS) { h.postDelayed(this, IDLE_MS - idle); return }
            burst = false
            applyPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
            LinkState.update { it.copy(power = "low-power") }
        }
    }

    private val rssiPoll: Runnable = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            if (mux == null) return
            g.readRemoteRssi()
            h.postDelayed(this, RSSI_EVERY_MS)
        }
    }

    /** 0 = 2M (fast, least airtime), 1 = Coded S2 (~2x range), 2 = Coded S8 (~4x range). */
    private fun adjustPhy(rssi: Int) {
        if (!adapter.isLeCodedPhySupported) return
        val next = when (phyTier) {
            0 -> if (rssi < -85) 2 else if (rssi < -78) 1 else 0
            1 -> if (rssi < -86) 2 else if (rssi > -70) 0 else 1
            else -> if (rssi > -70) 0 else if (rssi > -80) 1 else 2
        }
        if (next != phyTier) setPhyTier(next)
    }

    private fun setPhyTier(tier: Int) {
        val g = gatt ?: return
        phyTier = tier
        when (tier) {
            0 -> g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            1 -> g.setPreferredPhy(BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S2)
            else -> g.setPreferredPhy(BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S8)
        }
        Log.d(TAG, "PHY tier -> $tier")
    }

    // ---- loss & recovery -----------------------------------------------------------------------

    private fun dropLink() {
        generation++
        h.removeCallbacks(boost); h.removeCallbacks(relax); h.removeCallbacks(rssiPoll); h.removeCallbacks(retryPriority)
        mux?.close(); mux = null; LinkState.mux = null
        try { channel?.close() } catch (_: IOException) {}
        channel = null
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        burst = false
    }

    private fun lost(reason: String, backoffMs: Long? = null) {
        val wasUp = mux != null
        dropLink()
        if (!running) return
        failures = if (wasUp) 0 else failures + 1
        val delay = backoffMs ?: if (failures == 0) 500L else minOf(60_000L, 2_000L shl minOf(failures - 1, 5))
        LinkState.update { it.copy(phase = "Searching for relay", detail = reason, rssi = null, phy = null, power = null, peer = null) }
        h.postDelayed({ startScan() }, delay)
    }

    /** Called from the service when Bluetooth comes back on. */
    fun bluetoothChanged(on: Boolean) = h.post {
        if (on) { fastScan = true; startScan() } else { stopScan(); dropLink(); LinkState.phase("Bluetooth is off", "") }
    }
}
