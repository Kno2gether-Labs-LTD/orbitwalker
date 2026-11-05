package dev.avijit.orbit.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Surface
import dev.avijit.orbit.R
import dev.avijit.orbit.ScreensSession
import dev.avijit.orbit.diag.DiagLog
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mirror mode (no Shizuku): captures the phone's own screen with the user's consent and renders
 * it into screen slot 0. Follows fold/unfold by resizing the capture (one display per projection).
 */
class MirrorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private var sizer: ((Int, Int) -> Unit)? = null
    private lateinit var session: ScreensSession
    private lateinit var displays: DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) attach()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        session = ScreensSession.get(this)
        displays = getSystemService(DisplayManager::class.java)
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        if (data == null || projection != null) return START_NOT_STICKY
        try {
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data).apply {
                registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        DiagLog.i(TAG, "screen capture stopped by the system")
                        stopSelf()
                    }
                }, main)
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "cannot start screen capture", t)
            stopSelf()
            return START_NOT_STICKY
        }
        session.mirrorSink = { s, size -> main.post { surface = s; sizer = size; attach() } }
        session.mirrorSurface().let { (s, size) -> surface = s; sizer = size }
        displays.registerDisplayListener(displayListener, main)
        session.setMirror(true) { attach() } // attach only after app displays released slot 0
        return START_NOT_STICKY
    }

    /** Creates or resizes the capture to the phone's current screen and points it at slot 0. */
    private fun attach() {
        val proj = projection ?: return
        val s = surface
        if (s == null) {
            display?.surface = null
            return
        }
        val d = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val real = Point().also { @Suppress("DEPRECATION") d.getRealSize(it) }
        val scale = MAX_SIDE_PX.toFloat() / max(real.x, real.y).coerceAtLeast(1)
        val w = (real.x * minOf(1f, scale)).roundToInt()
        val h = (real.y * minOf(1f, scale)).roundToInt()
        val dpi = (resources.displayMetrics.densityDpi * minOf(1f, scale)).roundToInt()
        session.mirrorAspect = w.toFloat() / h
        sizer?.invoke(w, h)
        try {
            val vd = display
            if (vd == null) {
                display = proj.createVirtualDisplay("orbit-mirror", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, main)
                DiagLog.i(TAG, "mirroring ${real.x}x${real.y} as ${w}x$h")
            } else {
                vd.resize(w, h, dpi)
                vd.surface = s
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "mirror display failed", t)
        }
    }

    override fun onDestroy() {
        runCatching { displays.unregisterDisplayListener(displayListener) }
        session.mirrorSink = null
        display?.release()
        display = null
        projection?.stop()
        projection = null
        session.setMirror(false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mirror mode", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_orbit)
            .setContentTitle("OrbitWalker is mirroring your screen")
            .setContentText("Your phone screen is pinned in the glasses.")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "mirror"
        private const val CHANNEL = "mirror"
        private const val NOTIFICATION_ID = 42
        private const val MAX_SIDE_PX = 1600
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, MirrorService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorService::class.java))
        }
    }
}
