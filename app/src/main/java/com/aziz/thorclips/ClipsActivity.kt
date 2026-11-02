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
        col.addView(ui.text("A play · hold Y options · B back", 13f, dim = true)
            .apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(4)) })
        empty = ui.text("No clips yet.\nStart the clip buffer in Dual Clips and press L3 + R3 while playing.", 16f, dim = true)
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
            setOnItemClickListener { _, _, pos, _ -> ClipStore.play(this@ClipsActivity, clips[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showMenu(clips[pos]); true }
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
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            val pos = grid.selectedItemPosition
            if (pos in clips.indices) showMenu(clips[pos])
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) return true
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------ hold-Y menu

    private fun showMenu(c: Clip) {
        val fav = c.id.toString() in prefs.favourites
        val items = listOf(
            "Rename" to { rename(c) },
            "Open in editor" to { openEditor(c) },
            "Trim" to { startActivity(Intent(this, TrimActivity::class.java).putExtra("id", c.id)) },
            "Share" to { ClipStore.share(this, c) },
            (if (fav) "Unfavourite" else "Favourite") to { toggleFav(c) },
            "Info" to { showInfo(c) },
            "Delete" to { confirmDelete(c) },
        )
        ui.dialog().setTitle(ClipStore.title(c))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun rename(c: Clip) {
        val input = EditText(this).apply {
            setText(ClipStore.title(c))
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = FrameLayout(this).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(input)
        }
        ui.dialog().setTitle("Rename clip").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val ok = runCatching { ClipStore.rename(this, c, input.text.toString()) }.getOrDefault(false)
                if (!ok) Toast.makeText(this, "Couldn't rename this clip", Toast.LENGTH_SHORT).show()
                load()
            }
            .setNegativeButton("Cancel", null).show()
        input.requestFocus()
    }

    private fun openEditor(c: Clip) {
        val saved = ClipStore.decodeEditor(prefs.editor)
        if (saved != null) {
            try {
                ClipStore.openInEditor(this, c, saved); return
            } catch (_: Throwable) {
                prefs.editor = null // uninstalled; ask again
            }
        }
        val eds = ClipStore.editors(this)
        if (eds.isEmpty()) {
            Toast.makeText(this, "No video editor found. Install one (e.g. CapCut) first.", Toast.LENGTH_LONG).show()
            return
        }
        ui.dialog().setTitle("Open in…")
            .setItems(eds.map { it.label }.toTypedArray()) { _, which ->
                val e = eds[which]
                prefs.editor = ClipStore.encodeEditor(e)
                runCatching { ClipStore.openInEditor(this, c, e) }
                    .onFailure { Toast.makeText(this, "${e.label} couldn't open it", Toast.LENGTH_SHORT).show() }
                Toast.makeText(this, "Will use ${e.label} next time (change in My Clips settings)", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun toggleFav(c: Clip) {
        val set = prefs.favourites.toMutableSet()
        val k = c.id.toString()
        if (!set.add(k)) set.remove(k)
        prefs.favourites = set
        load()
    }

    private fun showInfo(c: Clip) {
        ui.dialog().setTitle("Clip info").setMessage(ClipStore.info(this, c, prefs))
            .setPositiveButton("OK", null).show()
    }

    private fun confirmDelete(c: Clip) {
        ui.dialog().setTitle("Delete this clip?")
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
            title.text = (if (fav) "★ " else "") + ClipStore.title(c)
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
