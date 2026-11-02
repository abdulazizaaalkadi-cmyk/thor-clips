package com.aziz.thorclips

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import java.nio.ByteBuffer

data class Clip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val dateSec: Long,
    val sizeBytes: Long,
    val isImage: Boolean = false,
) {
    val mime get() = if (isImage) "image/png" else "video/mp4"
}

/** Reading, renaming, deleting and trimming clips in Movies/DualClips (and the old ThorClips folder). */
object ClipStore {

    fun list(ctx: Context, prefs: ClipsPrefs): List<Clip> {
        val out = ArrayList<Clip>()
        val f = prefs.filter
        if (f != LibraryFilter.SHOTS) out += query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies", false)
        if (f != LibraryFilter.CLIPS) out += query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures", true)
        val favs = prefs.favourites
        val sorted = when (prefs.sort) {
            SortOrder.NEWEST -> out.sortedByDescending { it.dateSec }
            SortOrder.OLDEST -> out.sortedBy { it.dateSec }
            SortOrder.LONGEST -> out.sortedByDescending { it.durationMs }
        }
        // Favourites always float to the top.
        return sorted.sortedByDescending { it.id.toString() in favs }
    }

    private fun query(ctx: Context, table: Uri, root: String, images: Boolean): List<Clip> {
        val out = ArrayList<Clip>()
        val projection = arrayListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
        )
        if (!images) projection += MediaStore.Video.Media.DURATION
        ctx.contentResolver.query(
            table,
            projection.toTypedArray(),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%$root/${ReplayService.CLIP_FOLDER}%", "%$root/ThorClips%"),
            null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDur = if (images) -1 else c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += Clip(
                    id,
                    ContentUris.withAppendedId(table, id),
                    c.getString(iName) ?: (if (images) "shot.png" else "clip.mp4"),
                    if (iDur >= 0) c.getLong(iDur) else 0L,
                    c.getLong(iDate),
                    c.getLong(iSize),
                    images,
                )
            }
        }
        return out
    }

    fun title(c: Clip) = c.name.substringBeforeLast('.')

    fun meta(ctx: Context, c: Clip): String {
        val ago = DateUtils.getRelativeTimeSpanString(c.dateSec * 1000)
        val kind = if (c.isImage) "Screenshot" else "${c.durationMs / 1000}s"
        return "$kind · $ago · ${Formatter.formatShortFileSize(ctx, c.sizeBytes)}"
    }

    fun rename(ctx: Context, c: Clip, newName: String): Boolean {
        val clean = newName.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty { return false }
        val ext = if (c.isImage) "png" else "mp4"
        val v = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "$clean.$ext") }
        return ctx.contentResolver.update(c.uri, v, null, null) > 0
    }

    /** Deletes directly if we own the file; otherwise asks Android to confirm. */
    fun delete(a: Activity, c: Clip, requestCode: Int): Boolean {
        return try {
            a.contentResolver.delete(c.uri, null, null) > 0
        } catch (e: SecurityException) {
            val pi = MediaStore.createDeleteRequest(a.contentResolver, listOf(c.uri))
            a.startIntentSenderForResult(pi.intentSender, requestCode, null, 0, 0, 0)
            false
        }
    }

    fun share(a: Activity, c: Clip) {
        val i = Intent(Intent.ACTION_SEND).setType(c.mime)
            .putExtra(Intent.EXTRA_STREAM, c.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, if (c.isImage) "Share screenshot" else "Share clip"))
    }

    fun shareMany(a: Activity, list: List<Clip>) {
        if (list.isEmpty()) return
        if (list.size == 1) { share(a, list[0]); return }
        val type = when {
            list.all { it.isImage } -> "image/*"
            list.none { it.isImage } -> "video/*"
            else -> "*/*"
        }
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType(type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list.map { it.uri }))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, "Share ${list.size} items"))
    }

    /** Deletes what it can directly; anything else goes to Android's confirm prompt. Returns how many were deleted now. */
    fun deleteMany(a: Activity, list: List<Clip>, requestCode: Int): Int {
        var done = 0
        val needAsk = ArrayList<Uri>()
        for (c in list) {
            try {
                if (a.contentResolver.delete(c.uri, null, null) > 0) done++
            } catch (e: SecurityException) {
                needAsk += c.uri
            }
        }
        if (needAsk.isNotEmpty()) {
            val pi = MediaStore.createDeleteRequest(a.contentResolver, needAsk)
            a.startIntentSenderForResult(pi.intentSender, requestCode, null, 0, 0, 0)
        }
        return done
    }

    // ---------------------------------------------------------------- SD card export

    const val EXPORT_FOLDER = "DCIM/DualClips"

    /** The MediaStore name of a mounted SD card, or null if there isn't one. */
    fun sdCardVolume(ctx: Context): Pair<String, String>? {
        val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java) ?: return null
        val v = sm.storageVolumes.firstOrNull {
            it.isRemovable && it.state == Environment.MEDIA_MOUNTED
        } ?: return null
        val name = v.mediaStoreVolumeName ?: return null
        return name to v.getDescription(ctx)
    }

    /**
     * Copies (or moves) clips and screenshots to SD card → DCIM/DualClips.
     * Returns (exported, failed).
     */
    fun exportToSd(ctx: Context, volume: String, list: List<Clip>, move: Boolean, progress: (Int) -> Unit): Pair<Int, Int> {
        var ok = 0
        var bad = 0
        val r = ctx.contentResolver
        for ((i, c) in list.withIndex()) {
            progress(i)
            val table = if (c.isImage) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Video.Media.getContentUri(volume)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, c.name)
                put(MediaStore.MediaColumns.MIME_TYPE, c.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, EXPORT_FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            var dest: Uri? = null
            try {
                dest = r.insert(table, values) ?: throw IllegalStateException("couldn't create file on SD card")
                r.openInputStream(c.uri)!!.use { input ->
                    r.openOutputStream(dest)!!.use { out -> input.copyTo(out, 1 shl 20) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                r.update(dest, values, null, null)
                if (move) runCatching { r.delete(c.uri, null, null) }
                ok++
            } catch (t: Throwable) {
                dest?.let { runCatching { r.delete(it, null, null) } }
                bad++
            }
        }
        progress(list.size)
        return ok to bad
    }

    fun play(a: Activity, c: Clip) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, c.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { a.startActivity(i) }
    }

    // ---------------------------------------------------------------- editors

    data class Editor(val pkg: String, val cls: String, val action: String, val label: String)

    /** Apps that can edit a video (ACTION_EDIT), plus apps that accept one (ACTION_SEND) as a fallback. */
    fun editors(ctx: Context, mime: String = "video/mp4"): List<Editor> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<Editor>()
        for (action in listOf(Intent.ACTION_EDIT, Intent.ACTION_SEND)) {
            val i = Intent(action).setType(mime)
            @Suppress("DEPRECATION")
            for (ri in pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                val label = ri.loadLabel(pm).toString()
                // Share-only targets are mostly chat apps; keep the ones that look like editors.
                if (action == Intent.ACTION_SEND && !looksLikeEditor(pkg, label)) continue
                out += Editor(pkg, ri.activityInfo.name, action, label)
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun looksLikeEditor(pkg: String, label: String): Boolean {
        val s = (pkg + " " + label).lowercase()
        return listOf("edit", "capcut", "lemon", "vn", "inshot", "kinemaster", "powerdirector", "cut", "video", "movie", "studio",
            "photo", "snapseed", "lightroom", "picsart", "canva", "gallery", "markup")
            .any { s.contains(it) }
    }

    fun openInEditor(a: Activity, c: Clip, e: Editor) {
        val i = Intent(e.action).setClassName(e.pkg, e.cls)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (e.action == Intent.ACTION_EDIT) i.setDataAndType(c.uri, c.mime)
        else i.setType(c.mime).putExtra(Intent.EXTRA_STREAM, c.uri)
        a.startActivity(i)
    }

    fun encodeEditor(e: Editor) = "${e.pkg}|${e.cls}|${e.action}|${e.label}"
    fun decodeEditor(s: String?): Editor? {
        val p = s?.split('|') ?: return null
        return if (p.size == 4) Editor(p[0], p[1], p[2], p[3]) else null
    }

    // ---------------------------------------------------------------- info

    fun info(ctx: Context, c: Clip, prefs: ClipsPrefs): String {
        var res = "?"
        if (c.isImage) {
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(c.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                res = "${o.outWidth} × ${o.outHeight}"
            }
        }
        if (!c.isImage) {
            val mmr = MediaMetadataRetriever()
            try {
            mmr.setDataSource(ctx, c.uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            res = "$w × $h"
        } catch (_: Throwable) {
        } finally {
            runCatching { mmr.release() }
        }
        }
        val game = prefs.gameFor(c.id)?.let { pkg ->
            runCatching {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        } ?: "Unknown"
        val date = DateUtils.formatDateTime(
            ctx, c.dateSec * 1000,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
        )
        val length = if (c.isImage) "Screenshot" else "${c.durationMs / 1000}s"
        return "Name: ${title(c)}\nGame: $game\nLength: $length\n" +
            "Size: ${Formatter.formatFileSize(ctx, c.sizeBytes)}\nResolution: $res\nSaved: $date"
    }

    // ---------------------------------------------------------------- trim

    /**
     * Copies [startMs]..[endMs] into a new clip without re-encoding (fast, no quality loss).
     * The start snaps back to the nearest keyframe (at most ~1 s earlier).
     */
    fun trim(ctx: Context, c: Clip, startMs: Long, endMs: Long): Uri {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, c.uri, null)
        val name = title(c) + "_trim.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ReplayService.CLIP_FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val out = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("couldn't create file")
        try {
            resolver.openFileDescriptor(out, "rw")!!.use { pfd ->
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val map = HashMap<Int, Int>()
                    var maxIn = 1 shl 20
                    var videoTrack = -1
                    for (t in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(t)
                        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                        if (mime.startsWith("video/")) videoTrack = t
                        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            maxIn = maxOf(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                        ex.selectTrack(t)
                        map[t] = muxer.addTrack(f)
                    }
                    muxer.start()
                    val startUs = startMs * 1000
                    val endUs = endMs * 1000
                    ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var base = -1L
                    val buf = ByteBuffer.allocateDirect(maxIn * 2)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val track = ex.sampleTrackIndex
                        if (track < 0) break
                        val t = ex.sampleTime
                        if (t > endUs) break
                        if (base < 0) {
                            // Start the file on the first video keyframe.
                            if (track != videoTrack && videoTrack >= 0) { ex.advance(); continue }
                            base = t
                        }
                        if (t < base) { ex.advance(); continue }
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        info.set(0, size, t - base, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(map[track]!!, buf, info)
                        ex.advance()
                    }
                    muxer.stop()
                } finally {
                    runCatching { muxer.release() }
                    runCatching { ex.release() }
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(out, values, null, null)
            return out
        } catch (t: Throwable) {
            runCatching { resolver.delete(out, null, null) }
            throw t
        }
    }
}
