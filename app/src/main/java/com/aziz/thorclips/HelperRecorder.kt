package com.aziz.thorclips

import android.graphics.Bitmap
import android.graphics.Point
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The actual recorder. Runs inside the Shizuku helper process (shell user), which
 * Android doesn't close when a game needs memory, so recording survives even if the
 * Dual Clips app itself gets closed.
 *
 * It mirrors the screen(s), stacks them with OpenGL, encodes with the hardware encoder
 * into a file-backed ring, follows which screen(s) are awake / show a game, and on
 * request writes the last N seconds (video only) to a file the app hands it.
 * The app adds game audio afterwards.
 */
class HelperRecorder(
    private val context: () -> android.content.Context?,
    private val mirror: (displayId: Int, surface: Surface, w: Int, h: Int) -> Int,
    private val unmirror: (token: Int) -> Unit,
    private val windows: () -> String,
) {
    private enum class Live { BOTH, TOP, BOTTOM }

    // Config (from the app)
    private var width = 1280
    private var bitrate = 9_000_000
    private var fps = 60
    private var seconds = 30
    private var mode = "AUTO"
    private var launchers: Set<String> = emptySet()

    private val gl = HandlerThread("hr-gl").apply { start() }
    private val glH = Handler(gl.looper)
    private val enc = HandlerThread("hr-enc").apply { start() }
    private val encH = Handler(enc.looper)
    private val det = HandlerThread("hr-detect").apply { start() }
    private val detH = Handler(det.looper)

    @Volatile private var running = false
    @Volatile private var live: Live? = null
    @Volatile private var error = ""
    @Volatile private var game = ""
    private var compositor: Compositor? = null
    private var encoder: MediaCodec? = null
    private var codecMime = ""
    private var layoutDesc = ""
    private val tokens = ArrayList<Int>()
    @Volatile private var ring: SampleRing? = null
    @Volatile private var audio: HelperAudio? = null
    @Volatile private var audioState = "off"
    private var wantAudio = true
    private var appUid = -1
    @Volatile private var format: MediaFormat? = null

    private var topId = 0
    private var bottomId: Int? = null
    private var topSize = Point(1920, 1080)
    private var bottomSize: Point? = null
    private var pending: Live? = null
    private var lastReading = ""
    private var lastWindowCheck = 0L
    private var suspended = false
    private val ringDir = File("/data/local/tmp/dualclips").apply { mkdirs() }

    private val log = ArrayDeque<String>()
    private fun note(s: String) = synchronized(log) {
        log.addLast("[helper] $s")
        while (log.size > 60) log.removeFirst()
    }

    fun takeLog(): String = synchronized(log) { val t = log.joinToString("\n"); log.clear(); t }

    fun status(): String =
        "state=${if (running) "recording" else if (error.isNotEmpty()) "error" else "idle"};" +
            "live=${live?.name ?: ""};layout=$layoutDesc;codec=$codecMime;game=$game;" +
            "seconds=$seconds;suspended=$suspended;audio=$audioState;" +
            "level=${"%.4f".format(audio?.level ?: 0.0)};err=${error.replace(';', ',')}"

    // ------------------------------------------------------------------ start / stop

    fun start(cfg: Bundle): Int {
        width = cfg.getInt("width", 1280)
        bitrate = cfg.getInt("bitrate", 9_000_000)
        fps = cfg.getInt("fps", 60)
        seconds = cfg.getInt("seconds", 30)
        mode = cfg.getString("mode", "AUTO") ?: "AUTO"
        wantAudio = cfg.getBoolean("audio", true)
        appUid = cfg.getInt("appUid", -1)
        launchers = (cfg.getStringArray("launchers") ?: emptyArray()).toSet() +
            setOf("com.android.systemui", "android", "moe.shizuku.privileged.api", "com.aziz.thorclips")
        val done = CountDownLatch(1)
        var result = -1
        glH.post {
            try {
                if (running) stopVideo()
                findDisplays()
                val first = when {
                    bottomId == null -> Live.TOP
                    mode == "TOP" -> Live.TOP
                    mode == "BOTTOM" -> Live.BOTTOM
                    else -> Live.BOTH
                }
                startVideo(first)
                running = true
                error = ""
                result = 0
                startAudio()
                note("started mode=$mode ${width}w ${fps}fps ${seconds}s top=$topId bottom=${bottomId ?: "-"}")
                if (mode == "AUTO" && bottomId != null) {
                    detH.removeCallbacksAndMessages(null)
                    detH.postDelayed(detectTask, 800)
                }
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
                note("start failed: $error")
                runCatching { stopVideo() }
            } finally {
                done.countDown()
            }
        }
        done.await(10, TimeUnit.SECONDS)
        return result
    }

    private fun startAudio() {
        runCatching { audio?.stop() }
        audio = null
        if (!wantAudio) { audioState = "off"; return }
        try {
            val r = SampleRing.fileBacked(ringDir, "audio", (128_000L / 8 * (seconds + 3) * 3 / 2).toInt(), (seconds + 3) * 1_000_000L)
            audio = HelperAudio(r, appUid) { note(it) }.also { it.start(context()) }
            audioState = "on"
            note("game audio capture on")
        } catch (t: Throwable) {
            audioState = "failed"
            note("game audio failed: ${t.cause?.message ?: t.message}")
        }
    }

    fun stop() {
        runCatching { audio?.stop() }
        audio = null
        audioState = "off"
        running = false
        detH.removeCallbacksAndMessages(null)
        val done = CountDownLatch(1)
        glH.post { runCatching { stopVideo() }; done.countDown() }
        done.await(5, TimeUnit.SECONDS)
        note("stopped")
    }

    // ------------------------------------------------------------------ displays

    private class DInfo(val id: Int, val name: String, val w: Int, val h: Int, val state: Int, val type: Int)

    private fun displayInfo(id: Int): DInfo? = try {
        val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val inst = dmg.getMethod("getInstance").invoke(null)
        val info = dmg.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(inst, id) ?: null
        if (info == null) null else {
            val c = info.javaClass
            DInfo(
                id,
                (c.getField("name").get(info) as? String) ?: "",
                c.getField("logicalWidth").getInt(info),
                c.getField("logicalHeight").getInt(info),
                runCatching { c.getField("state").getInt(info) }.getOrDefault(2),
                runCatching { c.getField("type").getInt(info) }.getOrDefault(1),
            )
        }
    } catch (t: Throwable) {
        null
    }

    private fun displayIds(): IntArray = try {
        val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val inst = dmg.getMethod("getInstance").invoke(null)
        dmg.getMethod("getDisplayIds").invoke(inst) as IntArray
    } catch (t: Throwable) {
        intArrayOf(0)
    }

    private fun findDisplays() {
        val top = displayInfo(0)
        topId = 0
        if (top != null) topSize = Point(top.w, top.h)
        val others = displayIds().filter { it != 0 }.mapNotNull { displayInfo(it) }
            .filter { !it.name.contains("thorclips", true) && !it.name.contains("dualclips", true) }
        // Prefer a built-in panel (type 1 = internal).
        val b = others.firstOrNull { it.type == 1 } ?: others.firstOrNull()
        bottomId = b?.id
        bottomSize = b?.let { Point(it.w, it.h) }
    }

    /** 2 = STATE_ON, 5 = STATE_VR. Anything else counts as asleep. */
    private fun awake(id: Int): Boolean = displayInfo(id)?.state.let { it == 2 || it == 5 }

    // ------------------------------------------------------------------ video

    /** GL thread. */
    private fun startVideo(want: Live) {
        val sizes = ArrayList<Point>()
        val ids = ArrayList<Int>()
        val bId = bottomId
        val bSize = bottomSize
        when {
            want == Live.BOTTOM && bId != null && bSize != null -> { sizes += bSize; ids += bId }
            want == Live.BOTH && bId != null && bSize != null -> { sizes += topSize; ids += topId; sizes += bSize; ids += bId }
            else -> { sizes += topSize; ids += topId }
        }
        val actual = when { ids.size == 2 -> Live.BOTH; ids[0] == topId -> Live.TOP; else -> Live.BOTTOM }
        val layout = Layout.compute(sizes, width)
        val codec = createEncoder(layout.outW, layout.outH)
        val input = codec.createInputSurface()
        val rate = if (codecMime == MediaFormat.MIMETYPE_VIDEO_HEVC) bitrate else bitrate * 3 / 2
        val windowUs = (seconds + 3) * 1_000_000L
        val r = SampleRing.fileBacked(ringDir, "video", (rate.toLong() / 8 * (seconds + 3) * 5 / 4).toInt(), windowUs)
        r.onNeedKeyframe = { encH.post { requestKeyframe() } }
        format = null
        ring = r
        codec.start()
        encoder = codec
        val comp = Compositor(input, layout, fps, glH)
        compositor = comp
        for ((i, id) in ids.withIndex()) {
            val reg = layout.regions[i]
            val t = mirror(id, comp.surfaces[i], reg.w, reg.h)
            if (t < 0) throw IllegalStateException("couldn't mirror display $id")
            tokens += t
        }
        live = actual
        layoutDesc = "${layout.outW}x${layout.outH}"
        note("recording ${actual.name} $layoutDesc $codecMime")
    }

    /** GL thread. */
    private fun stopVideo() {
        tokens.forEach { runCatching { unmirror(it) } }
        tokens.clear()
        runCatching { compositor?.release() }
        compositor = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        ring = null
        format = null
        live = null
    }

    private fun createEncoder(w: Int, h: Int): MediaCodec {
        var last: Throwable? = null
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createEncoderByType(mime)
                val rate = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) bitrate else bitrate * 3 / 2
                val f = MediaFormat.createVideoFormat(mime, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, rate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                }
                c.setCallback(callback, encH)
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codecMime = mime
                return c
            } catch (t: Throwable) {
                last = t
                runCatching { c?.release() }
            }
        }
        throw IllegalStateException("no encoder for ${w}x$h: ${last?.message}")
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (codec !== encoder) return
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    codec.getOutputBuffer(index)?.let { b ->
                        b.position(info.offset); b.limit(info.offset + info.size)
                        ring?.add(b, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== encoder) return
            note("encoder error ${e.diagnosticInfo}")
            if (!e.isTransient) glH.post { rebuild(live ?: Live.TOP, "encoder error") }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, f: MediaFormat) {
            if (codec === encoder) format = f
        }
    }

    private fun requestKeyframe() {
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private var rebuilds = 0

    /** GL thread: switch screens, or recover from an error (falls back to top only). */
    private fun rebuild(want: Live, why: String) {
        if (!running) return
        stopVideo()
        for (m in listOf(want, Live.TOP).distinct()) {
            try {
                startVideo(m)
                note("$why → ${m.name}")
                return
            } catch (t: Throwable) {
                note("$why: ${m.name} failed: ${t.message}")
                stopVideo()
            }
        }
        if (++rebuilds > 6) { running = false; error = "recording kept failing ($why)" }
    }

    // ------------------------------------------------------------------ detection

    private val detectTask = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { detect() }.onFailure { note("detect: ${it.message}") }
            detH.postDelayed(this, 700)
        }
    }

    /**
     * Every 0.7 s: screen sleep states (cheap). Every 4 s: which app is on each screen.
     * A sleeping screen is dropped straight away; game changes need two matching readings.
     */
    private fun detect() {
        val bId = bottomId ?: return
        val topAwake = awake(topId)
        val botAwake = awake(bId)

        // Both asleep: pause the encoder so it doesn't burn power.
        val allAsleep = !topAwake && !botAwake
        if (allAsleep != suspended) {
            suspended = allAsleep
            glH.post {
                compositor?.paused = allAsleep
                runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (allAsleep) 1 else 0) }) }
                if (!allAsleep) requestKeyframe()
            }
            note(if (allAsleep) "both screens asleep: paused" else "screen on: resumed")
        }
        if (allAsleep) return

        val now = System.currentTimeMillis()
        val sleepRule = !topAwake || !botAwake
        var t: String? = null
        var b: String? = null
        if (!sleepRule && now - lastWindowCheck >= 4000) {
            lastWindowCheck = now
            val snap = runCatching { windows() }.getOrDefault("")
            val types = setOf("BASE_APPLICATION", "APPLICATION", "PRESENTATION", "PRIVATE_PRESENTATION", "?")
            fun on(d: Int) = snap.lineSequence().map { it.split('|') }
                .filter { it.size >= 3 && it[0].toIntOrNull() == d && it[2] in types }
                .map { it[1] }.firstOrNull { it !in launchers }
            t = on(topId); b = on(bId)
            if (t != null || b != null) game = t ?: b ?: ""
        } else if (!sleepRule) {
            return // nothing new to decide until the next window check
        }

        val want: Live? = when {
            topAwake && !botAwake -> Live.TOP
            !topAwake && botAwake -> Live.BOTTOM
            t != null && b != null -> Live.BOTH
            t != null -> Live.TOP
            b != null -> Live.BOTTOM
            else -> null
        }
        val reading = "awake top=$topAwake bottom=$botAwake game top=${t ?: "-"} bottom=${b ?: "-"} → ${want ?: "keep"}"
        if (reading != lastReading && (t != null || b != null || sleepRule)) { note(reading); lastReading = reading }
        if (want == null || want == live) { pending = null; return }
        if (!sleepRule && pending != want) { pending = want; return }
        pending = null
        glH.post { if (running && live != want) rebuild(want, "switch") }
    }

    // ------------------------------------------------------------------ saving

    /**
     * Writes the last [secs] seconds of video into [out] (MP4, video only).
     * Returns the absolute start time in µs (System.nanoTime()/1000 clock) so the app
     * can line up its game audio, or -1 on failure.
     */
    fun saveClip(out: ParcelFileDescriptor, secs: Int): Long {
        val r = ring
        val f = format
        if (!running || r == null || f == null) { note("save: not ready"); out.close(); return -1 }
        val samples = r.pinFrom(r.newestPtsUs() - secs * 1_000_000L, requireKey = true)
        if (samples.isEmpty()) { r.unpin(); out.close(); return -1 }
        val base = samples.first().ptsUs
        val a = audio
        val aFmt = a?.format
        val aRing = a?.ringRef
        val aud = if (aFmt != null && aRing != null) aRing.pinFrom(base, requireKey = false).filter { it.ptsUs >= base } else emptyList()
        try {
            val muxer = MediaMuxer(out.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val track = muxer.addTrack(f)
                val aTrack = if (aFmt != null && aud.isNotEmpty()) muxer.addTrack(aFmt) else -1
                muxer.start()
                val buf = ByteBuffer.allocateDirect(maxOf(samples.maxOf { it.size }, aud.maxOfOrNull { it.size } ?: 0))
                val info = MediaCodec.BufferInfo()
                val endUs = samples.last().ptsUs
                var ai = 0
                for (s in samples) {
                    // Interleave audio that belongs before this video frame.
                    while (aTrack >= 0 && ai < aud.size && aud[ai].ptsUs <= s.ptsUs) {
                        val sm = aud[ai++]
                        aRing!!.read(sm, buf)
                        info.set(0, sm.size, sm.ptsUs - base, 0)
                        muxer.writeSampleData(aTrack, buf, info)
                    }
                    r.read(s, buf)
                    info.set(0, s.size, s.ptsUs - base, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, buf, info)
                }
                while (aTrack >= 0 && ai < aud.size && aud[ai].ptsUs <= endUs + 100_000) {
                    val sm = aud[ai++]
                    aRing!!.read(sm, buf)
                    info.set(0, sm.size, sm.ptsUs - base, 0)
                    muxer.writeSampleData(aTrack, buf, info)
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
                aRing?.unpin()
            }
            note("saved ${(samples.last().ptsUs - base) / 1_000_000}s · audio ${if (aud.isEmpty()) "none" else "level %.3f".format(a?.level ?: 0.0)}")
            return base
        } catch (t: Throwable) {
            note("save failed: ${t.message}")
            return -1
        } finally {
            r.unpin()
            runCatching { out.close() }
        }
    }

    /** Writes the current frame of the recorded screen(s) as PNG into [out]. */
    fun screenshot(out: ParcelFileDescriptor): Boolean {
        val got = AtomicReference<Bitmap?>()
        val done = CountDownLatch(1)
        glH.post {
            runCatching { got.set(compositor?.capture()) }.onFailure { note("screenshot: ${it.message}") }
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        val bmp = got.get() ?: run { out.close(); return false }
        return try {
            FileOutputStream(out.fileDescriptor).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (t: Throwable) {
            note("screenshot write: ${t.message}")
            false
        } finally {
            bmp.recycle()
            runCatching { out.close() }
        }
    }
}
