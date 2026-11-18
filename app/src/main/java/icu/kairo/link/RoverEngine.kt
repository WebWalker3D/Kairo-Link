package icu.kairo.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import icu.kairo.link.core.rover.DriveLoop
import icu.kairo.link.core.rover.RoverAuth
import icu.kairo.link.core.rover.RoverProtocol
import icu.kairo.link.core.rover.RoverStatus
import icu.kairo.link.core.rover.RoverUuids
import icu.kairo.link.core.rover.RoverWrite
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE central for the rover's GATT service, independent of the Link tether.
 * Lives only while the Rover screen is visible; never drives from the background.
 * One GATT operation in flight at a time; a newer DRIVE replaces a queued one,
 * a STOP is never dropped.
 */
@SuppressLint("MissingPermission") // RoverActivity grants BLUETOOTH_SCAN/CONNECT before connect()
class RoverEngine(context: Context, code: String, private val listener: Listener) {
    interface Listener {
        fun onState(state: String)
        fun onStatus(status: RoverStatus)
        fun onLiveView(url: String?)
        fun onRejected()
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val secret = RoverAuth.secret(code)
    private val random = SecureRandom()
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var closed = false
    private var loop: DriveLoop? = null
    private var nonceC = ByteArray(16)
    private var nonceR = ByteArray(16)
    private var sessionKey: ByteArray? = null
    private var lastStatus: RoverStatus? = null
    private var liveToken = ByteArray(0)
    private var lockedUntil = 0L

    private sealed interface Op {
        data class Read(val uuid: UUID) : Op
        data class Write(val uuid: UUID, val value: ByteArray, val noResponse: Boolean) : Op
        object EnableStatus : Op
    }
    private val queue = ArrayDeque<Op>()
    private var busy = false

    private val ticker = object : Runnable {
        override fun run() {
            loop?.tick()?.let { w ->
                when (w) {
                    is RoverWrite.Drive -> enqueueDrive(w.payload)
                    RoverWrite.Stop -> enqueue(Op.Write(RoverUuids.STOP, byteArrayOf(1), false))
                }
            }
            if (!closed) main.postDelayed(this, 100)
        }
    }

    fun connect() {
        if (closed || gatt != null || scanning) return
        if (android.os.SystemClock.elapsedRealtime() < lockedUntil) return // rover asked us to wait
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return listener.onState("Bluetooth is off")
        val scanner = adapter.bluetoothLeScanner ?: return listener.onState("Bluetooth is off")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(RoverUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        listener.onState("Looking for the rover…")
        scanner.startScan(listOf(filter), settings, scanCallback)
        main.postDelayed({
            if (scanning) {
                stopScan()
                listener.onState("Rover not found — is it powered? Still looking…")
                main.postDelayed({ connect() }, 3_000)
            }
        }, 15_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            listener.onState("Connecting…")
            gatt = result.device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onState("Scan failed ($errorCode)")
        }
    }

    private fun stopScan() {
        scanning = false
        app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun hold(left: Int, right: Int) = main.post { loop?.hold(left, right) }
    fun release() = main.post { loop?.release() }
    fun pause() = main.post { loop?.pause() }
    fun resume() = main.post { loop?.resume() }

    /** STOP is honoured by the rover without authentication, so send it whenever connected. */
    fun stop() = main.post {
        loop?.release()
        if (gatt != null) enqueue(Op.Write(RoverUuids.STOP, byteArrayOf(1), false))
    }

    fun close() {
        main.post {
            closed = true
            stopScan()
            main.removeCallbacks(ticker)
            gatt?.let { g ->
                // Best effort final STOP; the rover's disconnect handling and deadman cover the rest.
                g.getService(RoverUuids.SERVICE)?.getCharacteristic(RoverUuids.STOP)?.let {
                    g.writeCharacteristic(it, byteArrayOf(1), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                }
                g.disconnect(); g.close()
            }
            gatt = null
            loop = null
        }
    }

    private fun enqueue(op: Op) { queue.addLast(op); pump() }

    private fun enqueueDrive(payload: ByteArray) {
        queue.removeAll { it is Op.Write && it.uuid == RoverUuids.DRIVE }
        enqueue(Op.Write(RoverUuids.DRIVE, payload, true))
    }

    private fun pump() {
        val g = gatt ?: return queue.clear()
        if (busy) return
        val op = queue.pollFirst() ?: return
        val svc = g.getService(RoverUuids.SERVICE) ?: return
        busy = true
        val started = when (op) {
            is Op.Read -> svc.getCharacteristic(op.uuid)?.let { g.readCharacteristic(it) } ?: false
            is Op.Write -> svc.getCharacteristic(op.uuid)?.let {
                g.writeCharacteristic(it, op.value, if (op.noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } ?: false
            Op.EnableStatus -> svc.getCharacteristic(RoverUuids.STATUS)?.let { ch ->
                g.setCharacteristicNotification(ch, true)
                ch.getDescriptor(CCCD)?.let {
                    g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                }
            } ?: false
        }
        if (!started) { busy = false; main.post { pump() } }
    }

    private fun opDone() { busy = false; pump() }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
            if (closed || gatt !== g) { g.close(); return@post }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(100)
            } else {
                g.close()
                if (gatt === g) gatt = null
                loop = null; sessionKey = null; busy = false; queue.clear()
                main.removeCallbacks(ticker)
                listener.onLiveView(null)
                if (!closed) {
                    listener.onState("Disconnected — reconnecting…")
                    main.postDelayed({ connect() }, 2_000) // screen is visible: keep trying
                }
            }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (!closed && gatt === g) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
            if (closed || gatt !== g) return@post
            if (g.getService(RoverUuids.SERVICE) == null) { listener.onState("Not a Kairo rover"); g.disconnect() }
            else { listener.onState("Pairing…"); enqueue(Op.Read(RoverUuids.AUTH)) }
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { if (!closed && gatt === g) { onRead(g, ch.uuid, value, status); opDone() } }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (closed || gatt !== g) return@post
                if (ch.uuid == RoverUuids.AUTH) enqueue(Op.Read(RoverUuids.AUTH))
                opDone()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (!closed && gatt === g) opDone() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == RoverUuids.STATUS) main.post { if (!closed && gatt === g) onStatus(value) }
        }
    }

    private fun onRead(g: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        when (uuid) {
            RoverUuids.AUTH -> if (sessionKey == null && value.size == 21) {
                random.nextBytes(nonceC)
                nonceR = RoverAuth.nonceR(value)
                val hello = runCatching { RoverAuth.clientHello(secret, value, nonceC) }.getOrNull()
                    ?: return run { listener.onState("Not a Kairo rover"); g.disconnect() }
                enqueue(Op.Write(RoverUuids.AUTH, hello, false))
            } else if (sessionKey == null) {
                if (RoverAuth.isLockedOut(value)) {
                    // Too many recent failures on the rover: not a verdict on this code. One retry in a minute.
                    listener.onState("Too many pairing attempts — trying again in a minute")
                    lockedUntil = android.os.SystemClock.elapsedRealtime() + 61_000
                    g.disconnect()
                    main.postDelayed({ if (!closed) connect() }, 62_000)
                    return
                }
                if (!RoverAuth.verifyRoverProof(secret, value, nonceC, nonceR)) {
                    closed = true // no retry storm: wait for the code to be edited
                    listener.onRejected()
                    listener.onState("The rover rejected this pairing code")
                    g.disconnect()
                    return
                }
                val sk = RoverAuth.sessionKey(secret, nonceR, nonceC)
                sessionKey = sk
                loop = DriveLoop(sk)
                listener.onState("Connected")
                enqueue(Op.EnableStatus)
                enqueue(Op.Read(RoverUuids.STATUS))
                enqueue(Op.Read(RoverUuids.LIVEVIEW))
                main.removeCallbacks(ticker)
                main.post(ticker)
            }
            RoverUuids.STATUS -> onStatus(value)
            RoverUuids.LIVEVIEW -> {
                liveToken = value
                lastStatus?.let { listener.onLiveView(RoverProtocol.liveViewUrl(it, liveToken)) }
            }
        }
    }

    private fun onStatus(value: ByteArray) {
        val s = RoverStatus.parse(value) ?: return
        val ipChanged = s.ipv4 != lastStatus?.ipv4
        lastStatus = s
        listener.onStatus(s)
        if (ipChanged && liveToken.size == 16) listener.onLiveView(RoverProtocol.liveViewUrl(s, liveToken))
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
