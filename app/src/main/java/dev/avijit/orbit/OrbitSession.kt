package dev.avijit.orbit

import android.content.Context
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.glasses.GlassesStatus
import dev.avijit.orbit.glasses.VitureImuSource
import dev.avijit.orbit.tracking.OrientationFilter

/**
 * Process-wide glasses session. VITURE's SDK is process-global, so exactly one source may exist;
 * Activity recreation (rotation, DeX/density changes) must reuse it, not start a second one.
 */
class OrbitSession private constructor(context: Context) {
    val status = GlassesStatus()
    val filter = OrientationFilter()

    @Volatile
    private var autoRecentered = false

    val source = VitureImuSource(
        context = context.applicationContext,
        status = status,
        onSample = { sample ->
            filter.update(sample)
            if (!autoRecentered && filter.isCalibrated) {
                filter.recenter()
                autoRecentered = true
                DiagLog.i("session", "gyro calibrated; auto-recentered")
            }
        },
    )

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        source.start()
    }

    companion object {
        @Volatile
        private var instance: OrbitSession? = null

        fun get(context: Context): OrbitSession =
            instance ?: synchronized(this) {
                instance ?: OrbitSession(context.applicationContext).also { instance = it }
            }
    }
}
