package com.aziz.thorclips

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ScrollView

/** My Clips settings: its own theme, accent colour, previews, sort and default editor. */
class ClipsSettingsActivity : Activity() {

    private lateinit var prefs: ClipsPrefs
    private lateinit var ui: Ui
    private val feedback by lazy { ClipFeedback(this) }

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

        col.addView(ui.section("Library"))
        col.addView(ui.settingRow("Clip previews", { if (prefs.previews) "On" else "Off" }) { r ->
            prefs.previews = !prefs.previews; r()
        })
        col.addView(ui.text("Shows a picture from each clip. Off keeps the list instant.", 13f, dim = true)
            .apply { setPadding(ui.dp(4), ui.dp(6), 0, 0) })
        col.addView(ui.settingRow("Sort", { prefs.sort.label }) { r ->
            ui.choose("Sort clips", SortOrder.values().map { it.label to it }, prefs.sort) { prefs.sort = it; r() }
        })
        col.addView(ui.settingRow("Show", { prefs.filter.label }) { r ->
            ui.choose("Show", LibraryFilter.values().map { it.label to it }, prefs.filter) { prefs.filter = it; r() }
        })
        col.addView(ui.settingRow("Photo editor (screenshots)", {
            ClipStore.decodeEditor(prefs.imageEditor)?.label ?: "Ask each time"
        }) { r ->
            val eds = ClipStore.editors(this, "image/png")
            val options = listOf("Ask each time" to "") + eds.map { it.label to ClipStore.encodeEditor(it) }
            ui.choose("Photo editor", options, prefs.imageEditor ?: "") { prefs.imageEditor = it.ifEmpty { null }; r() }
        })
        col.addView(ui.settingRow("Default editor", {
            ClipStore.decodeEditor(prefs.editor)?.label ?: "Ask each time"
        }) { r ->
            val eds = ClipStore.editors(this)
            val options = listOf("Ask each time" to "") + eds.map { it.label to ClipStore.encodeEditor(it) }
            ui.choose("Default editor", options, prefs.editor ?: "") { prefs.editor = it.ifEmpty { null }; r() }
        })

        ClipAlertRows.add(this, ui, col, feedback)

        setContentView(ScrollView(this).apply { addView(col) })
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
