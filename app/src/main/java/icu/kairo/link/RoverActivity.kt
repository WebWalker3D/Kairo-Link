package icu.kairo.link

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import icu.kairo.link.core.PairingCode
import icu.kairo.link.core.rover.MjpegReader
import icu.kairo.link.core.rover.RoverStatus
import icu.kairo.link.core.rover.StopReason
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Drives Kairo's rover by hand: hold the pad to move, lift to stop, big STOP.
 * Visible-screen only. Leaving, backgrounding or losing focus stops the rover.
 */
class RoverActivity : Activity(), RoverEngine.Listener {
    private lateinit var prefs: Prefs
    private var engine: RoverEngine? = null
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var codeBox: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var video: ImageView
    private lateinit var speed: SeekBar
    @Volatile private var streamUrl: String? = null
    private var streamThread: Thread? = null

    private val bg = Color.rgb(12, 14, 20)
    private val fg = Color.rgb(226, 232, 240)
    private val dim = Color.rgb(140, 150, 170)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        connectIfReady()
    }

    override fun onPause() {
        // Leaving the screen ends the session: STOP, then disconnect. No background link.
        engine?.pause()
        engine?.stop()
        engine?.close()
        engine = null
        stopStream()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { engine?.pause(); engine?.stop() } else engine?.resume()
    }

    override fun onDestroy() {
        engine?.stop()
        engine?.close()
        engine = null
        stopStream()
        super.onDestroy()
    }

    private fun connectIfReady() {
        val code = prefs.roverCode
        codeBox.visibility = if (code == null) View.VISIBLE else View.GONE
        if (code == null) return showState("Enter the rover's pairing code")
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) return requestPermissions(needed.toTypedArray(), 2)
        if (engine == null) engine = RoverEngine(this, code, this)
        engine?.connect()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) connectIfReady()
        else showState("Bluetooth permission is needed to drive the rover")
    }

    // RoverEngine.Listener (called on the main thread)
    override fun onState(state: String) = showState(state)

    override fun onRejected() {
        engine?.close(); engine = null
        codeBox.visibility = View.VISIBLE
        detail.text = ""
    }

    override fun onStatus(status: RoverStatus) {
        val stop = if (status.stopReason == StopReason.NONE) "" else "stopped: ${status.stopReason.name.lowercase().replace('_', ' ')}"
        detail.text = buildString {
            append(if (status.driving) "driving" else "still")
            if (stop.isNotEmpty()) append(" · ").append(stop)
            if (!status.motorsCommissioned) append(" · motors locked until commissioning")
            append(" · cap ${status.speedCap}%")
            append(" · deadman trips ${status.deadmanTrips}")
            append(if (status.wifi) " · Wi-Fi ${status.ipv4}" else " · no Wi-Fi")
            if (status.serverSession) append(" · Kairo online")
        }
    }

    override fun onLiveView(url: String?) {
        if (url == streamUrl) return
        stopStream()
        streamUrl = url
        if (url != null) startStream(url)
    }

    private fun startStream(url: String) {
        streamThread = Thread {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000; conn.readTimeout = 5_000
                conn.inputStream.use { input ->
                    val reader = MjpegReader(input)
                    while (streamUrl == url && !Thread.currentThread().isInterrupted) {
                        val jpeg = reader.next() ?: break
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                        runOnUiThread { if (streamUrl == url) video.setImageBitmap(bmp) }
                    }
                }
                conn.disconnect()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopStream() {
        streamUrl = null
        streamThread?.interrupt()
        streamThread = null
    }

    private fun showState(s: String) { if (::state.isInitialized) state.text = s }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16)) }
        root.addView(text("Kairo's rover", 24f, fg, bold = true))
        state = text("", 15f, fg).also(root::addView)
        detail = text("", 12f, dim, mono = true).also(root::addView)

        codeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        codeInput = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX-XXXX"; setTextColor(fg); setHintTextColor(dim); typeface = Typeface.MONOSPACE; textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        codeBox.addView(text("Rover pairing code", 12f, dim, bold = true))
        codeBox.addView(codeInput)
        codeBox.addView(Button(this).apply {
            text = "Save code"; isAllCaps = false
            setOnClickListener {
                val n = PairingCode.normalize(codeInput.text.toString())
                if (n == null) showState("That pairing code isn't valid") else {
                    prefs.roverCode = PairingCode.format(n)
                    getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(codeInput.windowToken, 0)
                    codeInput.clearFocus()
                    engine?.close(); engine = null
                    connectIfReady()
                }
            }
        })
        root.addView(codeBox)

        video = ImageView(this).apply {
            setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(10) }
        }
        root.addView(video)

        val pad = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.rgb(28, 32, 44)); cornerRadius = dp(18).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
            addView(text("Hold to drive · lift to stop", 13f, dim).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        }
        pad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val x = ((e.x / v.width) * 2 - 1).coerceIn(-1f, 1f)
                    val y = (1 - (e.y / v.height) * 2).coerceIn(-1f, 1f) // up = forward
                    val scale = speed.progress + 10
                    val left = ((y + x).coerceIn(-1f, 1f) * scale).roundToInt()
                    val right = ((y - x).coerceIn(-1f, 1f) * scale).roundToInt()
                    engine?.hold(left, right)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> engine?.release()
            }
            true
        }
        root.addView(pad)

        root.addView(text("Speed", 12f, dim, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        speed = SeekBar(this).apply { max = 90; progress = 40 } // 10..100 %
        root.addView(speed)

        root.addView(TextView(this).apply {
            text = "Change pairing code"; textSize = 13f; setTextColor(dim); setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                engine?.stop(); engine?.close(); engine = null
                stopStream()
                codeInput.setText("")
                codeBox.visibility = View.VISIBLE
                detail.text = ""
                showState("Enter the rover's pairing code")
            }
        })

        root.addView(Button(this).apply {
            text = "STOP"; textSize = 26f; setTextColor(Color.WHITE); isAllCaps = false
            background = GradientDrawable().apply { setColor(Color.rgb(200, 40, 40)); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84)).apply { topMargin = dp(10) }
            setOnClickListener { engine?.stop() }
        })

        setContentView(root.apply { setBackgroundColor(bg) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
    }
}
