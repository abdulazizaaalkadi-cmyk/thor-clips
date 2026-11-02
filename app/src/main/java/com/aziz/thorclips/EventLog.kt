package com.aziz.thorclips

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small log shown in Settings → Diagnostics. Also written to a file, so it survives
 * the app being closed or killed and still shows what happened right before.
 */
object EventLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "events.log")
        file = f
        runCatching {
            if (f.exists()) synchronized(this) {
                f.readLines().takeLast(80).forEach { lines.addLast(it) }
            }
        }
    }

    @Synchronized
    fun add(msg: String) {
        val line = fmt.format(Date()) + "  " + msg
        lines.addLast(line)
        while (lines.size > 80) lines.removeFirst()
        runCatching {
            val f = file ?: return
            if (f.length() > 64_000) f.writeText(lines.joinToString("\n") + "\n") else f.appendText(line + "\n")
        }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
