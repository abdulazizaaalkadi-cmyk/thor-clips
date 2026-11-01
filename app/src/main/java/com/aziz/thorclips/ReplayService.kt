package com.aziz.thorclips

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
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
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Keeps the last N seconds of both screens (and game audio) in memory and writes
 * them to Movies/ThorClips when asked. Nothing touches storage until you save.
 */
class ReplayService : Service() {

    companion object {
        const val ACTION_START = "com.aziz.thorclips.START"
        const val ACTION_SAVE = "com.aziz.thorclips.SAVE"
        const val ACTION_STOP = "com.aziz.thorclips.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val TAG = "ThorClips"
        private const val CHANNEL = "thorclips"
        private const val NOTIF_ID = 1

        @Volatile var instance: ReplayService? = null
            private set
        @Volatile var status: String = "Stopped"
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
    }

    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private val glThread = HandlerThread("tc-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val encThread = HandlerThread("tc-enc").apply { start() }
    private val encHandler = Handler(encThread.looper)
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

    private var compositor: Compositor? = null
    private var encoder: MediaCodec? = null
    private var codecMime = ""
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var videoRing: SampleRing? = null
    private var audio: AudioCapture? = null
    private val mirrorTokens = mutableListOf<Int>()
    @Volatile private var saving = false
    private var screenReceiver: BroadcastReceiver? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
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
        glThread.quitSafely()
        encThread.quitSafely()
        saveExecutor.shutdown()
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

        if (!Shizuku.pingBinder()) {
            fail("Shizuku isn't running. Open Shizuku and start it, then try again.")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            fail("Thor Clips doesn't have Shizuku permission yet.")
            return
        }
        setStatus("Connecting to Shizuku…")
        boundShizuku = true
        Shizuku.bindUserService(userServiceArgs, shizukuConnection)
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                fail("Shizuku capture service didn't start.")
                return
            }
            capture = ICaptureService.Stub.asInterface(binder)
            glHandler.post { startPipeline() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture = null
            if (running) main.post { fail("Shizuku capture service stopped (was Shizuku turned off?).") }
        }
    }

    // ---------------------------------------------------------------- pipeline

    /** Runs on the GL thread. */
    private fun startPipeline() {
        if (!running) return
        try {
            val cap = capture ?: throw IllegalStateException("no capture service")
            val dm = getSystemService(DisplayManager::class.java)
            val top = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val bottom = dm.displays.firstOrNull {
                it.displayId != Display.DEFAULT_DISPLAY && !it.name.contains("thorclips", ignoreCase = true)
            }
            val topSize = Point().also { @Suppress("DEPRECATION") top.getRealSize(it) }
            val botSize = bottom?.let { d -> Point().also { @Suppress("DEPRECATION") d.getRealSize(it) } }

            val diag = StringBuilder()
            dm.displays.forEach { d ->
                val p = Point(); @Suppress("DEPRECATION") d.getRealSize(p)
                diag.append("display ${d.displayId} \"${d.name}\" ${p.x}x${p.y}\n")
            }

            val layout = Layout.compute(topSize, botSize, settings.width)
            val enc = createEncoder(layout.outW, layout.outH)
            val input = enc.createInputSurface()

            val bitrate = if (codecMime == MediaFormat.MIMETYPE_VIDEO_HEVC) settings.bitrate else settings.bitrate * 3 / 2
            val windowUs = (settings.seconds + 3) * 1_000_000L
            val ring = SampleRing((bitrate.toLong() / 8 * (settings.seconds + 3) * 5 / 4).toInt(), windowUs)
            ring.onNeedKeyframe = { encHandler.post { requestKeyframe() } }
            videoRing = ring

            enc.start()
            encoder = enc

            val comp = Compositor(input, layout, settings.fps, glHandler)
            compositor = comp

            val t1 = cap.startMirror(top.displayId, comp.topSurface, layout.topW, layout.topH)
            if (t1 < 0) throw IllegalStateException("Couldn't mirror the top screen.\n" + cap.info())
            mirrorTokens += t1
            if (bottom != null && comp.botSurface != null) {
                val t2 = cap.startMirror(bottom.displayId, comp.botSurface, layout.botW, layout.botH)
                if (t2 < 0) throw IllegalStateException("Couldn't mirror the bottom screen.\n" + cap.info())
                mirrorTokens += t2
            }

            if (settings.audio && projection != null &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    val aRing = SampleRing((128_000L / 8 * (settings.seconds + 3) * 3 / 2).toInt(), windowUs)
                    audio = AudioCapture(projection!!, aRing).also { it.start() }
                } catch (t: Throwable) {
                    Log.w(TAG, "audio unavailable", t)
                    diag.append("audio: off (${t.message})\n")
                }
            }

            registerScreenReceiver()
            diag.append("output ${layout.outW}x${layout.outH} $codecMime ${bitrate / 1_000_000} Mbps\n")
            diag.append(cap.info())
            diagnostics = diag.toString()

            val screens = if (bottom != null) "both screens" else "top screen only (no 2nd display found)"
            main.post {
                setStatus("Recording ${settings.seconds}s buffer · $screens · L3+R3 to clip")
                updateNotification()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            main.post { fail("Couldn't start: ${t.message}") }
        }
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
                    // When the screen is static, the encoder repeats the last frame itself
                    // instead of us redrawing it.
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
            Log.e(TAG, "encoder error", e)
            main.post { fail("Video encoder error: ${e.diagnosticInfo}") }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            videoFormat = format
        }
    }

    private fun requestKeyframe() {
        runCatching {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    private fun setSuspended(suspend: Boolean) {
        compositor?.paused = suspend
        runCatching {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspend) 1 else 0) })
        }
        if (!suspend) requestKeyframe()
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

        saveExecutor.execute {
            var ok = false
            var err: String? = null
            try {
                writeMp4(ring, video, vFmt, aRing, aud, aFmt, base)
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
            main.post { toast(if (ok) "Clip saved (${len}s) → Movies/ThorClips" else "Clip failed: $err") }
        }
    }

    private fun writeMp4(
        vRing: SampleRing, video: List<SampleRing.Sample>, vFmt: MediaFormat,
        aRing: SampleRing?, aud: List<SampleRing.Sample>, aFmt: MediaFormat?, base: Long,
    ) {
        val name = "ThorClip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ThorClips")
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
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    // ---------------------------------------------------------------- shutdown

    private fun shutdown() {
        if (running) teardown()
        setStatus("Stopped")
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
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        val cap = capture
        val tokens = mirrorTokens.toList()
        mirrorTokens.clear()
        val comp = compositor
        val enc = encoder
        val aud = audio
        compositor = null
        encoder = null
        audio = null
        videoRing = null
        videoFormat = null
        glHandler.post {
            tokens.forEach { t -> runCatching { cap?.stopMirror(t) } }
            runCatching { comp?.release() }
            runCatching { enc?.stop() }
            runCatching { enc?.release() }
            runCatching { aud?.stop() }
        }
        if (boundShizuku) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, shizukuConnection, true) }
            boundShizuku = false
        }
        capture = null
        runCatching { projection?.stop() }
        projection = null
    }

    // ---------------------------------------------------------------- UI bits

    private fun setStatus(s: String) {
        status = s
        MainActivity.refresh()
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
            .setContentTitle("Thor Clips")
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
