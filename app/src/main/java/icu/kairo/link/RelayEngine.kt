package icu.kairo.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import icu.kairo.link.core.Handshake
import icu.kairo.link.core.Mux
import icu.kairo.link.core.RelaySession
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Runs on the phone with internet (the S22+).
 *
 * Listens on a BLE L2CAP channel and advertises its channel number (PSM) as service data, only
 * while no body is connected. There is deliberately no GATT server: Samsung's stack can keep a
 * closed server's service alive and route reads to it, which strands the body. Advertising starts fast (250 ms) for two minutes so a body
 * that just dropped finds it again quickly, then slows to 1 s. A second, long-range (LE Coded)
 * advertising set lets the body find it at the edge of range.
 */
@SuppressLint("MissingPermission")
class RelayEngine(private val context: Context, secret: ByteArray, private val wake: ActivityWakeLock) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val session = RelaySession(secret)
    private val ht = HandlerThread("kairo-link-relay").apply { start() }
    private val handler = Handler(ht.looper)

    @Volatile private var running = false
    private var server: BluetoothServerSocket? = null
    private var psmValue = ByteArray(0)
    @Volatile private var current: Pair<BluetoothSocket, Mux>? = null
    private val adSets = mutableListOf<AdvertisingSetCallback>()

    fun start() {
        running = true
        val ss = adapter.listenUsingInsecureL2capChannel()
        server = ss
        psmValue = byteArrayOf(ss.psm.toByte(), (ss.psm ushr 8).toByte(), Handshake.VERSION.toByte())
        handler.post { advertise(fast = true) }
        LinkState.phase("Waiting for body", "advertising; channel ${ss.psm}")
        thread(name = "kairo-link-accept", isDaemon = true) { acceptLoop(ss) }
    }

    fun stop() {
        running = false
        handler.post { stopAdvertising() }
        try { server?.close() } catch (_: IOException) {}
        current?.let { (sock, mux) -> mux.close(); try { sock.close() } catch (_: IOException) {} }
        current = null
        LinkState.mux = null
        ht.quitSafely()
        wake.release()
    }

    private fun acceptLoop(ss: BluetoothServerSocket) {
        while (running) {
            val sock = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) LinkState.phase("Error", "channel listener failed: ${e.message}")
                return
            }
            thread(name = "kairo-link-serve", isDaemon = true) { serve(sock) }
        }
    }

    private fun serve(sock: BluetoothSocket) {
        val peer = sock.remoteDevice?.address ?: "?"
        val mux = try {
            session.accept(sock.inputStream, sock.outputStream, sock, object : Mux.Listener {
                override fun onActivity() = wake.touch()
                override fun onClosed(cause: Throwable?) = onSessionClosed(sock, cause)
            })
        } catch (e: IOException) {
            Log.w(TAG, "rejected channel from $peer: ${e.message}")
            try { sock.close() } catch (_: IOException) {}
            return
        }
        // A body that reconnects after a silent drop replaces its stale session.
        val previous = current
        current = sock to mux
        LinkState.mux = mux
        previous?.let { (s, m) -> m.close(); try { s.close() } catch (_: IOException) {} }
        handler.post { stopAdvertising() }
        LinkState.update { it.copy(phase = "Connected", detail = "sharing internet with the body", peer = peer) }
    }

    private fun onSessionClosed(sock: BluetoothSocket, cause: Throwable?) {
        if (current?.first !== sock) return
        current = null
        LinkState.mux = null
        if (!running) return
        LinkState.update { it.copy(phase = "Waiting for body", detail = "link closed${cause?.message?.let { m -> ": $m" } ?: ""}", peer = null) }
        handler.post { advertise(fast = true) }
    }

    private val slowDown = Runnable { if (current == null && running) advertise(fast = false) }

    private fun advertise(fast: Boolean) {
        stopAdvertising()
        if (!running || current != null) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return LinkState.phase("Error", "Bluetooth advertising unavailable")
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(LinkIds.SERVICE), psmValue)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val legacy = AdvertisingSetParameters.Builder()
            .setLegacyMode(true).setConnectable(true).setScannable(true)
            .setInterval(if (fast) AdvertisingSetParameters.INTERVAL_MEDIUM else AdvertisingSetParameters.INTERVAL_HIGH)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        startSet(advertiser, legacy, data, "standard")
        if (adapter.isLeCodedPhySupported && adapter.isLeExtendedAdvertisingSupported) {
            val coded = AdvertisingSetParameters.Builder()
                .setLegacyMode(false).setConnectable(true).setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED).setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            startSet(advertiser, coded, data, "long-range")
        }
        if (fast) handler.postDelayed(slowDown, 120_000)
    }

    private fun startSet(advertiser: android.bluetooth.le.BluetoothLeAdvertiser, params: AdvertisingSetParameters, data: AdvertiseData, label: String) {
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status != ADVERTISE_SUCCESS) Log.w(TAG, "$label advertising failed: status $status")
                else Log.i(TAG, "$label advertising at ${txPower} dBm")
            }
        }
        adSets += cb
        advertiser.startAdvertisingSet(params, data, null, null, null, cb, handler)
    }

    private fun stopAdvertising() {
        handler.removeCallbacks(slowDown)
        val advertiser = adapter.bluetoothLeAdvertiser
        adSets.forEach { cb -> try { advertiser?.stopAdvertisingSet(cb) } catch (_: Exception) {} }
        adSets.clear()
    }
}
