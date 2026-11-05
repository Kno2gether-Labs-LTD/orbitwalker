package dev.avijit.orbit.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import dev.avijit.orbit.diag.DiagLog

/**
 * Notification access: shows phone notifications as cards in the glasses, and lets Cinema mode
 * control media playback. Contents are never logged (only the app name).
 */
class OrbitNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // The process may start for this listener before the app has loaded its settings.
        val p = getSharedPreferences("orbit", MODE_PRIVATE)
        NotificationFeed.enabled = p.getBoolean("notify", true)
        NotificationFeed.hideText = p.getBoolean("notifyHide", false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!NotificationFeed.enabled) return
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true &&
            ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT
        ) return // silent / low-importance updates don't pop up
        if (sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 &&
            NotificationFeed.cards.any { it.key == sbn.key }
        ) return
        if (sbn.notification.visibility == Notification.VISIBILITY_SECRET) return
        val n = sbn.notification
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        val icon = runCatching { packageManager.getApplicationIcon(sbn.packageName).toBitmap(120, 120) }.getOrNull()
        val private = NotificationFeed.hideText
        val art = runCatching {
            CardArt.render(app, if (private) "New notification" else title, if (private) "Open the app to read it" else text, icon)
        }.getOrNull() ?: return
        NotificationFeed.post(sbn.key, art)
        DiagLog.i("notify", "card from $app")
    }

    companion object {
        fun component(context: Context) = ComponentName(context, OrbitNotificationListener::class.java)

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
