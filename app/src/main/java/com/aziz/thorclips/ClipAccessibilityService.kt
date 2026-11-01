package com.aziz.thorclips

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Watches for L3 + R3 held together and asks the clip buffer to save.
 * Always returns false so the buttons still reach your game.
 */
class ClipAccessibilityService : AccessibilityService() {

    private var l3 = false
    private var r3 = false
    private var fired = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_THUMBL -> l3 = down
            KeyEvent.KEYCODE_BUTTON_THUMBR -> r3 = down
            else -> return false
        }
        if (l3 && r3 && !fired) {
            fired = true
            val svc = ReplayService.instance
            if (svc != null) svc.requestSave()
            else Toast.makeText(this, "Thor Clips: buffer is off", Toast.LENGTH_SHORT).show()
        }
        if (!l3 && !r3) fired = false
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
