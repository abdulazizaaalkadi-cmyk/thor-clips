package com.aziz.thorclips

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

/** Dual Clips home: status, start/stop, save, and setup checklist. */
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
    private lateinit var ui: Ui
    private var themeKey = ""

    private lateinit var dot: View
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var toggle: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var tileHint: TextView

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val shizukuBinder = Shizuku.OnBinderReceivedListener { refresh() }
    private val shizukuDead = Shizuku.OnBinderDeadListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        settings = Settings(this)
        Shizuku.addRequestPermissionResultListener(shizukuResult)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinder)
        Shizuku.addBinderDeadListener(shizukuDead)
        askBasicPermissions()
    }

    override fun onResume() {
        super.onResume()
        current = this
        val key = settings.theme.name + resources.configuration.uiMode
        if (key != themeKey) {
            themeKey = key
            ui = Ui(this, settings.palette(this))
            ui.applyWindow(this)
            setContentView(build())
        }
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

    private fun build(): View {
        val col = ui.column()
        col.addView(ui.header("Dual Clips", R.drawable.ic_dualclips) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        // Status card
        val card = ui.column(18).apply { background = ui.borderCard() }
        val top = ui.row()
        dot = View(this)
        top.addView(dot, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { rightMargin = ui.dp(10) })
        statusView = ui.text("", 20f, bold = true)
        top.addView(statusView)
        card.addView(top)
        detailView = ui.text("", 14f, dim = true).apply { setPadding(0, ui.dp(8), 0, 0) }
        card.addView(detailView)
        col.addView(card, ui.lp(top = 20))

        toggle = ui.primaryButton("") { onToggle() }
        col.addView(toggle, ui.lp(top = 16))

        val pair = ui.row()
        pair.addView(ui.secondaryButton("Save clip now") {
            ReplayService.instance?.requestSave()
                ?: Toast.makeText(this, "Start the clip buffer first", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(6) })
        pair.addView(ui.secondaryButton("My Clips") {
            startActivity(Intent(this, ClipsActivity::class.java))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(6) })
        col.addView(pair, ui.lp(top = 12))

        col.addView(ui.text("Press L3 + R3 while playing to save the last ${settings.seconds} seconds.", 14f, dim = true)
            .apply { setPadding(ui.dp(4), ui.dp(14), 0, 0) })

        tileHint = ui.text("Tip: add the Dual Clips tile to Quick Settings (pull down → edit/pencil) to start it without opening the app.", 13f, dim = true)
            .apply { setPadding(ui.dp(4), ui.dp(8), 0, 0) }
        col.addView(tileHint)

        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(setupBox)

        toggle.requestFocus()
        return ScrollView(this).apply { addView(col); isFillViewport = true }
    }

    fun render() {
        if (!::ui.isInitialized) return
        val on = ReplayService.recording
        val err = ReplayService.status.startsWith("Error")
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF34D399.toInt() else if (err) 0xFFF87171.toInt() else ui.p.dim)
        }
        statusView.text = when {
            on -> ReplayService.status
            err -> "Something went wrong"
            ReplayService.instance != null -> ReplayService.status
            else -> "Clip buffer is off"
        }
        detailView.text = if (err) ReplayService.status.removePrefix("Error: ") else
            "${settings.seconds}s clips · ${settings.qualityLabel} · ${settings.fps} fps · " +
                "${settings.screenMode.label} · ~${settings.estimateMb()} MB RAM"
        toggle.text = if (on || (ReplayService.instance != null && !err)) "Stop clip buffer" else "Start clip buffer"
        tileHint.visibility = if (on) View.GONE else View.VISIBLE

        // Setup checklist: only shows what still needs doing.
        setupBox.removeAllViews()
        val shizukuOk = ReplayService.shizukuReady()
        val accOk = accessibilityOn()
        if (!shizukuOk || !accOk) setupBox.addView(ui.section("Setup"))
        if (!shizukuOk) {
            setupBox.addView(ui.settingRow("Set up Shizuku for both screens", { "Top screen only now" }) {
                startActivity(Intent(this, ShizukuSetupActivity::class.java))
            })
        }
        if (!accOk) {
            setupBox.addView(ui.settingRow("L3 + R3 button", { "Turn on" }) { openAccessibility() })
        }
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

    fun requestShizuku() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        when {
            !running -> ui.dialog().setTitle("Shizuku is off")
                .setMessage("Without Shizuku, Dual Clips can only record the top screen.\n\n" +
                    "To record both screens: connect to Wi-Fi, open Shizuku and tap Start.")
                .setPositiveButton("Open Shizuku") { _, _ ->
                    packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                        ?: Toast.makeText(this, "Shizuku isn't installed", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("OK", null).show()
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
            else -> Shizuku.requestPermission(REQ_SHIZUKU)
        }
    }

    fun openAccessibility() {
        Toast.makeText(this, "Find \"Dual Clips\" and turn it on", Toast.LENGTH_LONG).show()
        startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun onToggle() {
        val svc = ReplayService.instance
        if (svc != null && !ReplayService.status.startsWith("Error")) {
            startService(Intent(this, ReplayService::class.java).setAction(ReplayService.ACTION_STOP))
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
            Toast.makeText(this, "Needed to record the screen and game audio", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, ReplayService::class.java)
            .setAction(ReplayService.ACTION_START)
            .putExtra(ReplayService.EXTRA_CODE, resultCode)
            .putExtra(ReplayService.EXTRA_DATA, data)
        startForegroundService(i)
    }
}
