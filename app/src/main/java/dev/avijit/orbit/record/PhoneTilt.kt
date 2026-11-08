package dev.avijit.orbit.record

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which way up the phone stands, from gravity (the screen's rotation can't tell: auto-rotate is
 * often off, and a foldable's screens disagree). Listens only while someone holds it.
 */
class PhoneTilt(context: Context, private val onSteadyChange: (Int?) -> Unit) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null
    private val holders = mutableSetOf<String>()
    @Volatile private var first = CountDownLatch(1)
    @Volatile private var raw: Int? = null
    @Volatile private var rawSince = 0L
    /** The tilt that has held for [STEADY_MS]. */
    @Volatile var steady: Int? = null
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val t = CameraOrient.tiltDeg(e.values[0], e.values[1])
            val now = SystemClock.uptimeMillis()
            if (t != raw) { raw = t; rawSince = now }
            if (first.count > 0L) { steady = t; first.countDown(); return }
            if (t != steady && now - rawSince >= STEADY_MS) { steady = t; onSteadyChange(t) }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }

    /** Main thread. Starts listening while any [who] holds it. */
    fun hold(who: String) {
        if (!holders.add(who) || holders.size > 1 || accel == null) return
        first = CountDownLatch(1)
        val t = HandlerThread("rec-tilt").apply { start() }
        thread = t
        sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI, Handler(t.looper))
    }

    fun release(who: String) {
        if (!holders.remove(who) || holders.isNotEmpty()) return
        sensors?.unregisterListener(listener)
        thread?.quitSafely(); thread = null
    }

    /** The current tilt, waiting briefly for the first reading after [hold]. */
    fun now(): Int? {
        if (accel != null && holders.isNotEmpty()) first.await(400, TimeUnit.MILLISECONDS)
        return steady
    }

    private companion object { const val STEADY_MS = 1_000L }
}
