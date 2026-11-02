package com.aziz.thorclips

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * What you see and hear when a clip is saved, Medal-style:
 *  - a short chime the moment you press L3 + R3
 *  - a small pop-up card over the game ("Clip saved · 60s"), tap to open My Clips
 *  - a notification with Open / Share, in case you missed the pop-up
 */
class ClipFeedback(private val ctx: Context) {

    companion object {
        private const val CHANNEL = "clip_saved"
        private const val NOTIF_ID = 2

        /** key, label, sound file */
        val SOUNDS = listOf(
            Triple("soft", "Soft chime", R.raw.snd_soft),
            Triple("marimba", "Marimba", R.raw.snd_marimba),
            Triple("bubble", "Bubble", R.raw.snd_bubble),
            Triple("glass", "Glass", R.raw.snd_glass),
            Triple("pluck", "Pluck", R.raw.snd_pluck),
            Triple("bright", "Bright", R.raw.snd_bright),
        )

        fun label(key: String) = SOUNDS.firstOrNull { it.first == key }?.second ?: "Soft chime"
    }

    private val settings = Settings(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var popup: View? = null

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val ids = SOUNDS.associate { (key, _, res) -> key to pool.load(ctx, res, 1) }
    private val loaded = HashSet<Int>()
    private var pending: Pair<Int, Float>? = null

    init {
        pool.setOnLoadCompleteListener { p, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            synchronized(loaded) { loaded += id }
            val want = pending
            if (want != null && want.first == id) { pending = null; p.play(id, want.second, want.second, 1, 0, 1f) }
        }
    }

    /** Plays one of the clip sounds (also used for previews in settings). */
    fun playSound(key: String = settings.clipSoundName, volume: Float = settings.clipVolume) {
        val id = ids[key] ?: ids["soft"] ?: return
        val ready = synchronized(loaded) { id in loaded }
        if (ready) pool.play(id, volume, volume, 1, 0, 1f) else pending = id to volume
    }

    fun release() {
        main.post { hidePopup() }
        runCatching { pool.release() }
    }

    /** Instant feedback when the button combo is pressed. */
    fun onPressed() {
        if (settings.clipSound) playSound()
    }

    fun onSaved(clipId: Long, seconds: Int) {
        main.post {
            if (settings.clipPopup) showPopup("Clip saved", "${seconds}s · tap to view", settings.popupMs)
            if (settings.clipNotification && clipId >= 0) notifySaved(clipId, seconds)
        }
    }

    fun onFailed(msg: String) {
        main.post {
            if (settings.clipPopup && canOverlay()) showPopup("Clip failed", msg, maxOf(settings.popupMs, 3000L))
            else Toast.makeText(ctx, "Clip failed: $msg", Toast.LENGTH_LONG).show()
        }
    }

    fun canOverlay() = SysSettings.canDrawOverlays(ctx)

    // ------------------------------------------------------------------ pop-up

    /** Preview of the pop-up for a given length (used in settings). */
    fun previewPopup(ms: Long) = main.post { showPopup("Clip saved", "Preview · ${ms / 1000.0}s", ms) }

    private fun showPopup(title: String, subtitle: String, durationMs: Long) {
        if (!canOverlay()) {
            Toast.makeText(ctx, "$title · $subtitle", Toast.LENGTH_SHORT).show()
            return
        }
        hidePopup()
        val ui = Ui(ctx, settings.palette(ctx))
        val card = ui.row().apply {
            background = ui.borderCard(ui.p.surface, ui.dp(2), ui.dpf(18f))
            setPadding(ui.dp(14), ui.dp(12), ui.dp(20), ui.dp(12))
            elevation = ui.dpf(8f)
        }
        card.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_dualclips) },
            LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { rightMargin = ui.dp(12) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.text(title, 16f, bold = true))
        texts.addView(ui.text(subtitle, 13f, dim = true))
        card.addView(texts)
        card.setOnClickListener {
            hidePopup()
            ctx.startActivity(Intent(ctx, ClipsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = ui.dp(16)
            y = ui.dp(16)
        }
        val wm = ctx.getSystemService(WindowManager::class.java)
        try {
            wm.addView(card, lp)
        } catch (_: Throwable) {
            Toast.makeText(ctx, "$title · $subtitle", Toast.LENGTH_SHORT).show()
            return
        }
        popup = card
        // Slide in from the right, wait, slide out.
        card.translationX = ui.dpf(320f)
        card.alpha = 0f
        card.animate().translationX(0f).alpha(1f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        main.postDelayed({
            if (popup === card) {
                card.animate().translationX(ui.dpf(320f)).alpha(0f).setDuration(200)
                    .withEndAction { if (popup === card) hidePopup() }.start()
            }
        }, durationMs)
    }

    private fun hidePopup() {
        val v = popup ?: return
        popup = null
        runCatching { ctx.getSystemService(WindowManager::class.java).removeView(v) }
    }

    // ------------------------------------------------------------------ notification

    private fun notifySaved(clipId: Long, seconds: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // Silent channel: the app plays its own chime, so the system shouldn't add another sound.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Clip saved", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, clipId)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, ClipsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val play = PendingIntent.getActivity(
            ctx, 11,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val share = PendingIntent.getActivity(
            ctx, 12,
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clip"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Clip saved")
            .setContentText("${seconds}s · tap to open My Clips")
            .setColor(Color.rgb(123, 47, 247))
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Play", play).build())
            .addAction(Notification.Action.Builder(null, "Share", share).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }
}
