package dev.avijit.orbit.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Test hooks for driving OrbitWalker from a computer over adb, e.g.
 *   adb shell am broadcast -a dev.avijit.orbit.TEST -n dev.avijit.orbit/.diag.TestHarness --es cmd status
 * Protected by android.permission.DUMP (only the adb shell / system hold it), so ordinary apps
 * can't send these. Results go to logcat (tag Orbit/test) and Share logs. Typed text is never logged.
 */
class TestHarness : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Off unless a developer enabled it with: adb shell run-as dev.avijit.orbit touch files/test-hooks
        // (only possible over adb on this debuggable build; other apps can't write OrbitWalker's files).
        if (!java.io.File(context.filesDir, "test-hooks").exists()) return
        if (context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) return
        val h = handler ?: return DiagLog.e(TAG, "no app session")
        val cmd = intent.getStringExtra("cmd") ?: return
        DiagLog.i(TAG, "cmd $cmd")
        when (cmd) {
            "status" -> DiagLog.i(TAG, h.status())
            "open" -> intent.getStringExtra("component")?.let(h::open)
            "type" -> intent.getStringExtra("text")?.let(h::type)
            "key" -> h.key(intent.getIntExtra("code", KeyEvent.KEYCODE_ENTER))
            "aim" -> h.aim(intent.getIntExtra("slot", 0), intent.getFloatExtra("u", 0.5f), intent.getFloatExtra("v", 0.5f))
            "look" -> h.look(intent.getFloatExtra("yaw", 0f), intent.getFloatExtra("pitch", 0f))
            "point" -> h.point(intent.getFloatExtra("yaw", 0f), intent.getFloatExtra("pitch", 0f))
            "click" -> h.click()
            "drag" -> h.drag()
            "aimPlanet" -> intent.getStringExtra("name")?.let(h::aimPlanet)
            "eyebreak" -> h.eyeBreak()
            "autoblocker" -> h.autoBlocker()
            "background" -> intent.getStringExtra("mode")?.let(h::background)
            "press" -> h.press(intent.getBooleanExtra("down", true))
            "keyboard" -> h.showKeyboard(intent.getBooleanExtra("on", true))
            "rawAnyBus" -> h.rawAnyBus(intent.getBooleanExtra("on", false))
            "closeAll" -> h.closeAll()
            // Recording (test hooks): start/stop, camera monitor, calibration keys, options.
            "record" -> if (intent.getBooleanExtra("on", true)) dev.avijit.orbit.record.Recorder.start() else dev.avijit.orbit.record.Recorder.stop()
            "monitor" -> dev.avijit.orbit.record.Recorder.setMonitor(intent.getBooleanExtra("on", true))
            "calKey" -> dev.avijit.orbit.record.Recorder.calibrate(intent.getIntExtra("code", 0), intent.getBooleanExtra("shift", false))
            "recSpot" -> intent.getStringExtra("spot")?.let { dev.avijit.orbit.record.Recorder.spot = dev.avijit.orbit.record.CameraSpot.valueOf(it) }
            "recOpt" -> {
                val on = intent.getBooleanExtra("on", true)
                val r = dev.avijit.orbit.record.Recorder
                when (intent.getStringExtra("name")) {
                    "glasses" -> r.glassesView = on; "mixed" -> r.mixedReality = on; "mic" -> r.microphone = on
                    "bubble" -> r.faceBubble = on; "person" -> r.personInFront = on; "wide" -> r.wideLens = on; "front" -> r.frontCamera = on
                }
            }
            "frame" -> h.frame()
            "reclaim" -> h.reclaim()
            "forceStereo" -> h.forceStereo(intent.getBooleanExtra("on", true))
            "pixelPerfect" -> h.pixelPerfect()
            "cinema" -> intent.getStringExtra("component")?.let(h::cinema) ?: h.cinemaExit()
            else -> DiagLog.e(TAG, "unknown cmd $cmd")
        }
    }

    /** Implemented by the app while it's running. */
    interface Handler {
        fun status(): String
        fun open(component: String)
        fun type(text: String)
        fun key(code: Int)
        fun point(yawDeg: Float, pitchDeg: Float)
        /** Tests without glasses: turn the virtual head. */
        fun look(yawDeg: Float, pitchDeg: Float)
        /** Aims the pointer at (u, v) on a screen slot, or on the in-glasses keyboard with slot -1. */
        fun aim(slot: Int, u: Float, v: Float)
        fun click()
        /** Show the eye-break card now. */
        fun eyeBreak()
        fun autoBlocker()
        /** ORBIT, SPACE, LOGO or BLACK. */
        fun background(mode: String)
        /** Points the cursor at the Orbit-sky planet called [name]. */
        fun aimPlanet(name: String)
        /** Pointer moved while pressed (after an aim), like dragging the mouse. */
        fun drag()
        fun press(down: Boolean)
        fun showKeyboard(on: Boolean)
        fun rawAnyBus(on: Boolean)
        fun closeAll()
        /** Saves what the glasses show to the app's files dir (frame.png). */
        fun frame()
        /** Same as tapping "use them in the glasses" after Ctrl+Esc. */
        fun reclaim()
        /** Render the 3D desk without glasses (to check stereo output). */
        fun forceStereo(on: Boolean)
        fun pixelPerfect()
        fun cinema(component: String)
        fun cinemaExit()
    }

    companion object {
        private const val TAG = "test"
        @Volatile var handler: Handler? = null
    }
}
