package icu.kairo.link

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import icu.kairo.link.core.PairingCode

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var roleGroup: RadioGroup
    private lateinit var relayBox: LinearLayout
    private lateinit var bodyBox: LinearLayout
    private lateinit var codeView: TextView
    private lateinit var codeInput: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var toggle: Button
    private lateinit var batteryButton: Button
    private lateinit var status: TextView
    private val idRelay = View.generateViewId()
    private val idBody = View.generateViewId()
    private val idAuto = View.generateViewId()
    private val idLink = View.generateViewId()
    private val idDirect = View.generateViewId()
    private var syncing = false
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); ui.postDelayed(this, 1_000) } }

    private val bg = Color.rgb(12, 14, 20)
    private val fg = Color.rgb(226, 232, 240)
    private val dim = Color.rgb(140, 150, 170)
    private val accent = Color.rgb(120, 190, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        applyDebugProvisioning(intent)
        buildUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDebugProvisioning(intent)
        syncFromPrefs()
    }

    override fun onResume() { super.onResume(); syncFromPrefs(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    /** Debug builds only: `adb shell am start -n icu.kairo.link/.MainActivity --es role body --es code XXXX-... --ez start true`. */
    private fun applyDebugProvisioning(intent: Intent?) {
        if (intent == null || applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent.getStringExtra("role")?.let { r -> runCatching { Role.valueOf(r.uppercase()) }.getOrNull()?.let { prefs.role = it } }
        intent.getStringExtra("code")?.let { c -> PairingCode.normalize(c)?.let { prefs.code = PairingCode.format(it) } }
        intent.getStringExtra("mode")?.let { m -> runCatching { RouteMode.valueOf(m.uppercase()) }.getOrNull()?.let { prefs.mode = it } }
        if (prefs.role == Role.RELAY && prefs.code == null) prefs.code = PairingCode.generate()
        intent.getStringExtra("interval")?.let { prefs.interval = it }
        if (intent.getBooleanExtra("selftest", false)) runSelfTest()
        if (intent.hasExtra("start")) {
            if (intent.getBooleanExtra("start", false)) { prefs.enabled = true; LinkService.start(this) }
            else { prefs.enabled = false; LinkService.stop(this) }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        root.addView(text("Kairo-Link", 28f, fg, bold = true))
        root.addView(text("Low-power Bluetooth tether for Kairo's body", 14f, dim).apply { setPadding(0, 0, 0, dp(20)) })

        root.addView(label("This phone"))
        roleGroup = RadioGroup(this).apply {
            addView(radio(idRelay, "Shares its internet (relay)"))
            addView(radio(idBody, "Is Kairo's body"))
            setOnCheckedChangeListener { _, id ->
                if (syncing || id == View.NO_ID) return@setOnCheckedChangeListener
                prefs.role = if (id == idRelay) Role.RELAY else Role.BODY
                if (prefs.role == Role.RELAY && prefs.code == null) prefs.code = PairingCode.generate()
                syncFromPrefs()
            }
        }
        root.addView(roleGroup)

        relayBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        relayBox.addView(label("Pairing code — type this on Kairo's body"))
        codeView = text("", 30f, accent, bold = true, mono = true).apply { setTextIsSelectable(true) }
        relayBox.addView(codeView)
        relayBox.addView(button("New code") {
            prefs.code = PairingCode.generate(); syncFromPrefs(); restartIfRunning()
        })
        root.addView(relayBox)

        bodyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyBox.addView(label("Pairing code from the relay phone"))
        codeInput = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX-XXXX"
            setTextColor(fg); setHintTextColor(dim); typeface = Typeface.MONOSPACE; textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        bodyBox.addView(codeInput)
        bodyBox.addView(label("Route Kairo traffic"))
        modeGroup = RadioGroup(this).apply {
            addView(radio(idAuto, "Automatic — own Wi-Fi when available, else the relay"))
            addView(radio(idLink, "Always through the relay"))
            addView(radio(idDirect, "Never through the relay"))
            setOnCheckedChangeListener { _, id ->
                if (syncing || id == View.NO_ID) return@setOnCheckedChangeListener
                prefs.mode = when (id) { idLink -> RouteMode.LINK_ONLY; idDirect -> RouteMode.DIRECT_ONLY; else -> RouteMode.AUTO }
                restartIfRunning()
            }
        }
        bodyBox.addView(modeGroup)
        root.addView(bodyBox)

        toggle = button("Start") { onToggle() }
        root.addView(toggle)
        batteryButton = button("Let Kairo-Link run in the background") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        root.addView(batteryButton)

        root.addView(label("Kairo's rover"))
        root.addView(button("Drive the rover") { startActivity(Intent(this, RoverActivity::class.java)) })

        root.addView(label("Status"))
        status = text("", 14f, fg, mono = true)
        root.addView(status)

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
        syncFromPrefs()
    }

    private fun syncFromPrefs() {
        if (!::roleGroup.isInitialized) return
        syncing = true
        try { applyPrefsToViews() } finally { syncing = false }
        render()
    }

    private fun applyPrefsToViews() {
        val role = prefs.role
        when (role) { Role.RELAY -> roleGroup.check(idRelay); Role.BODY -> roleGroup.check(idBody); Role.NONE -> roleGroup.clearCheck() }
        relayBox.visibility = if (role == Role.RELAY) View.VISIBLE else View.GONE
        bodyBox.visibility = if (role == Role.BODY) View.VISIBLE else View.GONE
        codeView.text = prefs.code ?: ""
        if (role == Role.BODY && codeInput.text.isEmpty()) codeInput.setText(prefs.code ?: "")
        modeGroup.check(when (prefs.mode) { RouteMode.AUTO -> idAuto; RouteMode.LINK_ONLY -> idLink; RouteMode.DIRECT_ONLY -> idDirect })
    }

    private fun onToggle() {
        if (prefs.enabled) {
            prefs.enabled = false
            LinkService.stop(this)
            render(); return
        }
        if (prefs.role == Role.NONE) return toast("Choose this phone's role first")
        if (prefs.role == Role.BODY) {
            val n = PairingCode.normalize(codeInput.text.toString()) ?: return toast("That pairing code isn't valid")
            prefs.code = PairingCode.format(n)
            codeInput.setText(prefs.code)
        }
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1) else startLink()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val bluetoothOk = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (bluetoothOk) startLink() else toast("Kairo-Link needs Bluetooth permission to work")
    }

    private fun startLink() {
        prefs.enabled = true
        LinkService.start(this)
        render()
    }

    private fun restartIfRunning() {
        if (!prefs.enabled) return
        LinkService.stop(this)
        ui.postDelayed({ LinkService.start(this) }, 300)
    }

    private fun render() {
        if (!::status.isInitialized) return
        toggle.text = if (prefs.enabled) "Stop" else "Start"
        batteryButton.visibility =
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        val s = LinkState.current
        val m = LinkState.mux
        val age = (SystemClock.elapsedRealtime() - s.since) / 1000
        status.text = buildString {
            appendLine("${s.phase}  (${age / 60}m ${age % 60}s)")
            if (s.detail.isNotEmpty()) appendLine(s.detail)
            s.peer?.let { appendLine("peer     $it") }
            s.rssi?.let { appendLine("signal   $it dBm") }
            s.phy?.let { appendLine("radio    $it") }
            s.power?.let { appendLine("interval $it") }
            if (m != null) {
                appendLine("streams  ${m.streamCount}")
                appendLine("sent     ${kb(m.bytesSent.get())}")
                appendLine("received ${kb(m.bytesReceived.get())}")
            }
            if (prefs.role == Role.BODY) appendLine("proxy    127.0.0.1:${LinkIds.PROXY_PORT}")
        }
    }

    /** Debug: `--ez selftest true` measures the link on-device; the report goes to logcat (tag KairoLink). */
    private fun runSelfTest() {
        if (prefs.role != Role.BODY) return
        Thread {
            android.util.Log.i(TAG, "self-test starting")
            try {
                icu.kairo.link.core.SelfTest.run(LinkIds.DIAG_PORT) { android.util.Log.i(TAG, "self-test: $it") }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "self-test crashed", t)
            }
            android.util.Log.i(TAG, "self-test done")
        }.start()
    }

    private fun kb(b: Long) = if (b < 10_240) "$b B" else if (b < 10_485_760) "${b / 1024} KB" else "${b / 1_048_576} MB"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = when { mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
    }

    private fun label(s: String) = text(s.uppercase(), 12f, dim, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) }

    private fun radio(id: Int, s: String) = RadioButton(this).apply { this.id = id; text = s; setTextColor(fg); textSize = 15f }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }
}
