package com.aziz.thorclips

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SysSettings
import android.widget.LinearLayout
import android.widget.Toast

/** "When you clip" settings, shared by Dual Clips settings and My Clips settings. */
object ClipAlertRows {

    fun add(a: Activity, ui: Ui, col: LinearLayout, feedback: ClipFeedback) {
        val s = Settings(a)
        col.addView(ui.section("When you clip"))

        col.addView(ui.settingRow("Sound", {
            when {
                !s.clipSound -> "Off"
                s.clipSoundName == "custom" -> s.customSoundLabel
                else -> ClipFeedback.label(s.clipSoundName)
            }
        }) { r ->
            val hasCustom = PickSoundActivity.file(a).exists()
            val opts = listOf("Off" to "off") + ClipFeedback.SOUNDS.map { it.second to it.first } +
                (if (hasCustom) listOf("★ ${s.customSoundLabel}" to "custom") else emptyList()) +
                listOf("＋ Upload your own…" to "upload")
            ui.choosePreview("Clip sound", opts, if (s.clipSound) s.clipSoundName else "off",
                onPreview = { key ->
                    when (key) {
                        "off" -> {}
                        "upload" -> a.startActivity(Intent(a, PickSoundActivity::class.java))
                        "custom" -> ClipFeedback(a).let { f -> f.playSound("custom", s.clipVolume) }
                        else -> feedback.playSound(key, s.clipVolume)
                    }
                },
                onPick = { key ->
                    when (key) {
                        "off" -> s.clipSound = false
                        "upload" -> a.startActivity(Intent(a, PickSoundActivity::class.java))
                        else -> { s.clipSound = true; s.clipSoundName = key }
                    }
                    r()
                })
        })

        col.addView(ui.settingRow("Sound volume", { volumeLabel(s.clipVolume) }) { r ->
            val opts = listOf("Quiet" to 0.3f, "Medium" to 0.6f, "Loud" to 0.9f)
            ui.choosePreview("Sound volume", opts, s.clipVolume,
                onPreview = { v -> feedback.playSound(s.clipSoundName, v) },
                onPick = { v -> s.clipVolume = v; r() })
        })

        col.addView(ui.settingRow("Pop-up over game", {
            when {
                !s.clipPopup -> "Off"
                !SysSettings.canDrawOverlays(a) -> "Needs permission"
                else -> "On"
            }
        }) { r ->
            if (s.clipPopup && !SysSettings.canDrawOverlays(a)) askOverlay(a) else s.clipPopup = !s.clipPopup
            r()
        })

        col.addView(ui.settingRow("Pop-up length", { "${s.popupMs / 1000.0}s" }) { r ->
            val opts = listOf("1.5 s" to 1500L, "2.5 s" to 2500L, "4 s" to 4000L, "6 s" to 6000L)
            ui.choosePreview("How long the pop-up stays", opts, s.popupMs,
                onPreview = { ms ->
                    if (SysSettings.canDrawOverlays(a)) feedback.previewPopup(ms) else askOverlay(a)
                },
                onPick = { ms -> s.popupMs = ms; r() })
        })

        col.addView(ui.settingRow("Notification", { if (s.clipNotification) "On" else "Off" }) { r ->
            s.clipNotification = !s.clipNotification; r()
        })

        col.addView(ui.settingRow("Test everything", { "Sound + pop-up" }) { _ ->
            feedback.onPressed()
            if (SysSettings.canDrawOverlays(a)) feedback.previewPopup(s.popupMs) else askOverlay(a)
        })
    }

    private fun volumeLabel(v: Float) = when {
        v < 0.45f -> "Quiet"
        v < 0.75f -> "Medium"
        else -> "Loud"
    }

    private fun askOverlay(a: Activity) {
        Toast.makeText(a, "Find Dual Clips and allow \"Display over other apps\"", Toast.LENGTH_LONG).show()
        runCatching {
            a.startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
        }
    }
}
