package com.aziz.thorclips

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), OLED("OLED dark"), WHITE("White") }

/** Colour pairs (start → end of the gradient). PURPLE matches the app icon. */
enum class Accent(val label: String, val c1: Int, val c2: Int) {
    PURPLE("Purple", 0xFF7B2FF7.toInt(), 0xFFF107A3.toInt()),
    BLUE("Blue", 0xFF1A5FE8.toInt(), 0xFF38BDF8.toInt()),
    RED("Red", 0xFFE11D2E.toInt(), 0xFFFF7A7F.toInt()),
    GREEN("Green", 0xFF16A34A.toInt(), 0xFF6EE7B7.toInt()),
    ORANGE("Orange", 0xFFEA580C.toInt(), 0xFFFACC15.toInt()),
    CYAN("Cyan", 0xFF0891B2.toInt(), 0xFF67E8F9.toInt()),
    PINK("Pink", 0xFFDB2777.toInt(), 0xFFF9A8D4.toInt()),
}

class Palette(
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val surface2: Int,
    val text: Int,
    val dim: Int,
    val accent: Accent,
) {
    val c1 get() = accent.c1
    val c2 get() = accent.c2
}

/** Small toolkit so every screen shares one look and works with a controller. */
class Ui(val ctx: Context, val p: Palette) {

    companion object {
        fun palette(ctx: Context, mode: ThemeMode, accent: Accent): Palette {
            val sysDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val m = if (mode == ThemeMode.SYSTEM) (if (sysDark) ThemeMode.DARK else ThemeMode.WHITE) else mode
            return when (m) {
                ThemeMode.OLED -> Palette(
                    true, 0xFF000000.toInt(), 0xFF0D0912.toInt(), 0xFF1A1324.toInt(),
                    0xFFF6F0FF.toInt(), 0xFFA99CC2.toInt(), accent
                )
                ThemeMode.WHITE -> Palette(
                    false, 0xFFF5F1FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFECE5F5.toInt(),
                    0xFF1B1026.toInt(), 0xFF6C5F82.toInt(), accent
                )
                else -> Palette(
                    true, 0xFF130A1E.toInt(), 0xFF1F1030.toInt(), 0xFF2B1843.toInt(),
                    0xFFF6F0FF.toInt(), 0xFFB5A8CE.toInt(), accent
                )
            }
        }
    }

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * ctx.resources.displayMetrics.density

    fun applyWindow(a: Activity) {
        a.window.statusBarColor = p.bg
        a.window.navigationBarColor = p.bg
        a.window.decorView.setBackgroundColor(p.bg)
        @Suppress("DEPRECATION")
        a.window.decorView.systemUiVisibility = if (p.dark) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    fun dialog(): AlertDialog.Builder = AlertDialog.Builder(
        ctx,
        if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    )

    // ------------------------------------------------------------ drawables

    fun gradient(radius: Float = dpf(16f)) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(p.c1, p.c2)
    ).apply { cornerRadius = radius }

    fun solid(color: Int, radius: Float = dpf(16f)) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    /** Card with a gradient border (like the Cocoon icons). */
    fun borderCard(fill: Int = p.surface, border: Int = dp(2), radius: Float = dpf(18f)): Drawable {
        val ld = LayerDrawable(arrayOf(gradient(radius), solid(fill, radius - border)))
        ld.setLayerInset(1, border, border, border, border)
        return ld
    }

    /** Normal = flat card; focused/pressed = gradient border, so you can see where the controller is. */
    fun focusBg(fill: Int = p.surface, radius: Float = dpf(18f)): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), borderCard(p.surface2, dp(3), radius))
        addState(intArrayOf(android.R.attr.state_selected), borderCard(p.surface2, dp(3), radius))
        addState(intArrayOf(android.R.attr.state_pressed), borderCard(p.surface2, dp(3), radius))
        addState(intArrayOf(), solid(fill, radius))
    }

    // ------------------------------------------------------------ views

    fun text(s: CharSequence, size: Float = 15f, bold: Boolean = false, dim: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(if (dim) p.dim else p.text)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun column(padding: Int = 20) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun row() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    /** Header: icon, title, gear button. */
    fun header(title: String, iconRes: Int, onSettings: (() -> Unit)?): View {
        val r = row()
        r.addView(ImageView(ctx).apply { setImageResource(iconRes) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        r.addView(text(title, 26f, bold = true).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onSettings != null) r.addView(iconButton(GearDrawable(p.text)) { onSettings() })
        return r
    }

    fun iconButton(d: Drawable, onClick: () -> Unit): View = FrameLayout(ctx).apply {
        background = focusBg(p.surface, dpf(14f))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        addView(ImageView(ctx).apply { setImageDrawable(d) },
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
    }

    /** Big gradient call-to-action. */
    fun primaryButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 19f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = StateListDrawable().apply {
            val focused = LayerDrawable(arrayOf(solid(p.text, dpf(20f)), gradient(dpf(17f))))
            focused.setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), gradient(dpf(20f)))
        }
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun secondaryButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 16f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(p.text)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(16), dp(12), dp(16))
        background = focusBg(p.surface, dpf(18f))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Settings row: title + current value; tap to change. */
    fun settingRow(title: String, value: () -> String, onClick: (refresh: () -> Unit) -> Unit): View {
        val r = row().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = focusBg(p.surface, dpf(16f))
            isFocusable = true
            isClickable = true
        }
        val v = text(value(), 15f, dim = true)
        r.addView(text(title, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(v)
        val refresh = { v.text = value() }
        r.setOnClickListener { onClick(refresh) }
        r.layoutParams = lp(top = 8)
        return r
    }

    fun section(title: String) = text(title.uppercase(), 13f, bold = true).apply {
        setTextColor(p.c1.let { if (p.dark) p.c2 else it })
        letterSpacing = 0.08f
        setPadding(dp(4), dp(22), 0, dp(2))
    }

    /** Pick one of several options in a dialog. */
    fun <T> choose(title: String, options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
        val idx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        dialog().setTitle(title)
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), idx) { d, which ->
                onPick(options[which].second); d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun tint(v: TextView, color: Int) {
        v.setTextColor(ColorStateList.valueOf(color))
    }
}

/** Gear icon drawn in code (no image files needed). */
class GearDrawable(private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@GearDrawable.color }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        val save = c.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), null)
        val path = Path()
        val teeth = 8
        val outer = r * 0.98f
        val inner = r * 0.74f
        for (i in 0 until teeth * 2) {
            val a0 = Math.PI * 2 * i / (teeth * 2) - Math.PI / (teeth * 4)
            val a1 = a0 + Math.PI / (teeth * 2)
            val rr = if (i % 2 == 0) outer else inner
            val x0 = cx + rr * cos(a0).toFloat()
            val y0 = cy + rr * sin(a0).toFloat()
            val x1 = cx + rr * cos(a1).toFloat()
            val y1 = cy + rr * sin(a1).toFloat()
            if (i == 0) path.moveTo(x0, y0) else path.lineTo(x0, y0)
            path.lineTo(x1, y1)
        }
        path.close()
        c.drawPath(path, paint)
        c.drawCircle(cx, cy, r * 0.30f, hole)
        c.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 64
    override fun getIntrinsicHeight() = 64
}
