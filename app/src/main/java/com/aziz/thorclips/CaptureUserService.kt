package com.aziz.thorclips

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Runs in a separate process started by Shizuku with shell (adb) privileges.
 * A normal app can only record the main screen; shell can mirror any display,
 * which is how we reach the Thor's bottom screen.
 *
 * It does as little as possible: create a mirror of a display that renders into
 * a Surface owned by the app. All encoding happens in the app process.
 */
class CaptureUserService() : ICaptureService.Stub() {

    @Suppress("unused")
    constructor(context: Context) : this()

    private val mirrors = HashMap<Int, () -> Unit>()
    private var nextToken = 1
    private val log = StringBuilder()

    init {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        } catch (t: Throwable) {
            note("hidden api bypass failed: $t")
        }
    }

    private fun note(s: String) {
        synchronized(log) {
            if (log.length > 6000) log.setLength(0)
            log.append(s).append('\n')
        }
    }

    override fun destroy() {
        synchronized(mirrors) {
            mirrors.values.forEach { runCatching { it() } }
            mirrors.clear()
        }
        System.exit(0)
    }

    override fun info(): String =
        "uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT}\n" + synchronized(log) { log.toString() }

    override fun startMirror(displayId: Int, surface: Surface, width: Int, height: Int): Int {
        val release = tryDisplayManagerMirror(displayId, surface, width, height)
            ?: trySurfaceControlMirror(displayId, surface, width, height)
            ?: return -1
        synchronized(mirrors) {
            val token = nextToken++
            mirrors[token] = release
            return token
        }
    }

    override fun stopMirror(token: Int) {
        val r = synchronized(mirrors) { mirrors.remove(token) }
        runCatching { r?.invoke() }
    }

    /** Newer Android: a hidden static DisplayManager method creates a mirror directly. */
    private fun tryDisplayManagerMirror(displayId: Int, surface: Surface, w: Int, h: Int): (() -> Unit)? {
        return try {
            val m = DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Surface::class.java
            )
            val vd = m.invoke(null, "thorclips-$displayId", w, h, displayId, surface) as VirtualDisplay
            note("display $displayId: mirrored via DisplayManager")
            ({ vd.release() })
        } catch (t: Throwable) {
            note("display $displayId: DisplayManager path unavailable (${t.javaClass.simpleName}: ${t.cause ?: t.message})")
            null
        }
    }

    /** Older path (Android 13 and below): build a display that shows the target's layer stack. */
    private fun trySurfaceControlMirror(displayId: Int, surface: Surface, w: Int, h: Int): (() -> Unit)? {
        return try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getMethod("getInstance").invoke(null)
            val info = dmgClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(dmg, displayId)
                ?: throw IllegalStateException("no DisplayInfo for $displayId")
            val ic = info.javaClass
            val layerStack = ic.getField("layerStack").getInt(info)
            val lw = ic.getField("logicalWidth").getInt(info)
            val lh = ic.getField("logicalHeight").getInt(info)

            val sc = Class.forName("android.view.SurfaceControl")
            // Shell cannot create secure displays on Android 11+.
            val secure = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            val token = sc.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, "thorclips-$displayId", secure) as IBinder

            sc.getMethod("openTransaction").invoke(null)
            try {
                sc.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java)
                    .invoke(null, token, surface)
                sc.getMethod(
                    "setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType,
                    Rect::class.java, Rect::class.java
                ).invoke(null, token, 0, Rect(0, 0, lw, lh), fit(lw, lh, w, h))
                sc.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, token, layerStack)
            } finally {
                sc.getMethod("closeTransaction").invoke(null)
            }
            note("display $displayId: mirrored via SurfaceControl (layerStack=$layerStack ${lw}x$lh)")
            ({
                runCatching { sc.getMethod("destroyDisplay", IBinder::class.java).invoke(null, token) }
                Unit
            })
        } catch (t: Throwable) {
            note("display $displayId: SurfaceControl path failed (${t.javaClass.simpleName}: ${t.cause ?: t.message})")
            null
        }
    }

    /** Largest rect with the source aspect ratio that fits inside w x h, centred. */
    private fun fit(srcW: Int, srcH: Int, w: Int, h: Int): Rect {
        val s = minOf(w.toFloat() / srcW, h.toFloat() / srcH)
        val fw = (srcW * s).toInt()
        val fh = (srcH * s).toInt()
        val x = (w - fw) / 2
        val y = (h - fh) / 2
        return Rect(x, y, x + fw, y + fh)
    }
}
