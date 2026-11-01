package com.aziz.thorclips

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Simple list of saved clips (newest first). Tap to play, long-press to share. */
class ClipsActivity : Activity() {

    private data class Clip(val uri: Uri, val name: String, val durationMs: Long, val dateSec: Long, val sizeBytes: Long)

    private val clips = mutableListOf<Clip>()
    private lateinit var list: ListView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        root.addView(TextView(this).apply {
            text = "My Clips"
            textSize = 24f
            setPadding(0, 0, 0, pad / 2)
        })
        empty = TextView(this).apply {
            text = "No clips yet. Start the buffer in Thor Clips and press L3 + R3 while playing."
            visibility = View.GONE
        }
        root.addView(empty)
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> open(clips[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> share(clips[pos]); true }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        clips.clear()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
        )
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%Movies/ThorClips%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    clips += Clip(
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        c.getString(iName) ?: "clip",
                        c.getLong(iDur),
                        c.getLong(iDate),
                        c.getLong(iSize),
                    )
                }
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't list clips: ${t.message}", Toast.LENGTH_LONG).show()
        }
        empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun open(c: Clip) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (_: Throwable) {
            Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(c: Clip) {
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, c.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share clip"))
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = clips.size
        override fun getItem(position: Int) = clips[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val pad = (12 * resources.displayMetrics.density).toInt()
            val tv = (convertView as? TextView) ?: TextView(this@ClipsActivity).apply {
                textSize = 16f
                setPadding(0, pad, 0, pad)
            }
            val c = clips[position]
            val ago = DateUtils.getRelativeTimeSpanString(c.dateSec * 1000)
            val secs = c.durationMs / 1000
            val mb = c.sizeBytes / (1024 * 1024)
            tv.text = "${secs}s · $ago · $mb MB\n${c.name}"
            return tv
        }
    }
}
