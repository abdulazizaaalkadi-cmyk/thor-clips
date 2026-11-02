package com.aziz.thorclips

import android.graphics.Point
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** One screen's place in the stacked output video (y measured from the top). */
data class Region(val w: Int, val h: Int, val x: Int, val y: Int)

/** Screens stacked top-to-bottom, all scaled by the same factor so they keep their real size ratio. */
data class Layout(val outW: Int, val outH: Int, val regions: List<Region>) {
    companion object {
        private fun even(v: Float) = (v.toInt() / 2) * 2
        private fun align16(v: Int) = (v + 15) / 16 * 16

        fun compute(screens: List<Point>, targetW: Int): Layout {
            require(screens.isNotEmpty())
            val s = targetW.toFloat() / screens.maxOf { it.x }
            val sizes = screens.map { even(it.x * s) to even(it.y * s) }
            val outW = align16(sizes.maxOf { it.first })
            val outH = align16(sizes.sumOf { it.second })
            var y = 0
            val regions = sizes.map { (w, h) -> Region(w, h, (outW - w) / 2, y).also { y += h } }
            return Layout(outW, outH, regions)
        }
    }
}

/**
 * Draws each mirrored screen into its region of the encoder's input surface.
 * Runs entirely on [handler]'s thread (it owns the GL context).
 *
 * New frames from the mirrors are latched as soon as they arrive (so the system never
 * waits on us), but we only draw and send a frame to the encoder at the target fps.
 */
class Compositor(
    encoderInput: Surface,
    private val layout: Layout,
    fps: Int,
    private val handler: Handler,
) {
    private val frameIntervalMs = (1000L / fps).coerceAtLeast(1)

    private val eglDisplay: EGLDisplay
    private val eglContext: EGLContext
    private val eglSurface: EGLSurface

    private val program: Int
    private val aPos: Int
    private val aTex: Int
    private val uTexMatrix: Int

    private val textures: IntArray
    private val surfaceTextures = ArrayList<SurfaceTexture>()
    private val matrices = ArrayList<FloatArray>()

    /** One input Surface per region, in the same order as [Layout.regions]. */
    val surfaces = ArrayList<Surface>()

    private var lastDraw = 0L
    private var drawScheduled = false
    private var released = false
    @Volatile var paused = false

    private val quad: FloatBuffer = floatBuffer(
        // x, y, u, v
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "no EGL config"
        }
        val config = configs[0]!!
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, encoderInput, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val n = layout.regions.size
        textures = IntArray(n)
        GLES20.glGenTextures(n, textures, 0)
        for ((i, r) in layout.regions.withIndex()) {
            val t = textures[i]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val m = FloatArray(16)
            val st = SurfaceTexture(t).apply {
                setDefaultBufferSize(r.w, r.h)
                setOnFrameAvailableListener({ onFrame(i) }, handler)
                getTransformMatrix(m)
            }
            surfaceTextures += st
            matrices += m
            surfaces += Surface(st)
        }
    }

    private fun onFrame(i: Int) {
        if (released) return
        // Always latch right away so the mirror's buffers are returned promptly.
        try {
            val st = surfaceTextures[i]
            st.updateTexImage()
            st.getTransformMatrix(matrices[i])
        } catch (_: Throwable) {
            return
        }
        if (paused || drawScheduled) return
        val wait = lastDraw + frameIntervalMs - SystemClock.uptimeMillis()
        drawScheduled = true
        if (wait <= 0) handler.post { draw() } else handler.postDelayed({ draw() }, wait)
    }

    private fun draw() {
        drawScheduled = false
        if (released || paused) return
        lastDraw = SystemClock.uptimeMillis()

        GLES20.glViewport(0, 0, layout.outW, layout.outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        for ((i, r) in layout.regions.withIndex()) {
            // GL's origin is bottom-left; regions are measured from the top.
            drawQuad(textures[i], matrices[i], r.x, layout.outH - r.y - r.h, r.w, r.h)
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawQuad(tex: Int, m: FloatArray, x: Int, y: Int, w: Int, h: Int) {
        GLES20.glViewport(x, y, w, h)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, m, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        if (released) return
        released = true
        surfaces.forEach { runCatching { it.release() } }
        surfaceTextures.forEach { runCatching { it.release() } }
        runCatching { GLES20.glDeleteProgram(program) }
        runCatching { GLES20.glDeleteTextures(textures.size, textures, 0) }
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglReleaseThread()
    }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """.trimIndent()
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "GL link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "GL shader failed: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v); position(0)
            }
    }
}
