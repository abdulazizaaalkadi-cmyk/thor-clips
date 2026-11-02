package com.aziz.thorclips

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.util.concurrent.Executors

/** Pick a start and end, then save the trimmed part as a new clip (the original is kept). */
class TrimActivity : Activity() {

    private lateinit var prefs: ClipsPrefs
    private lateinit var ui: Ui
    private lateinit var clip: Clip
    private lateinit var video: VideoView
    private lateinit var label: TextView
    private var startMs = 0L
    private var endMs = 0L
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ClipsPrefs(this)
        ui = Ui(this, prefs.palette(this))
        ui.applyWindow(this)
        val id = intent.getLongExtra("id", -1)
        clip = ClipStore.list(this, prefs).firstOrNull { it.id == id } ?: run { finish(); return }
        endMs = clip.durationMs

        val col = ui.column()
        col.addView(ui.text("Trim · ${ClipStore.title(clip)}", 20f, bold = true))
        video = VideoView(this)
        col.addView(video, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(12) })
        video.setVideoURI(clip.uri)
        video.setOnPreparedListener { it.isLooping = false; video.seekTo(1) }

        label = ui.text("", 15f).apply { setPadding(0, ui.dp(10), 0, 0) }
        col.addView(label)

        val dur = clip.durationMs.coerceAtLeast(1).toInt()
        col.addView(ui.text("Start", 13f, dim = true).apply { setPadding(0, ui.dp(8), 0, 0) })
        col.addView(seek(dur, 0) { v ->
            startMs = v.toLong().coerceAtMost(endMs - 1000).coerceAtLeast(0); video.seekTo(startMs.toInt()); update()
        })
        col.addView(ui.text("End", 13f, dim = true))
        col.addView(seek(dur, dur) { v ->
            endMs = v.toLong().coerceAtLeast(startMs + 1000).coerceAtMost(clip.durationMs); video.seekTo(endMs.toInt()); update()
        })

        val r = ui.row()
        r.addView(ui.secondaryButton("Preview") {
            video.seekTo(startMs.toInt()); video.start()
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ video.pause() }, endMs - startMs)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(6) })
        r.addView(ui.primaryButton("Save trimmed copy") { save() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(6) })
        col.addView(r, ui.lp(top = 14))
        setContentView(col)
        update()
    }

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = value
        keyProgressIncrement = 500
        progressTintList = android.content.res.ColorStateList.valueOf(ui.p.c1)
        thumbTintList = android.content.res.ColorStateList.valueOf(ui.p.c2)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun fmt(ms: Long) = "%d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

    private fun update() {
        label.text = "${fmt(startMs)} → ${fmt(endMs)}   (${(endMs - startMs) / 1000}s)"
    }

    private fun save() {
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        val s = startMs
        val e = endMs
        Executors.newSingleThreadExecutor().execute {
            val result = runCatching { ClipStore.trim(this, clip, s, e) }
            main.post {
                if (result.isSuccess) {
                    Toast.makeText(this, "Trimmed clip saved", Toast.LENGTH_SHORT).show(); finish()
                } else {
                    Toast.makeText(this, "Trim failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
        return super.onKeyUp(keyCode, event)
    }
}
