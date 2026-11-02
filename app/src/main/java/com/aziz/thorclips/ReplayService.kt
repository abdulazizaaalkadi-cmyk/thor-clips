package com.aziz.thorclips

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.Surface
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Keeps the last N seconds of the game screen(s) and game audio in memory and writes
 * them to Movies/DualClips when asked. Nothing touches storage until you save.
 *
 * With Shizuku: records either or both screens, and in Auto mode follows the game.
 * Without Shizuku (e.g. no Wi-Fi after a reboot): records the top screen only.
 */
class ReplayService : Service() {

    companion object {
        const val ACTION_START = "com.aziz.thorclips.START"
        const val ACTION_SAVE = "com.aziz.thorclips.SAVE"
        const val ACTION_STOP = "com.aziz.thorclips.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CLIP_FOLDER = "DualClips"
        private const val TAG = "DualClips"
        private const val CHANNEL = "thorclips"
        private const val NOTIF_ID = 1
        private const val DETECT_INTERVAL_MS = 4000L

        @Volatile var instance: ReplayService? = null
            private set
        @Volatile var status: String = "Off"
            private set
        @Volatile var recording: Boolean = false
            private set
        @Volatile var diagnostics: String = ""
            private set

        val userServiceArgs: Shizuku.UserServiceArgs by lazy {
            Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, CaptureUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("capture")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
        }

        fun shizukuReady(): Boolean = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private enum class Live { BOTH, TOP, BOTTOM }

    private lateinit var settings: Settings
    private lateinit var clipsPrefs: ClipsPrefs
    private lateinit var feedback: ClipFeedback
    private val main = Handler(Looper.getMainLooper())
    private val glThread = HandlerThread("tc-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val encThread = HandlerThread("tc-enc").apply { start() }
    private val encHandler = Handler(encThread.looper)
    private val detectThread = HandlerThread("tc-detect", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val detectHandler = Handler(detectThread.looper)
    private val saveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "tc-save")
    }

    private var running = false
    private var projection: MediaProjection? = null
    @Volatile private var capture: ICaptureService? = null
    private var boundShizuku = false
    private var useShizuku = false

    // Video pipeline (owned by the GL thread)
    private var compositor: Compositor? = null
    private var encoder: MediaCodec? = null
    private var codecMime = ""
    private val mirrorTokens = mutableListOf<Int>()
    private var projectionDisplay: VirtualDisplay? = null
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var videoRing: SampleRing? = null
    @Volatile private var live: Live? = null

    private var topId = Display.DEFAULT_DISPLAY
    private var bottomId: Int? = null
    private var topSize = Point()
    private var bottomSize: Point? = null

    // Auto detection
    private var pendingLive: Live? = null
    private var launchers: Set<String> = emptySet()
    @Volatile private var lastGame: String? = null
    private var screenOn = true

    private var audio: AudioCapture? = null
    @Volatile private var saving = false
    private var screenReceiver: BroadcastReceiver? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        clipsPrefs = ClipsPrefs(this)
        feedback = ClipFeedback(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) start(intent)
            ACTION_SAVE -> requestSave()
            ACTION_STOP -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) teardown()
        instance = null
        recording = false
        ClipTileService.refresh(this)
        glThread.quitSafely()
        encThread.quitSafely()
        detectThread.quitSafely()
        saveExecutor.shutdown()
        feedback.release()
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        running = true
        setStatus("Starting…")
        startInForeground()

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            fail("Screen-capture permission was not granted.")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)?.also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { if (running) shutdown() }
                }
            }, main)
        }
        launchers = findLaunchers()

        useShizuku = shizukuReady()
        if (useShizuku) {
            setStatus("Connecting to Shizuku…")
            boundShizuku = true
            Shizuku.bindUserService(userServiceArgs, shizukuConnection)
        } else {
            // No Shizuku (e.g. no Wi-Fi after a reboot): the top screen still works.
            glHandler.post { startAll() }
        }
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                useShizuku = false
                glHandler.post { startAll() }
                return
            }
            capture = ICaptureService.Stub.asInterface(binder)
            glHandler.post { startAll() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture = null
            if (running) {
                // Shizuku died (Wi-Fi/ADB dropped). Carry on with the top screen only.
                useShizuku = false
                glHandler.post {
                    stopVideo()
                    startVideo(Live.TOP)
                    main.post { toast("Shizuku stopped — recording top screen only") }
                }
            }
        }
    }

    private fun findLaunchers(): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = packageManager
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        return list.toSet() + setOf(packageName, "com.android.systemui", "android", "moe.shizuku.privileged.api")
    }

    // ---------------------------------------------------------------- pipeline

    /** GL thread: first start after Shizuku (or no-Shizuku) is decided. */
    private fun startAll() {
        if (!running) return
        try {
            val dm = getSystemService(DisplayManager::class.java)
            val top = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val bottom = dm.displays.firstOrNull {
                it.displayId != Display.DEFAULT_DISPLAY && !it.name.contains("thorclips", true) &&
                    !it.name.contains("dualclips", true)
            }
            topId = top.displayId
            topSize = Point().also { @Suppress("DEPRECATION") top.getRealSize(it) }
            bottomId = bottom?.displayId
            bottomSize = bottom?.let { d -> Point().also { @Suppress("DEPRECATION") d.getRealSize(it) } }

            val first = when {
                !useShizuku || bottomId == null -> Live.TOP
                settings.screenMode == ScreenMode.TOP -> Live.TOP
                settings.screenMode == ScreenMode.BOTTOM -> Live.BOTTOM
                else -> Live.BOTH
            }
            startVideo(first)

            if (settings.audio && projection != null &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    val windowUs = (settings.seconds + 3) * 1_000_000L
                    val aRing = SampleRing((128_000L / 8 * (settings.seconds + 3) * 3 / 2).toInt(), windowUs)
                    audio = AudioCapture(projection!!, aRing).also { it.start() }
                } catch (t: Throwable) {
                    Log.w(TAG, "audio unavailable", t)
                }
            }

            registerScreenReceiver()
            if (useShizuku && settings.screenMode == ScreenMode.AUTO && bottomId != null) {
                detectHandler.postDelayed(detectTask, 1500)
            }
            recording = true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            main.post { fail("Couldn't start: ${t.message}") }
        }
    }

    /** GL thread: build encoder + compositor + mirrors for the chosen screens. */
    private fun startVideo(mode: Live) {
        val sizes = ArrayList<Point>()
        val ids = ArrayList<Int>()
        val bId = bottomId
        val bSize = bottomSize
        when {
            mode == Live.BOTTOM && bId != null && bSize != null -> { sizes += bSize; ids += bId }
            mode == Live.BOTH && bId != null && bSize != null -> {
                sizes += topSize; ids += topId; sizes += bSize; ids += bId
            }
            else -> { sizes += topSize; ids += topId }
        }
        val actual = when {
            ids.size == 2 -> Live.BOTH
            ids[0] == topId -> Live.TOP
            else -> Live.BOTTOM
        }

        val layout = Layout.compute(sizes, settings.width)
        val enc = createEncoder(layout.outW, layout.outH)
        val input = enc.createInputSurface()
        val bitrate = if (codecMime == MediaFormat.MIMETYPE_VIDEO_HEVC) settings.bitrate else settings.bitrate * 3 / 2
        val windowUs = (settings.seconds + 3) * 1_000_000L
        val ring = SampleRing((bitrate.toLong() / 8 * (settings.seconds + 3) * 5 / 4).toInt(), windowUs)
        ring.onNeedKeyframe = { encHandler.post { requestKeyframe() } }
        videoFormat = null
        videoRing = ring
        enc.start()
        encoder = enc

        val comp = Compositor(input, layout, settings.fps, glHandler)
        compositor = comp
        if (!screenOn) comp.paused = true

        val cap = capture
        for ((i, id) in ids.withIndex()) {
            val r = layout.regions[i]
            val surface = comp.surfaces[i]
            if (useShizuku && cap != null) {
                val t = cap.startMirror(id, surface, r.w, r.h)
                if (t < 0) throw IllegalStateException("Couldn't mirror display $id.\n" + cap.info())
                mirrorTokens += t
            } else {
                mirrorWithProjection(surface, r.w, r.h)
            }
        }
        live = actual

        val diag = StringBuilder()
        getSystemService(DisplayManager::class.java).displays.forEach { d ->
            val p = Point(); @Suppress("DEPRECATION") d.getRealSize(p)
            diag.append("display ${d.displayId} \"${d.name}\" ${p.x}x${p.y}\n")
        }
        diag.append("recording: ${actual.name.lowercase()} · ${layout.outW}x${layout.outH} · $codecMime · ${bitrate / 1_000_000} Mbps\n")
        diag.append("shizuku: ${if (useShizuku) "on" else "off (top screen only)"} · mode ${settings.screenMode.label}\n")
        cap?.let { diag.append(runCatching { it.info() }.getOrDefault("")) }
        diagnostics = diag.toString()

        main.post {
            setStatus(statusText())
            updateNotification()
        }
    }

    private fun statusText(): String {
        val what = when (live) {
            Live.BOTH -> "both screens"
            Live.TOP -> "top screen"
            Live.BOTTOM -> "bottom screen"
            null -> "—"
        }
        val extra = if (!useShizuku) " (Shizuku off)" else if (settings.screenMode == ScreenMode.AUTO) " · auto" else ""
        return "Recording $what$extra"
    }

    /** No Shizuku: the normal screen-capture permission can mirror the top screen. */
    private fun mirrorWithProjection(surface: Surface, w: Int, h: Int) {
        val proj = projection ?: throw IllegalStateException("No screen-capture permission")
        val existing = projectionDisplay
        if (existing != null) {
            existing.resize(w, h, resources.displayMetrics.densityDpi)
            existing.surface = surface
            return
        }
        projectionDisplay = proj.createVirtualDisplay(
            "dualclips-top", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
    }

    /** GL thread: release encoder + compositor + mirrors (audio keeps running). */
    private fun stopVideo() {
        val cap = capture
        mirrorTokens.forEach { t -> runCatching { cap?.stopMirror(t) } }
        mirrorTokens.clear()
        runCatching { projectionDisplay?.surface = null }
        runCatching { compositor?.release() }
        compositor = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        videoRing = null
        videoFormat = null
        live = null
    }

    private fun createEncoder(w: Int, h: Int): MediaCodec {
        var last: Throwable? = null
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createEncoderByType(mime)
                val rate = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) settings.bitrate else settings.bitrate * 3 / 2
                val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, rate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // When the screen is static, the encoder repeats the last frame itself.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                }
                codec.setCallback(encoderCallback, encHandler)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codecMime = mime
                return codec
            } catch (t: Throwable) {
                last = t
                runCatching { codec?.release() }
            }
        }
        throw IllegalStateException("No video encoder works for ${w}x$h: ${last?.message}")
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (codec !== encoder) return
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        videoRing?.add(buf, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "encoder output", t)
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== encoder) return
            Log.e(TAG, "encoder error", e)
            main.post { fail("Video encoder error: ${e.diagnosticInfo}") }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codec === encoder) videoFormat = format
        }
    }

    private fun requestKeyframe() {
        runCatching {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    // ---------------------------------------------------------------- auto detection

    private val detectTask = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                if (screenOn) detectOnce()
            } catch (t: Throwable) {
                Log.w(TAG, "detect", t)
            }
            detectHandler.postDelayed(this, DETECT_INTERVAL_MS)
        }
    }

    /** Detect thread: which screen(s) is a game showing on? Switch after two matching readings. */
    private fun detectOnce() {
        val cap = capture ?: return
        val bId = bottomId ?: return
        val snap = cap.windowSnapshot()
        if (snap.isBlank()) return
        val appTypes = setOf("BASE_APPLICATION", "APPLICATION", "PRESENTATION", "PRIVATE_PRESENTATION", "?")
        fun gameOn(display: Int): String? = snap.lineSequence()
            .map { it.split('|') }
            .filter { it.size >= 3 && it[0].toIntOrNull() == display && it[2] in appTypes }
            .map { it[1] }
            .firstOrNull { it !in launchers }

        val t = gameOn(topId)
        val b = gameOn(bId)
        val want = when {
            t != null && b != null -> Live.BOTH
            t != null -> Live.TOP
            b != null -> Live.BOTTOM
            else -> null
        } ?: return
        lastGame = t ?: b

        if (want == live) { pendingLive = null; return }
        if (pendingLive != want) { pendingLive = want; return }
        pendingLive = null
        glHandler.post {
            if (!running || live == want) return@post
            stopVideo()
            try {
                startVideo(want)
                val label = when (want) { Live.BOTH -> "both screens"; Live.TOP -> "top screen"; Live.BOTTOM -> "bottom screen" }
                main.post { toast("Dual Clips: now recording $label") }
            } catch (e: Throwable) {
                main.post { fail("Couldn't switch screens: ${e.message}") }
            }
        }
    }

    private fun registerScreenReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> glHandler.post { setSuspended(true) }
                    Intent.ACTION_SCREEN_ON -> glHandler.post { setSuspended(false) }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        main.post {
            registerReceiver(r, f)
            screenReceiver = r
        }
    }

    private fun setSuspended(suspend: Boolean) {
        screenOn = !suspend
        compositor?.paused = suspend
        runCatching {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspend) 1 else 0) })
        }
        if (!suspend) requestKeyframe()
    }

    // ---------------------------------------------------------------- saving

    fun requestSave() {
        main.post { saveClip() }
    }

    private fun saveClip() {
        val ring = videoRing
        val vFmt = videoFormat
        if (!running || ring == null || vFmt == null) {
            toast("Clip buffer isn't running")
            return
        }
        if (saving) {
            toast("Still saving the last clip…")
            return
        }
        saving = true
        buzz()
        feedback.onPressed()
        val seconds = settings.seconds
        val newest = ring.newestPtsUs()
        val video = ring.pinFrom(newest - seconds * 1_000_000L, requireKey = true)
        if (video.isEmpty()) {
            ring.unpin()
            saving = false
            toast("Nothing recorded yet")
            return
        }
        val base = video.first().ptsUs
        val aCap = audio
        val aFmt = aCap?.format
        val aRing = aCap?.ring
        val aud = if (aFmt != null && aRing != null) aRing.pinFrom(base, requireKey = false) else emptyList()
        val game = lastGame

        saveExecutor.execute {
            var ok = false
            var err: String? = null
            var id = -1L
            try {
                id = writeMp4(ring, video, vFmt, aRing, aud, aFmt, base)
                if (game != null) clipsPrefs.setGame(id, game)
                ok = true
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                err = t.message
            } finally {
                ring.unpin()
                aRing?.unpin()
                saving = false
            }
            val len = ((video.last().ptsUs - base) / 1_000_000L).toInt()
            if (ok) feedback.onSaved(id, len) else feedback.onFailed(err ?: "unknown error")
        }
    }

    /** Returns the MediaStore id of the new clip. */
    private fun writeMp4(
        vRing: SampleRing, video: List<SampleRing.Sample>, vFmt: MediaFormat,
        aRing: SampleRing?, aud: List<SampleRing.Sample>, aFmt: MediaFormat?, base: Long,
    ): Long {
        val name = "DualClip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + CLIP_FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("couldn't create file")
        try {
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val vTrack = muxer.addTrack(vFmt)
                    val aTrack = if (aFmt != null && aud.isNotEmpty()) muxer.addTrack(aFmt) else -1
                    muxer.start()

                    val maxSize = maxOf(video.maxOf { it.size }, aud.maxOfOrNull { it.size } ?: 0)
                    val buf = ByteBuffer.allocateDirect(maxSize)
                    val info = MediaCodec.BufferInfo()
                    var vi = 0
                    var ai = 0
                    while (vi < video.size || (aTrack >= 0 && ai < aud.size)) {
                        val takeVideo = aTrack < 0 || ai >= aud.size ||
                            (vi < video.size && video[vi].ptsUs <= aud[ai].ptsUs)
                        if (takeVideo) {
                            val s = video[vi++]
                            vRing.read(s, buf)
                            info.set(0, s.size, s.ptsUs - base, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(vTrack, buf, info)
                        } else {
                            val s = aud[ai++]
                            if (s.ptsUs < base) continue
                            aRing!!.read(s, buf)
                            info.set(0, s.size, s.ptsUs - base, 0)
                            muxer.writeSampleData(aTrack, buf, info)
                        }
                    }
                    muxer.stop()
                } finally {
                    runCatching { muxer.release() }
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return ContentUris.parseId(uri)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    // ---------------------------------------------------------------- shutdown

    private fun shutdown() {
        if (running) teardown()
        setStatus("Off")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        toast(msg)
        if (running) teardown()
        setStatus("Error: $msg")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        running = false
        recording = false
        detectHandler.removeCallbacksAndMessages(null)
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        val aud = audio
        audio = null
        glHandler.post {
            stopVideo()
            runCatching { projectionDisplay?.release() }
            projectionDisplay = null
            runCatching { aud?.stop() }
        }
        if (boundShizuku) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, shizukuConnection, true) }
            boundShizuku = false
        }
        capture = null
        glHandler.post {
            runCatching { projection?.stop() }
            projection = null
        }
    }

    // ---------------------------------------------------------------- UI bits

    private fun setStatus(s: String) {
        status = s
        MainActivity.refresh()
        ClipTileService.refresh(this)
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun buzz() {
        runCatching {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Clip buffer", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val save = PendingIntent.getService(this, 1, Intent(this, ReplayService::class.java).setAction(ACTION_SAVE), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, ReplayService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Dual Clips")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Save clip", save).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
