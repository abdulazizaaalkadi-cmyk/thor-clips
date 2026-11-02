package com.aziz.thorclips

import android.content.Context

enum class ScreenMode(val label: String) {
    AUTO("Auto (detect)"), BOTH("Both screens"), TOP("Top screen only"), BOTTOM("Bottom screen only")
}

enum class SortOrder(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), LONGEST("Longest first") }

/** Dual Clips (recorder) settings. */
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

    /** True while the user wants the buffer on, so it can come back if Android kills it. */
    var wantRunning: Boolean
        get() = prefs.getBoolean("want_running", false)
        set(v) = prefs.edit().putBoolean("want_running", v).apply()

    var clipSound: Boolean
        get() = prefs.getBoolean("clip_sound", true)
        set(v) = prefs.edit().putBoolean("clip_sound", v).apply()

    /** Which clip sound: soft, marimba, bubble, glass, pluck, bright. */
    var clipSoundName: String
        get() = prefs.getString("clip_sound_name", "soft") ?: "soft"
        set(v) = prefs.edit().putString("clip_sound_name", v).apply()

    var clipVolume: Float
        get() = prefs.getFloat("clip_volume", 0.6f)
        set(v) = prefs.edit().putFloat("clip_volume", v).apply()

    /** How long the clip-saved pop-up stays on screen, in ms. */
    var popupMs: Long
        get() = prefs.getLong("popup_ms", 2500L)
        set(v) = prefs.edit().putLong("popup_ms", v).apply()

    var clipPopup: Boolean
        get() = prefs.getBoolean("clip_popup", true)
        set(v) = prefs.edit().putBoolean("clip_popup", v).apply()

    var clipNotification: Boolean
        get() = prefs.getBoolean("clip_notif", true)
        set(v) = prefs.edit().putBoolean("clip_notif", v).apply()

    var screenMode: ScreenMode
        get() = runCatching { ScreenMode.valueOf(prefs.getString("screens", "AUTO")!!) }.getOrDefault(ScreenMode.AUTO)
        set(v) = prefs.edit().putString("screens", v.name).apply()

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", "DARK")!!) }.getOrDefault(ThemeMode.DARK)
        set(v) = prefs.edit().putString("theme", v.name).apply()

    val qualityLabel get() = arrayOf("Low", "Medium", "High")[quality.coerceIn(0, 2)]

    /** Output width of the stacked video (each screen is scaled to fit this width). */
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

    fun palette(ctx: Context) = Ui.palette(ctx, theme, Accent.PURPLE)
}

/** My Clips (library) settings, kept separate so it can have its own look. */
class ClipsPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("clips", Context.MODE_PRIVATE)

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", "DARK")!!) }.getOrDefault(ThemeMode.DARK)
        set(v) = prefs.edit().putString("theme", v.name).apply()

    var accent: Accent
        get() = runCatching { Accent.valueOf(prefs.getString("accent", "PURPLE")!!) }.getOrDefault(Accent.PURPLE)
        set(v) = prefs.edit().putString("accent", v.name).apply()

    var previews: Boolean
        get() = prefs.getBoolean("previews", false)
        set(v) = prefs.edit().putBoolean("previews", v).apply()

    var sort: SortOrder
        get() = runCatching { SortOrder.valueOf(prefs.getString("sort", "NEWEST")!!) }.getOrDefault(SortOrder.NEWEST)
        set(v) = prefs.edit().putString("sort", v.name).apply()

    /** "package/activity|action" of the editor picked last time, or null. */
    var editor: String?
        get() = prefs.getString("editor", null)
        set(v) = prefs.edit().putString("editor", v).apply()

    var favourites: Set<String>
        get() = prefs.getStringSet("favs", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("favs", v.toSet()).apply()

    fun gameFor(id: Long): String? = prefs.getString("game_$id", null)
    fun setGame(id: Long, pkg: String) = prefs.edit().putString("game_$id", pkg).apply()

    fun palette(ctx: Context) = Ui.palette(ctx, theme, accent)
}
