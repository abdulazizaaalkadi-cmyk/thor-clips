package com.aziz.thorclips

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var seconds: Int
        get() = prefs.getInt("seconds", 60)
        set(v) = prefs.edit().putInt("seconds", v).apply()

    /** 0 = Low, 1 = Medium, 2 = High */
    var quality: Int
        get() = prefs.getInt("quality", 1)
        set(v) = prefs.edit().putInt("quality", v).apply()

    var fps: Int
        get() = prefs.getInt("fps", 30)
        set(v) = prefs.edit().putInt("fps", v).apply()

    var audio: Boolean
        get() = prefs.getBoolean("audio", true)
        set(v) = prefs.edit().putBoolean("audio", v).apply()

    /** Output width of the stacked video (top screen is scaled to this width). */
    val width: Int get() = intArrayOf(960, 1280, 1920)[quality.coerceIn(0, 2)]

    /** HEVC bitrate. H.264 fallback uses 1.5x. */
    val bitrate: Int
        get() {
            val base = intArrayOf(5_000_000, 9_000_000, 16_000_000)[quality.coerceIn(0, 2)]
            return if (fps >= 60) base * 7 / 5 else base
        }

    /** Rough RAM the clip buffer will reserve, in MB (HEVC case). */
    fun estimateMb(): Int {
        val video = bitrate.toLong() / 8 * (seconds + 3) * 5 / 4
        val audio = if (audio) 128_000L / 8 * (seconds + 3) * 3 / 2 else 0L
        return ((video + audio) / (1024 * 1024)).toInt()
    }
}
