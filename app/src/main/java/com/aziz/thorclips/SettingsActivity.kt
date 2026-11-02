package com.aziz.thorclips

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.Toast
import rikka.shizuku.Shizuku

/** Dual Clips settings (opened from the gear icon). */
class SettingsActivity : Activity() {

    private lateinit var s: Settings
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        build()
    }

    private fun build() {
        ui = Ui(this, s.palette(this))
        ui.applyWindow(this)
        val col = ui.column()
        col.addView(ui.header("Settings", R.drawable.ic_dualclips, null))

        col.addView(ui.section("Appearance"))
        col.addView(ui.settingRow("Theme", { s.theme.label }) { _ ->
            ui.choose("Theme", ThemeMode.values().map { it.label to it }, s.theme) {
                s.theme = it; build() // redraw in the new theme
            }
        })

        col.addView(ui.section("Recording"))
        col.addView(ui.settingRow("Clip length", { "${s.seconds} s" }) { r ->
            ui.choose("Clip length", listOf("30 s" to 30, "60 s" to 60, "120 s" to 120), s.seconds) { s.seconds = it; r() }
        })
        col.addView(ui.settingRow("Quality", { s.qualityLabel }) { r ->
            ui.choose("Quality", listOf("Low (960 wide)" to 0, "Medium (1280 wide)" to 1, "High (1920 wide)" to 2), s.quality) {
                s.quality = it; r()
            }
        })
        col.addView(ui.settingRow("Frame rate", { "${s.fps} fps" }) { r ->
            ui.choose("Frame rate", listOf("30 fps" to 30, "60 fps" to 60), s.fps) { s.fps = it; r() }
        })
        col.addView(ui.settingRow("Screens", { s.screenMode.label }) { r ->
            ui.choose("Which screens to record", ScreenMode.values().map { it.label to it }, s.screenMode) {
                s.screenMode = it; r()
            }
        })
        col.addView(ui.settingRow("Game audio", { if (s.audio) "On" else "Off" }) { r ->
            s.audio = !s.audio; r()
        })
        col.addView(ui.text(
            "Auto watches which screen your game is on and records just that screen, or both. " +
                "Without Shizuku only the top screen can be recorded. RAM use now: ~${s.estimateMb()} MB. " +
                "Changes apply next time you start the buffer.", 13f, dim = true
        ).apply { setPadding(ui.dp(4), ui.dp(10), 0, 0) })

        col.addView(ui.section("Setup"))
        col.addView(ui.settingRow("Shizuku", {
            when {
                ReplayService.shizukuReady() -> "Ready"
                runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Needs permission"
                else -> "Off"
            }
        }) { r ->
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false) && !ReplayService.shizukuReady()) {
                Shizuku.requestPermission(11)
            } else {
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                    ?: Toast.makeText(this, "Shizuku isn't installed", Toast.LENGTH_SHORT).show()
            }
            r()
        })
        col.addView(ui.settingRow("L3 + R3 button", { "Accessibility settings" }) { _ ->
            startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(ui.settingRow("Add My Clips to Cocoon / home", { "" }) { _ -> pinClipsShortcut() })

        col.addView(ui.section("About"))
        col.addView(ui.settingRow("Version", { BuildConfig.VERSION_NAME }) { _ -> })
        col.addView(ui.settingRow("Diagnostics", { "Show" }) { _ ->
            ui.dialog().setTitle("Diagnostics")
                .setMessage(ReplayService.diagnostics.ifEmpty { "Start the clip buffer first." })
                .setPositiveButton("OK", null).show()
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun pinClipsShortcut() {
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "Make Cocoon your default launcher first", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, ClipsActivity::class.java).setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val info = android.content.pm.ShortcutInfo.Builder(this, "my-clips-pinned")
            .setShortLabel("My Clips")
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_myclips))
            .setIntent(intent)
            .build()
        val ok = sm.requestPinShortcut(info, null)
        Toast.makeText(this, if (ok) "Sent — in Cocoon: Start → New → Add Games" else "Launcher refused", Toast.LENGTH_LONG).show()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyUp(keyCode, event)
    }
}
