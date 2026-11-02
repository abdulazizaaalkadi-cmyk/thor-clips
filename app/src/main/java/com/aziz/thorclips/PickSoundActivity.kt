package com.aziz.thorclips

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/**
 * Lets you use your own clip sound. Shows the rules first, then the file picker,
 * then checks the file and copies it into Dual Clips.
 *
 * Rules: 0.2 to 2 seconds long, at most 1 MB, MP3 / OGG / WAV / M4A / AAC / FLAC.
 */
class PickSoundActivity : Activity() {

    companion object {
        const val MIN_MS = 200L
        const val MAX_MS = 2000L
        const val MAX_BYTES = 1_000_000L
        fun file(ctx: android.content.Context) = File(ctx.filesDir, "custom_sound")
    }

    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this, Settings(this).palette(this))
        if (savedInstanceState != null) return
        ui.dialog().setTitle("Use your own clip sound")
            .setMessage(
                "Pick a short sound file. It must be:\n\n" +
                    "• 0.2 to 2 seconds long\n" +
                    "• 1 MB or smaller\n" +
                    "• MP3, OGG, WAV, M4A, AAC or FLAC\n\n" +
                    "Tip: quiet, short sounds work best over games. It plays at the Sound volume you set."
            )
            .setPositiveButton("Choose file") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                @Suppress("DEPRECATION")
                runCatching { startActivityForResult(i, 1) }.onFailure { finish() }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { finish(); return }
        val problem = check(uri)
        if (problem != null) {
            ui.dialog().setTitle("Can't use this sound").setMessage(problem)
                .setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        try {
            contentResolver.openInputStream(uri)!!.use { input -> file(this).outputStream().use { input.copyTo(it) } }
            val st = Settings(this)
            st.clipSound = true
            st.clipSoundName = "custom"
            st.customSoundLabel = displayName(uri) ?: "Custom sound"
            Toast.makeText(this, "Clip sound set to “${st.customSoundLabel}”", Toast.LENGTH_SHORT).show()
            ClipFeedback(this).let { f -> f.playSound("custom"); android.os.Handler(mainLooper).postDelayed({ f.release() }, 2500) }
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't copy the file: ${t.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    /** Returns a reason the file can't be used, or null if it's fine. */
    private fun check(uri: Uri): String? {
        val size = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        if (size > MAX_BYTES) return "This file is ${"%.1f".format(size / 1_000_000.0)} MB. The limit is 1 MB — trim it or export it at a lower quality."
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(this, uri)
            val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return "Couldn't read this file's length. Try an MP3, OGG or WAV."
            if (hasAudio != null && hasAudio != "yes") return "This file has no audio."
            if (ms < MIN_MS) return "This sound is ${ms} ms long. It must be at least 0.2 seconds."
            if (ms > MAX_MS) return "This sound is ${"%.1f".format(ms / 1000.0)} seconds long. It must be 2 seconds or less — trim it first."
        } catch (t: Throwable) {
            return "This file couldn't be opened as audio. Use MP3, OGG, WAV, M4A, AAC or FLAC."
        } finally {
            runCatching { mmr.release() }
        }
        return null
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()
}
