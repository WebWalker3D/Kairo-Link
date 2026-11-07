package icu.kairo.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import icu.kairo.link.core.PairingCode

/** Foreground service that owns whichever engine this phone's role needs. */
class LinkService : Service() {
    companion object {
        const val ACTION_START = "icu.kairo.link.START"
        const val ACTION_STOP = "icu.kairo.link.STOP"
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LinkService::class.java).setAction(ACTION_STOP))
        }
    }

    private var relay: RelayEngine? = null
    private var body: BodyEngine? = null
    private lateinit var wake: ActivityWakeLock
    private var shownPhase: String? = null

    private val stateListener: () -> Unit = {
        val s = LinkState.current
        if (s.phase != shownPhase) {
            shownPhase = s.phase
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { body?.bluetoothChanged(true); if (relay != null) restartEngine() }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    body?.bluetoothChanged(false)
                    relay?.stop(); relay = null
                    LinkState.phase("Bluetooth is off", "")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wake = ActivityWakeLock(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Kairo-Link", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        LinkState.addListener(stateListener)
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngines()
            LinkState.phase("Stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (relay == null && body == null) startEngine()
        return START_STICKY
    }

    override fun onDestroy() {
        stopEngines()
        LinkState.removeListener(stateListener)
        unregisterReceiver(bluetoothReceiver)
        super.onDestroy()
    }

    private fun startEngine() {
        val prefs = Prefs(this)
        val secret = try {
            PairingCode.secret(prefs.code ?: "")
        } catch (_: IllegalArgumentException) {
            LinkState.phase("Not set up", "enter the pairing code shown on the relay phone")
            return
        }
        try {
            when (prefs.role) {
                Role.RELAY -> relay = RelayEngine(this, secret, wake).also { it.start() }
                Role.BODY -> body = BodyEngine(this, secret, prefs.mode, wake, prefs.interval).also { it.start() }
                Role.NONE -> LinkState.phase("Not set up", "choose this phone's role")
            }
        } catch (e: Exception) {
            stopEngines()
            LinkState.phase("Error", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun restartEngine() {
        stopEngines()
        startEngine()
    }

    private fun stopEngines() {
        relay?.stop(); relay = null
        body?.stop(); body = null
        LinkState.mux = null
    }

    private fun notification(): Notification {
        val s = LinkState.current
        val role = if (Prefs(this).role == Role.RELAY) "Sharing internet with Kairo" else "Kairo's link"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle("$role: ${s.phase}")
            .setContentText(s.detail.ifEmpty { "Bluetooth LE link" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (prefs.enabled && prefs.role != Role.NONE) LinkService.start(context)
    }
}
