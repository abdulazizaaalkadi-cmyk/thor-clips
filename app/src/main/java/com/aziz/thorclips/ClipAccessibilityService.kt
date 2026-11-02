package com.aziz.thorclips

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Watches for the clip combo (default L3 + R3) and the screenshot combo
 * (default Select + R3). Always returns false so the buttons still reach your game.
 */
class ClipAccessibilityService : AccessibilityService() {

    private val held = HashSet<Int>()
    private val fired = HashSet<Combo>()
    private lateinit var settings: Settings

    override fun onServiceConnected() {
        super.onServiceConnected()
        EventLog.init(this)
        settings = Settings(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val watched = code == KeyEvent.KEYCODE_BUTTON_THUMBL || code == KeyEvent.KEYCODE_BUTTON_THUMBR ||
            code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BUTTON_START ||
            code == KeyEvent.KEYCODE_BUTTON_L1
        if (!watched) return false
        if (event.action == KeyEvent.ACTION_DOWN) held += code else held -= code
        if (!::settings.isInitialized) settings = Settings(this)

        check(settings.clipCombo) { svc -> svc.requestSave() }
        check(settings.shotCombo) { svc -> svc.requestScreenshot() }

        // Re-arm a combo once both of its buttons are released.
        fired.removeAll { it.a !in held && it.b !in held }
        return false
    }

    private fun check(c: Combo, action: (ReplayService) -> Unit) {
        if (c == Combo.OFF || c in fired) return
        if (c.a in held && c.b in held) {
            fired += c
            EventLog.add("${c.label} pressed")
            val svc = ReplayService.instance
            if (svc != null) action(svc)
            else {
                EventLog.add("${c.label} but buffer is off")
                Toast.makeText(this, "Dual Clips: buffer is off", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
