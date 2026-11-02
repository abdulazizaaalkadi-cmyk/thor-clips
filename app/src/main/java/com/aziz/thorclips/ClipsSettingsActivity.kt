package com.aziz.thorclips

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ScrollView

/** My Clips settings: its own theme, accent colour, previews, sort and default editor. */
class ClipsSettingsActivity : Activity() {

    private lateinit var prefs: ClipsPrefs
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ClipsPrefs(this)
        build()
    }

    private fun build() {
        ui = Ui(this, prefs.palette(this))
        ui.applyWindow(this)
        val col = ui.column()
        col.addView(ui.header("My Clips settings", R.drawable.ic_myclips, null))

        col.addView(ui.section("Look"))
        col.addView(ui.settingRow("Theme", { prefs.theme.label }) { _ ->
            ui.choose("Theme", ThemeMode.values().map { it.label to it }, prefs.theme) { prefs.theme = it; build() }
        })
        col.addView(ui.settingRow("Colour", { prefs.accent.label }) { _ ->
            ui.choose("Colour", Accent.values().map { it.label to it }, prefs.accent) { prefs.accent = it; build() }
        })

        col.addView(ui.section("Library"))
        col.addView(ui.settingRow("Clip previews", { if (prefs.previews) "On" else "Off" }) { r ->
            prefs.previews = !prefs.previews; r()
        })
        col.addView(ui.text("Shows a picture from each clip. Off keeps the list instant.", 13f, dim = true)
            .apply { setPadding(ui.dp(4), ui.dp(6), 0, 0) })
        col.addView(ui.settingRow("Sort", { prefs.sort.label }) { r ->
            ui.choose("Sort clips", SortOrder.values().map { it.label to it }, prefs.sort) { prefs.sort = it; r() }
        })
        col.addView(ui.settingRow("Default editor", {
            ClipStore.decodeEditor(prefs.editor)?.label ?: "Ask each time"
        }) { r ->
            val eds = ClipStore.editors(this)
            val options = listOf("Ask each time" to "") + eds.map { it.label to ClipStore.encodeEditor(it) }
            ui.choose("Default editor", options, prefs.editor ?: "") { prefs.editor = it.ifEmpty { null }; r() }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyUp(keyCode, event)
    }
}
