package dev.avijit.orbit.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.avijit.orbit.R
import dev.avijit.orbit.diag.DiagLog

/**
 * Keeps the camera and microphone allowed while OrbitWalker records (or shows the camera monitor),
 * even if the phone screen turns off. Its notification has a Stop button.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Recorder.stop(); Recorder.setMonitor(false) }
        val camera = Recorder.camera != null
        val mic = Recorder.recording && Recorder.microphone && Recorder.hasMicPermission()
        if (!camera && !mic && !Recorder.recording) { stopSelf(); return START_NOT_STICKY }
        var type = 0
        if (camera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        runCatching { startForeground(ID, notification(), type) }
            .onFailure { DiagLog.e("record", "foreground service refused", it); stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (Recorder.recording) "OrbitWalker is recording" else "OrbitWalker camera is on")
            .setContentText(if (Recorder.recording) "Saved to Movies/OrbitWalker when you stop" else "Lining up the mixed reality camera")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val ID = 7
        private const val ACTION_STOP = "dev.avijit.orbit.record.STOP"

        /** Starts, refreshes or ends the service to match what's in use. */
        fun update(context: Context) {
            val inUse = Recorder.recording || Recorder.camera != null
            val i = Intent(context, RecordingService::class.java)
            if (inUse) runCatching { context.startForegroundService(i) }.onFailure { DiagLog.e("record", "can't start recording service", it) }
            else context.stopService(i)
        }
    }
}
