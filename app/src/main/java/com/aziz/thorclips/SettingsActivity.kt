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
    private val feedback by lazy { ClipFeedback(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
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
        col.addView(ui.settingRow("Battery saver", { if (s.batterySaver) "On" else "Off" }) { r ->
            SaverTileService.toggle(this); r()
        })
        col.addView(ui.text("Battery saver records at 30 fps with a lower bitrate, checks screens less often, " +
            "and pauses whenever no game is open (Cocoon, settings…). Also a Quick Settings tile: \"Clip saver\".",
            13f, dim = true).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(6)) })
        col.addView(ui.settingRow("Clip length", { "${s.seconds} s" }) { r ->
            ui.choose("Clip length", listOf("10 s" to 10, "15 s" to 15, "30 s" to 30, "60 s" to 60, "120 s" to 120), s.seconds) { s.seconds = it; r() }
        })
        col.addView(ui.settingRow("Quality", { s.qualityLabel }) { r ->
            ui.choose("Quality", listOf("Low (960 wide)" to 0, "Medium (1280 wide)" to 1, "High (1920 wide)" to 2), s.quality) {
                s.quality = it; r()
            }
        })
        col.addView(ui.settingRow("Frame rate", { if (s.batterySaver) "30 fps (saver)" else "${s.fps} fps" }) { r ->
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
        col.addView(ui.settingRow("Voice chat (Discord etc.)", { if (s.voiceChat) "Included" else "Off" }) { r ->
            s.voiceChat = !s.voiceChat; r()
        })
        col.addView(ui.settingRow("My mic", { if (s.mic) "On" else "Off" }) { r ->
            if (!s.mic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 31)
            }
            s.mic = !s.mic; r()
        })
        col.addView(ui.settingRow("Mic volume", {
            when { s.micGain < 0.85f -> "Quiet"; s.micGain > 1.25f -> "Loud"; else -> "Normal" }
        }) { r ->
            ui.choose("Mic volume in clips", listOf("Quiet" to 0.7f, "Normal" to 1.0f, "Loud" to 1.6f), s.micGain) { s.micGain = it; r() }
        })
        col.addView(ui.text("With My mic on, each clip keeps a copy of the audio without your voice — " +
            "hold Y on it in My Clips → Remove my voice.", 13f, dim = true).apply { setPadding(ui.dp(4), ui.dp(8), 0, 0) })
        col.addView(ui.text(
            "Auto watches which screen your game is on and records just that screen, or both. " +
                "Without Shizuku only the top screen can be recorded. RAM use now: ~${s.estimateMb()} MB. " +
                "Changes apply next time you start the buffer.", 13f, dim = true
        ).apply { setPadding(ui.dp(4), ui.dp(10), 0, 0) })

        col.addView(ui.section("Save location"))
        col.addView(ui.settingRow("Save new clips to", {
            val st = Settings(this)
            if (st.saveToSd) "SD card" else "Thor storage"
        }) { r ->
            val st = Settings(this)
            val sd = ClipStore.sdCardVolume(this)
            val opts = listOf("Thor storage" to false, (if (sd != null) "SD card (${sd.second})" else "SD card (not found)") to true)
            ui.choose("Save new clips and screenshots to", opts, st.saveToSd) { wantSd ->
                if (wantSd && sd == null) {
                    android.widget.Toast.makeText(this, "No SD card found. Insert one set up as portable storage.", android.widget.Toast.LENGTH_LONG).show()
                } else st.saveToSd = wantSd
                r()
            }
        })
        col.addView(ui.settingRow("Folder name", { Settings(this).folderName }) { r ->
            val st = Settings(this)
            val input = android.widget.EditText(this).apply {
                setText(st.folderName); setSelectAllOnFocus(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            val box = android.widget.FrameLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input) }
            ui.dialog().setTitle("Folder name").setMessage("Clips go to Movies/<name>, screenshots to Pictures/<name>. Clips already saved stay where they are and still show in My Clips.")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val clean = input.text.toString().trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
                    if (clean.isNotEmpty()) st.folderName = clean
                    r()
                }.setNegativeButton("Cancel", null).show()
        })
        col.addView(ui.text("Only affects new clips — nothing is moved. To move old ones, use Select → Export to SD card.", 13f, dim = true)
            .apply { setPadding(ui.dp(4), ui.dp(8), 0, 0) })

        col.addView(ui.section("Buttons"))
        col.addView(ui.settingRow("Save clip", { s.clipCombo.label }) { r ->
            ui.choose("Button combo to save a clip", Combo.values().map { it.label to it }, s.clipCombo) {
                if (it != Combo.OFF && it == s.shotCombo) s.shotCombo = Combo.OFF
                s.clipCombo = it; r()
            }
        })
        col.addView(ui.settingRow("Screenshot", { s.shotCombo.label }) { r ->
            ui.choose("Button combo for a screenshot", Combo.values().map { it.label to it }, s.shotCombo) {
                if (it != Combo.OFF && it == s.clipCombo) s.clipCombo = Combo.OFF
                s.shotCombo = it; r()
            }
        })
        col.addView(ui.text("Screenshots capture the recorded screen(s), like your clips, and go to Pictures/DualClips. " +
            "Pick combos your emulators don't already use.", 13f, dim = true).apply { setPadding(ui.dp(4), ui.dp(8), 0, 0) })

        ClipAlertRows.add(this, ui, col, feedback)

        col.addView(ui.section("Setup"))
        col.addView(ui.settingRow("Shizuku", {
            when {
                ReplayService.shizukuReady() -> "Ready"
                runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Needs permission"
                else -> "Off"
            }
        }) { _ ->
            startActivity(Intent(this, ShizukuSetupActivity::class.java))
        })
        col.addView(ui.settingRow("L3 + R3 button", { "Accessibility settings" }) { _ ->
            startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(ui.settingRow("Add My Clips to Cocoon / home", { "" }) { _ -> pinClipsShortcut() })

        col.addView(ui.section("About"))
        col.addView(ui.settingRow("Version", { BuildConfig.VERSION_NAME }) { _ -> })
        col.addView(ui.settingRow("Diagnostics", { "Show" }) { _ ->
            val text = "Dual Clips ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE}\n\n" +
                ReplayService.diagnostics.ifEmpty { "(clip buffer not started)\n" } +
                "\n--- log ---\n" + EventLog.text().ifEmpty { "(empty)" }
            ui.dialog().setTitle("Diagnostics")
                .setMessage(text)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Dual Clips diagnostics", text))
                    Toast.makeText(this, "Copied, paste it to Claude", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null).show()
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

    override fun onDestroy() {
        feedback.release()
        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyUp(keyCode, event)
    }
}
