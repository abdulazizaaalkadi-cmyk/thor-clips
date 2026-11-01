package com.aziz.thorclips

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    companion object {
        private const val REQ_PROJECTION = 10
        private const val REQ_SHIZUKU = 11
        private const val REQ_PERMS = 12
        private var current: MainActivity? = null
        fun refresh() {
            current?.let { a -> a.runOnUiThread { a.render() } }
        }
    }

    private lateinit var settings: Settings
    private lateinit var statusView: TextView
    private lateinit var shizukuView: TextView
    private lateinit var accView: TextView
    private lateinit var memView: TextView
    private lateinit var toggle: Button
    private lateinit var diagView: TextView

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val shizukuBinder = Shizuku.OnBinderReceivedListener { refresh() }
    private val shizukuDead = Shizuku.OnBinderDeadListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildUi())
        Shizuku.addRequestPermissionResultListener(shizukuResult)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinder)
        Shizuku.addBinderDeadListener(shizukuDead)
        askBasicPermissions()
    }

    override fun onResume() {
        super.onResume()
        current = this
        render()
    }

    override fun onPause() {
        current = null
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResult)
        Shizuku.removeBinderReceivedListener(shizukuBinder)
        Shizuku.removeBinderDeadListener(shizukuDead)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        col.addView(TextView(this).apply {
            text = "Thor Clips"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply { textSize = 15f; setPadding(0, dp(6), 0, 0) }
        col.addView(statusView)

        col.addView(header("Setup"))
        shizukuView = TextView(this)
        col.addView(shizukuView)
        col.addView(Button(this).apply {
            text = "Allow Shizuku access"
            setOnClickListener { requestShizuku() }
        })
        accView = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(accView)
        col.addView(Button(this).apply {
            text = "Turn on L3 + R3 button"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Find \"Thor Clips\" and turn it on", Toast.LENGTH_LONG).show()
                startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        col.addView(header("Clip length"))
        col.addView(radio(listOf("30 s" to 30, "60 s" to 60, "120 s" to 120), settings.seconds) {
            settings.seconds = it; render()
        })
        col.addView(header("Quality"))
        col.addView(radio(listOf("Low" to 0, "Medium" to 1, "High" to 2), settings.quality) {
            settings.quality = it; render()
        })
        col.addView(header("Frame rate"))
        col.addView(radio(listOf("30 fps" to 30, "60 fps" to 60), settings.fps) {
            settings.fps = it; render()
        })
        col.addView(CheckBox(this).apply {
            text = "Include game audio"
            isChecked = settings.audio
            setOnCheckedChangeListener { _, c -> settings.audio = c; render() }
        })
        memView = TextView(this).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(memView)
        col.addView(TextView(this).apply {
            text = "Changes apply next time you start the buffer."
            alpha = 0.6f
        })

        toggle = Button(this).apply {
            textSize = 18f
            setOnClickListener { onToggle() }
        }
        col.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        col.addView(Button(this).apply {
            text = "Save clip now"
            setOnClickListener {
                ReplayService.instance?.requestSave()
                    ?: Toast.makeText(this@MainActivity, "Start the buffer first", Toast.LENGTH_SHORT).show()
            }
        })

        col.addView(Button(this).apply {
            text = "Open my clips"
            setOnClickListener { startActivity(Intent(this@MainActivity, ClipsActivity::class.java)) }
        })

        col.addView(header("Diagnostics"))
        diagView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        col.addView(diagView)

        return ScrollView(this).apply { addView(col) }
    }

    private fun radio(options: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit): RadioGroup {
        val g = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; gravity = Gravity.START }
        options.forEach { (label, value) ->
            val b = RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                isChecked = value == selected
                setPadding(0, 0, dp(16), 0)
            }
            g.addView(b)
            b.setOnCheckedChangeListener { _, checked -> if (checked) onPick(value) }
        }
        return g
    }

    fun render() {
        val running = ReplayService.instance != null && !ReplayService.status.startsWith("Stopped") &&
            !ReplayService.status.startsWith("Error")
        statusView.text = ReplayService.status

        shizukuView.text = when {
            !Shizuku.pingBinder() -> "✗ Shizuku isn't running. Open the Shizuku app and start it."
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "✗ Shizuku is running, but Thor Clips isn't allowed yet."
            else -> "✓ Shizuku ready"
        }
        accView.text = if (accessibilityOn()) "✓ L3 + R3 button is on" else "✗ L3 + R3 button is off (you can still use the notification's Save clip)"
        memView.text = "Uses about ${settings.estimateMb()} MB of RAM while running."
        toggle.text = if (running) "Stop clip buffer" else "Start clip buffer"
        diagView.text = ReplayService.diagnostics.ifEmpty { "Start the buffer to see display info." } +
            "\nversion ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}"
    }

    private fun accessibilityOn(): Boolean {
        val enabled = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = "$packageName/${ClipAccessibilityService::class.java.name}"
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        for (s in splitter) if (s.equals(me, true) || s.equals("$packageName/.ClipAccessibilityService", true)) return true
        return false
    }

    // ------------------------------------------------------------------ actions

    private fun askBasicPermissions() {
        val want = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) want += Manifest.permission.POST_NOTIFICATIONS
        if (settings.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            want += Manifest.permission.RECORD_AUDIO
        }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), REQ_PERMS)
    }

    private fun requestShizuku() {
        when {
            !Shizuku.pingBinder() ->
                Toast.makeText(this, "Open Shizuku and start it first", Toast.LENGTH_LONG).show()
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
            else -> Shizuku.requestPermission(REQ_SHIZUKU)
        }
    }

    private fun onToggle() {
        val svc = ReplayService.instance
        if (svc != null && !ReplayService.status.startsWith("Error") && !ReplayService.status.startsWith("Stopped")) {
            startService(Intent(this, ReplayService::class.java).setAction(ReplayService.ACTION_STOP))
            return
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Set up Shizuku first", Toast.LENGTH_SHORT).show()
            return
        }
        askBasicPermissions()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Needed for game audio and to keep the buffer running", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, ReplayService::class.java)
            .setAction(ReplayService.ACTION_START)
            .putExtra(ReplayService.EXTRA_CODE, resultCode)
            .putExtra(ReplayService.EXTRA_DATA, data)
        startForegroundService(i)
    }
}
