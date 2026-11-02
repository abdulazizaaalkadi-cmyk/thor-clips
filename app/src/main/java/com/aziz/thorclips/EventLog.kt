package com.aziz.thorclips

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log shown in Settings → Diagnostics, so problems can be reported. */
object EventLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast(fmt.format(Date()) + "  " + msg)
        while (lines.size > 80) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
