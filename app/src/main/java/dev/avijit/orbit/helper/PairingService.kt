package dev.avijit.orbit.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.avijit.orbit.MainActivity
import dev.avijit.orbit.R

/**
 * Keeps OrbitWalker alive while the user is in Settings and shows the pairing notification, so the
 * 6-digit code can be typed without leaving the pairing dialog (leaving it cancels the code).
 */
class PairingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { main.post { update() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HelperSetup.addListener(onChange)
    }

    override fun onDestroy() {
        HelperSetup.removeListener(onChange)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BEGIN -> {
                goForeground()
                HelperSetup.beginPairing(this)
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                HelperSetup.submitCode(this, code)
            }
            ACTION_STOP -> { HelperSetup.cancel(); finish(keepNote = false) }
        }
        update()
        return START_NOT_STICKY
    }

    /** Android ends a short service after ~3 minutes. */
    override fun onTimeout(startId: Int) {
        HelperSetup.cancel()
        finish(keepNote = false)
    }

    private fun goForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        else startForeground(ID, n)
    }

    private fun update() {
        val nm = getSystemService(NotificationManager::class.java)
        when (HelperSetup.phase) {
            HelperSetup.Phase.DONE -> {
                nm.notify(ID, simple("OrbitWalker is ready", "App screens are on. You can turn Auto Blocker back on.", ongoing = false))
                finish(keepNote = true)
            }
            HelperSetup.Phase.IDLE -> finish(keepNote = false)
            HelperSetup.Phase.START_FAILED -> {
                nm.notify(ID, simple("Paired, but the helper didn't start", "${HelperSetup.message}. Open OrbitWalker and tap Start.", ongoing = false))
                finish(keepNote = true)
            }
            else -> nm.notify(ID, notification())
        }
    }

    private fun finish(keepNote: Boolean) {
        stopForeground(if (keepNote) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification = when (HelperSetup.phase) {
        HelperSetup.Phase.WAITING_FOR_CODE, HelperSetup.Phase.FAILED -> {
            val reply = PendingIntent.getService(
                this, 1, Intent(this, PairingService::class.java).setAction(ACTION_REPLY),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
            val action = Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_orbit), "Enter code", reply)
                .addRemoteInput(input).build()
            val text = HelperSetup.message?.let { "$it. Tap Enter code to try again." }
                ?: "Tap Enter code and type the 6-digit code shown in Settings."
            builder("Pairing code found", text, ongoing = true).addAction(action).addAction(cancelAction()).build()
        }
        HelperSetup.Phase.PAIRING -> simple("Pairing…", "Checking the code.", ongoing = true)
        HelperSetup.Phase.STARTING -> simple("Starting OrbitWalker helper…", "This takes a few seconds.", ongoing = true)
        else -> builder(
            "Waiting for the pairing code",
            "In Wireless debugging tap \"Pair device with pairing code\". Then enter the code here.",
            ongoing = true,
        ).addAction(cancelAction()).build()
    }

    private fun cancelAction(): Notification.Action {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, PairingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_orbit), "Cancel", stop).build()
    }

    private fun simple(title: String, text: String, ongoing: Boolean) = builder(title, text, ongoing).build()

    private fun builder(title: String, text: String, ongoing: Boolean): Notification.Builder {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_orbit)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
    }

    companion object {
        private const val ID = 4207
        private const val CHANNEL = "setup"
        private const val KEY_CODE = "code"
        private const val ACTION_BEGIN = "dev.avijit.orbit.PAIR_BEGIN"
        private const val ACTION_REPLY = "dev.avijit.orbit.PAIR_REPLY"
        private const val ACTION_STOP = "dev.avijit.orbit.PAIR_STOP"

        fun begin(context: Context) {
            context.startForegroundService(Intent(context, PairingService::class.java).setAction(ACTION_BEGIN))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PairingService::class.java).setAction(ACTION_STOP))
        }
    }
}
