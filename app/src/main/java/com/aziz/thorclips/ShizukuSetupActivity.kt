package com.aziz.thorclips

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * Step-by-step Shizuku setup. Each step checks itself live and ticks off when done,
 * and its button jumps straight to the right place.
 */
class ShizukuSetupActivity : Activity() {

    companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    }

    private lateinit var ui: Ui
    private val main = Handler(Looper.getMainLooper())
    private val steps = ArrayList<Step>()
    private lateinit var summary: TextView

    private inner class Step(
        val title: String,
        val help: String,
        val button: String,
        val done: () -> Boolean,
        val action: () -> Unit,
    ) {
        lateinit var mark: TextView
        lateinit var card: View
    }

    private val poll = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this, Settings(this).palette(this))
        ui.applyWindow(this)
        Shizuku.addRequestPermissionResultListener(permListener)

        steps += Step(
            "Install Shizuku",
            "Free app that lets Dual Clips record the bottom screen.",
            "Get Shizuku",
            { installed() },
            { openStore() }
        )
        steps += Step(
            "Turn on Developer options",
            "Open About device and tap “Build number” 7 times until it says you're a developer.",
            "Open About device",
            { devOptionsOn() },
            { safeStart(Intent(SysSettings.ACTION_DEVICE_INFO_SETTINGS)) }
        )
        steps += Step(
            "Connect to Wi-Fi",
            "Any Wi-Fi works, even your phone's hotspot with mobile data off. No internet needed.",
            "Wi-Fi settings",
            { onWifi() },
            { safeStart(Intent(SysSettings.ACTION_WIFI_SETTINGS)) }
        )
        steps += Step(
            "Pair Shizuku (one time only)",
            "Opens Developer options on the top screen and Shizuku on the bottom.\n" +
                "1. Top: Wireless debugging → on → “Pair device with pairing code”.\n" +
                "2. Bottom: Shizuku → Pairing, then type the code from its notification.\n" +
                "Skip this if you've paired before.",
            "Open both",
            { running() },
            { openPairing() }
        )
        steps += Step(
            "Start Shizuku",
            "In Shizuku, tap Start under “Start via Wireless debugging”. " +
                "You redo only this step after a reboot. Once started, Wi-Fi can go off.",
            "Open Shizuku",
            { running() },
            { openShizuku() }
        )
        steps += Step(
            "Allow Dual Clips",
            "Lets Dual Clips use Shizuku. You only do this once.",
            "Allow",
            { ReplayService.shizukuReady() },
            { requestPermission() }
        )

        val col = ui.column()
        col.addView(ui.header("Shizuku setup", R.drawable.ic_dualclips, null))
        summary = ui.text("", 15f, dim = true).apply { setPadding(ui.dp(4), ui.dp(10), 0, ui.dp(4)) }
        col.addView(summary)
        steps.forEachIndexed { i, s -> col.addView(stepCard(i + 1, s), ui.lp(top = 12)) }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun stepCard(n: Int, s: Step): View {
        val card = ui.column(16).apply { background = ui.solid(ui.p.surface, ui.dpf(18f)) }
        val top = ui.row()
        s.mark = TextView(this).apply {
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
        }
        top.addView(s.mark, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { rightMargin = ui.dp(12) })
        top.addView(ui.text(s.title, 17f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(top)
        card.addView(ui.text(s.help, 14f, dim = true).apply { setPadding(ui.dp(46), ui.dp(6), 0, ui.dp(10)) })
        card.addView(ui.secondaryButton(s.button) { s.action() }.apply {
            background = ui.focusBg(ui.p.surface2, ui.dpf(14f))
        }, ui.lp().apply { leftMargin = ui.dp(46) })
        s.card = card
        card.tag = n
        return card
    }

    private fun render() {
        // The first unfinished step is the one to do now.
        var current = -1
        steps.forEachIndexed { i, s ->
            val ok = runCatching { s.done() }.getOrDefault(false)
            if (!ok && current < 0) current = i
        }
        steps.forEachIndexed { i, s ->
            val ok = runCatching { s.done() }.getOrDefault(false)
            s.mark.text = if (ok) "✓" else "${i + 1}"
            s.mark.background = if (ok) ui.solid(0xFF22C55E.toInt(), ui.dpf(17f))
            else if (i == current) ui.gradient(ui.dpf(17f)) else ui.solid(ui.p.surface2, ui.dpf(17f))
            s.card.background = if (i == current) ui.borderCard(ui.p.surface, ui.dp(2)) else ui.solid(ui.p.surface, ui.dpf(18f))
            s.card.alpha = if (ok && i != current) 0.6f else 1f
        }
        summary.text = if (current < 0) "All set — Dual Clips can record both screens. ✓"
        else "Next: step ${current + 1}. Each step ticks itself off when it's done."
    }

    // ------------------------------------------------------------------ checks

    private fun installed() = runCatching { packageManager.getPackageInfo(SHIZUKU_PKG, 0); true }.getOrDefault(false)

    private fun devOptionsOn() =
        SysSettings.Global.getInt(contentResolver, SysSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun onWifi(): Boolean {
        if (running()) return true // already started: Wi-Fi no longer matters
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun running() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    // ------------------------------------------------------------------ actions

    private fun safeStart(i: Intent, opts: Bundle? = null): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts); true
    } catch (_: Throwable) {
        false
    }

    private fun openStore() {
        if (!safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG")))) {
            safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
        }
    }

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (i == null || !safeStart(i)) Toast.makeText(this, "Install Shizuku first (step 1)", Toast.LENGTH_SHORT).show()
    }

    /** Developer options on the top screen, Shizuku on the bottom screen. */
    private fun openPairing() {
        val shizuku = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (shizuku == null) {
            Toast.makeText(this, "Install Shizuku first (step 1)", Toast.LENGTH_SHORT).show(); return
        }
        safeStart(Intent(SysSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        val bottom = getSystemService(DisplayManager::class.java).displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON }
        val opts = bottom?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        main.postDelayed({
            if (opts == null || !safeStart(shizuku.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK), opts)) {
                Toast.makeText(this, "Open Shizuku on the other screen to pair", Toast.LENGTH_LONG).show()
            }
        }, 600)
    }

    private fun requestPermission() {
        when {
            !running() -> Toast.makeText(this, "Start Shizuku first (step 5)", Toast.LENGTH_SHORT).show()
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                Toast.makeText(this, "Already allowed ✓", Toast.LENGTH_SHORT).show()
            else -> Shizuku.requestPermission(21)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyUp(keyCode, event)
    }
}
