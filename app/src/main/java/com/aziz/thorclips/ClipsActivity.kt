package com.aziz.thorclips

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * My Clips: your saved clips. A = play, hold Y (or long-press) = options, B = back.
 * Previews are optional (My Clips settings) so the list stays instant by default.
 */
class ClipsActivity : Activity() {

    companion object {
        private const val REQ_DELETE = 30
    }

    private lateinit var prefs: ClipsPrefs
    private lateinit var ui: Ui
    private var clips: List<Clip> = emptyList()
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var filterButton: TextView
    private lateinit var barHolder: LinearLayout

    /** Selection mode: ids of the picked clips/screenshots. */
    private var selecting = false
    private val selected = LinkedHashSet<Long>()
    private var countView: TextView? = null
    private var allButton: TextView? = null
    private var lookKey = ""

    private val main = Handler(Looper.getMainLooper())
    private val thumbs = object : LruCache<Long, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val thumbLoader = Executors.newFixedThreadPool(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ClipsPrefs(this)
    }

    override fun onResume() {
        super.onResume()
        val key = "${prefs.theme}${prefs.accent}${prefs.previews}${resources.configuration.uiMode}"
        if (key != lookKey) {
            lookKey = key
            ui = Ui(this, prefs.palette(this))
            ui.applyWindow(this)
            setContentView(build())
        }
        load()
    }

    override fun onDestroy() {
        thumbLoader.shutdownNow()
        super.onDestroy()
    }

    private fun build(): View {
        val col = ui.column()
        col.addView(ui.header("My Clips", R.drawable.ic_myclips) {
            startActivity(Intent(this, ClipsSettingsActivity::class.java))
        })
        barHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(barHolder, ui.lp(top = 10))
        renderBar()
        empty = ui.text("Nothing here yet.\nStart the clip buffer in Dual Clips, then use your clip or screenshot combo while playing.", 16f, dim = true)
            .apply { setPadding(ui.dp(4), ui.dp(24), 0, 0); visibility = View.GONE }
        col.addView(empty)

        grid = GridView(this).apply {
            numColumns = if (prefs.previews) GridView.AUTO_FIT else 1
            columnWidth = ui.dp(260)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = ui.dp(12)
            verticalSpacing = ui.dp(12)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setDrawSelectorOnTop(false)
            isFocusable = true
            clipToPadding = false
            setPadding(0, ui.dp(10), 0, ui.dp(24))
            adapter = this@ClipsActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                if (selecting) toggle(clips[pos]) else ClipStore.play(this@ClipsActivity, clips[pos])
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                if (selecting) toggle(clips[pos]) else showMenu(clips[pos]); true
            }
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    private fun load() {
        clips = try {
            ClipStore.list(this, prefs)
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't list clips: ${t.message}", Toast.LENGTH_LONG).show()
            emptyList()
        }
        empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        if (clips.isNotEmpty() && !grid.hasFocus()) grid.requestFocus()
    }

    // ------------------------------------------------------------------ controller

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y && selecting) return true
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            val pos = grid.selectedItemPosition
            if (pos in clips.indices) showMenu(clips[pos])
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) return true
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (selecting) exitSelect() else finish()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------ select mode

    private fun smallButton(label: String, onClick: () -> Unit) = ui.secondaryButton(label, onClick).apply {
        textSize = 14f
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
    }

    private fun renderBar() {
        barHolder.removeAllViews()
        val row = ui.row()
        if (!selecting) {
            row.addView(ui.text("A open · hold Y options · B back", 13f, dim = true)
                .apply { setPadding(ui.dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(smallButton("Select") { enterSelect() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
            filterButton = smallButton("Show: ${prefs.filter.label}") {
                ui.choose("Show", LibraryFilter.values().map { it.label to it }, prefs.filter) {
                    prefs.filter = it
                    filterButton.text = "Show: ${it.label}"
                    load()
                }
            }
            row.addView(filterButton)
            barHolder.addView(row)
            return
        }
        countView = ui.text("", 16f, bold = true).apply { setPadding(ui.dp(4), 0, ui.dp(8), 0) }
        row.addView(countView, LinearLayout.LayoutParams(0, -2, 1f))
        allButton = smallButton("Select all") {
            if (selected.size == clips.size) selected.clear() else clips.forEach { selected += it.id }
            updateSelection()
        }
        row.addView(allButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
        row.addView(smallButton("Done") { exitSelect() })
        barHolder.addView(row)

        val actions = ui.row()
        val lp = { LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) } }
        actions.addView(smallButton("Share") { pickedOrToast()?.let { ClipStore.shareMany(this, it) } }, lp())
        actions.addView(smallButton("Export to SD card") { pickedOrToast()?.let { exportSd(it) } }, lp())
        actions.addView(smallButton("Delete") { pickedOrToast()?.let { deletePicked(it) } },
            LinearLayout.LayoutParams(0, -2, 1f))
        barHolder.addView(actions, ui.lp(top = 8))
        updateSelection()
    }

    private fun enterSelect() {
        selecting = true
        selected.clear()
        renderBar()
        adapter.notifyDataSetChanged()
        grid.requestFocus()
    }

    private fun exitSelect() {
        selecting = false
        selected.clear()
        renderBar()
        adapter.notifyDataSetChanged()
    }

    private fun toggle(c: Clip) {
        if (!selected.add(c.id)) selected.remove(c.id)
        updateSelection()
    }

    private fun updateSelection() {
        countView?.text = if (selected.isEmpty()) "Pick clips (A)" else "${selected.size} selected"
        allButton?.text = if (clips.isNotEmpty() && selected.size == clips.size) "Deselect all" else "Select all"
        adapter.notifyDataSetChanged()
    }

    private fun pickedOrToast(): List<Clip>? {
        val list = clips.filter { it.id in selected }
        if (list.isEmpty()) { Toast.makeText(this, "Select something first", Toast.LENGTH_SHORT).show(); return null }
        return list
    }

    private fun deletePicked(list: List<Clip>) {
        ui.dialog().setTitle("Delete ${list.size} item${if (list.size == 1) "" else "s"}?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                ClipStore.deleteMany(this, list, REQ_DELETE)
                exitSelect(); load()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exportSd(list: List<Clip>) {
        val vol = ClipStore.sdCardVolume(this)
        if (vol == null) {
            ui.dialog().setTitle("No SD card found")
                .setMessage("Insert an SD card (and make sure it's set up as portable storage), then try again.")
                .setPositiveButton("OK", null).show()
            return
        }
        val n = list.size
        ui.dialog().setTitle("Export $n item${if (n == 1) "" else "s"} to ${vol.second}")
            .setMessage("They'll go to ${vol.second} › ${ClipStore.EXPORT_FOLDER}.\n\nCopy keeps them here too. Move frees up space on the Thor.")
            .setPositiveButton("Copy") { _, _ -> runExport(vol.first, list, move = false) }
            .setNeutralButton("Move") { _, _ -> runExport(vol.first, list, move = true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runExport(volume: String, list: List<Clip>, move: Boolean) {
        val progressText = ui.text("Preparing…", 16f).apply { setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(8)) }
        val dlg = ui.dialog().setTitle(if (move) "Moving to SD card" else "Copying to SD card")
            .setView(progressText).setCancelable(false).show()
        Executors.newSingleThreadExecutor().execute {
            val (ok, bad) = ClipStore.exportToSd(this, volume, list, move) { i ->
                main.post { progressText.text = "${minOf(i + 1, list.size)} of ${list.size}" }
            }
            main.post {
                runCatching { dlg.dismiss() }
                val msg = if (bad == 0) "${if (move) "Moved" else "Copied"} $ok to SD card › ${ClipStore.EXPORT_FOLDER}"
                else "$ok done, $bad failed (is the SD card full?)"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                exitSelect()
                load()
            }
        }
    }

    // ------------------------------------------------------------------ hold-Y menu

    private fun showMenu(c: Clip) {
        val fav = c.id.toString() in prefs.favourites
        val items = listOfNotNull(
            "Rename" to { rename(c) },
            "Open in editor" to { openEditor(c) },
            if (c.isImage) null else ("Trim" to { startActivity(Intent(this, TrimActivity::class.java).putExtra("id", c.id)) }),
            if (!c.isImage && prefs.hasMic(c.id)) ("Remove my voice" to { removeVoice(c) }) else null,
            "Share" to { ClipStore.share(this, c) },
            "Export to SD card" to { exportSd(listOf(c)) },
            (if (fav) "Unfavourite" else "Favourite") to { toggleFav(c) },
            "Info" to { showInfo(c) },
            "Delete" to { confirmDelete(c) },
        )
        ui.dialog().setTitle(ClipStore.title(c))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun rename(c: Clip) {
        val kind = if (c.isImage) "screenshot" else "clip"
        val input = EditText(this).apply {
            setText(ClipStore.title(c))
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = FrameLayout(this).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(input)
        }
        ui.dialog().setTitle("Rename $kind").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val ok = runCatching { ClipStore.rename(this, c, input.text.toString()) }.getOrDefault(false)
                if (!ok) Toast.makeText(this, "Couldn't rename this $kind", Toast.LENGTH_SHORT).show()
                load()
            }
            .setNegativeButton("Cancel", null).show()
        input.requestFocus()
    }

    private fun openEditor(c: Clip) {
        val saved = ClipStore.decodeEditor(if (c.isImage) prefs.imageEditor else prefs.editor)
        if (saved != null) {
            try {
                ClipStore.openInEditor(this, c, saved); return
            } catch (_: Throwable) {
                if (c.isImage) prefs.imageEditor = null else prefs.editor = null // uninstalled; ask again
            }
        }
        val eds = ClipStore.editors(this, c.mime)
        if (eds.isEmpty()) {
            Toast.makeText(this, if (c.isImage) "No photo editor found." else "No video editor found. Install one (e.g. CapCut) first.", Toast.LENGTH_LONG).show()
            return
        }
        ui.dialog().setTitle("Open in…")
            .setItems(eds.map { it.label }.toTypedArray()) { _, which ->
                val e = eds[which]
                if (c.isImage) prefs.imageEditor = ClipStore.encodeEditor(e) else prefs.editor = ClipStore.encodeEditor(e)
                runCatching { ClipStore.openInEditor(this, c, e) }
                    .onFailure { Toast.makeText(this, "${e.label} couldn't open it", Toast.LENGTH_SHORT).show() }
                Toast.makeText(this, "Will use ${e.label} next time (change in My Clips settings)", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun removeVoice(c: Clip) {
        ui.dialog().setTitle("Remove your voice from this clip?")
            .setMessage("Keeps the game and voice-chat audio, drops your mic. This can't be undone.")
            .setPositiveButton("Remove") { _, _ ->
                Toast.makeText(this, "Removing your voice…", Toast.LENGTH_SHORT).show()
                Executors.newSingleThreadExecutor().execute {
                    val ok = runCatching { ClipStore.removeMic(this, c) }.getOrDefault(false)
                    if (ok) prefs.setHasMic(c.id, false)
                    main.post {
                        Toast.makeText(this, if (ok) "Your voice was removed" else "Couldn't remove it from this clip", Toast.LENGTH_SHORT).show()
                        load()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toggleFav(c: Clip) {
        val set = prefs.favourites.toMutableSet()
        val k = c.id.toString()
        if (!set.add(k)) set.remove(k)
        prefs.favourites = set
        load()
    }

    private fun showInfo(c: Clip) {
        ui.dialog().setTitle(if (c.isImage) "Screenshot info" else "Clip info").setMessage(ClipStore.info(this, c, prefs))
            .setPositiveButton("OK", null).show()
    }

    private fun confirmDelete(c: Clip) {
        ui.dialog().setTitle(if (c.isImage) "Delete this screenshot?" else "Delete this clip?")
            .setMessage("${ClipStore.title(c)}\n\nThis can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (ClipStore.delete(this, c, REQ_DELETE)) load()
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) load()
    }

    // ------------------------------------------------------------------ grid items

    private val adapter = object : BaseAdapter() {
        override fun getCount() = clips.size
        override fun getItem(position: Int) = clips[position]
        override fun getItemId(position: Int) = clips[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = clips[position]
            val holder = (convertView?.tag as? Holder) ?: Holder().also { it.build() }
            holder.bind(c)
            return holder.root
        }
    }

    private inner class Holder {
        lateinit var root: LinearLayout
        lateinit var thumb: ImageView
        lateinit var title: TextView
        lateinit var meta: TextView
        var boundId = -1L

        fun build() {
            root = LinearLayout(this@ClipsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                background = ui.focusBg(ui.p.surface, ui.dpf(16f))
            }
            thumb = ImageView(this@ClipsActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.solid(ui.p.surface2, ui.dpf(10f))
                clipToOutline = true
                visibility = if (prefs.previews) View.VISIBLE else View.GONE
            }
            root.addView(thumb, LinearLayout.LayoutParams(-1, ui.dp(150)).apply { bottomMargin = ui.dp(10) })
            title = ui.text("", 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            meta = ui.text("", 13f, dim = true).apply { gravity = Gravity.START }
            root.addView(title)
            root.addView(meta)
            root.tag = this
        }

        fun bind(c: Clip) {
            boundId = c.id
            val fav = c.id.toString() in prefs.favourites
            val check = if (selecting) (if (c.id in selected) "☑ " else "☐ ") else ""
            title.text = check + (if (fav) "★ " else "") + (if (c.isImage) "▣ " else "") + ClipStore.title(c)
            root.alpha = if (selecting && c.id !in selected) 0.75f else 1f
            meta.text = ClipStore.meta(this@ClipsActivity, c)
            if (!prefs.previews) return
            val cached = thumbs.get(c.id)
            if (cached != null) { thumb.setImageBitmap(cached); return }
            thumb.setImageDrawable(null)
            val id = c.id
            thumbLoader.execute {
                val bmp = runCatching { contentResolver.loadThumbnail(c.uri, Size(480, 480), null) }.getOrNull()
                    ?: return@execute
                thumbs.put(id, bmp)
                main.post { if (boundId == id) thumb.setImageBitmap(bmp) }
            }
        }
    }
}
