package dev.avijit.orbit.cinema

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.notify.OrbitNotificationListener

/**
 * Cinema mode: runs an app directly on the glasses' real (secure, HDCP) display so protected
 * video plays. Not head-tracked. Media keys go through the app's media session.
 */
class CinemaController(private val context: Context) {

    @Volatile var component: String? = null
        private set
    val active: Boolean get() = component != null

    @Volatile var error: String? = null
        private set

    /**
     * Launches [component] on [displayId]. The direct launch needs no Shizuku; if Android refuses it,
     * [shellLaunch] (Shizuku, runs on a worker thread) is tried and [onResult] reports the outcome.
     */
    fun start(activity: Activity, component: String, displayId: Int, shellLaunch: ((Int, String) -> String?)?, worker: android.os.Handler, onResult: () -> Unit) {
        error = null
        val cn = ComponentName.unflattenFromString(component) ?: run { error = "Unknown app"; onResult(); return }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        try {
            activity.startActivity(intent, options.toBundle())
            this.component = component
            DiagLog.i(TAG, "cinema: ${component.substringBefore('/')} on display $displayId")
            onResult()
        } catch (t: Throwable) {
            DiagLog.e(TAG, "direct cinema launch refused", t)
            if (shellLaunch == null) {
                error = "Android refused to open it on the glasses. Start app screens (Home → Start) and try again."
                onResult()
                return
            }
            worker.post {
                val e = shellLaunch(displayId, component)
                activity.runOnUiThread {
                    if (e == null) this.component = component else error = e
                    onResult()
                }
            }
        }
    }

    fun stop() {
        val c = controller()
        if (c != null) c.transportControls.pause() else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        component = null
        DiagLog.i(TAG, "cinema off")
    }

    fun hasMediaAccess() = OrbitNotificationListener.isEnabled(context)

    fun isPlaying(): Boolean? = controller()?.playbackState?.state?.let { it == PlaybackState.STATE_PLAYING }

    fun playPause() {
        val c = controller() ?: return mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun seekBy(deltaMs: Long) {
        val c = controller() ?: return
        val s = c.playbackState
        if (s != null && s.actions and PlaybackState.ACTION_SEEK_TO != 0L) {
            val now = s.position + if (s.state == PlaybackState.STATE_PLAYING)
                ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong() else 0L
            c.transportControls.seekTo((now + deltaMs).coerceAtLeast(0))
        } else if (deltaMs > 0) {
            c.transportControls.fastForward()
        } else {
            c.transportControls.rewind()
        }
    }

    fun volume(up: Boolean) {
        context.getSystemService(AudioManager::class.java).adjustStreamVolume(
            AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun controller(): MediaController? {
        if (!hasMediaAccess()) return null
        val sessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(OrbitNotificationListener.component(context))
        }.getOrNull() ?: return null
        val pkg = component?.substringBefore('/')
        return sessions.firstOrNull { it.packageName == pkg }
    }

    /** Media key to whatever is playing; needs no permission. */
    private fun mediaKey(code: Int) {
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    private companion object {
        const val TAG = "cinema"
    }
}
