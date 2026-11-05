package dev.avijit.orbit.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.shell.IOrbitShell
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives the built-in helper's binder. The manifest restricts it to holders of
 * INTERACT_ACROSS_USERS_FULL (the shell and system), and [call] re-checks the caller is the shell.
 */
class HelperProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val caller = Binder.getCallingUid()
        if (caller != SHELL_UID && caller != ROOT_UID) {
            DiagLog.e(TAG, "helper provider: rejected uid $caller")
            return null
        }
        if (method != HelperLaunch.METHOD_SHELL) return null
        val binder = extras?.getBinder(HelperLaunch.KEY_BINDER) ?: return null
        BuiltInHelper.receive(binder)
        return Bundle().apply { putBoolean(HelperLaunch.KEY_OK, true) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object {
        const val TAG = "helper"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}

/** The built-in helper's binder, as last delivered to this process. Listeners run on the main thread. */
object BuiltInHelper {
    @Volatile private var shell: IOrbitShell? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun shell(): IOrbitShell? = shell?.takeIf { it.asBinder().pingBinder() }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun receive(binder: IBinder) {
        if (!binder.pingBinder()) return
        val s = IOrbitShell.Stub.asInterface(binder)
        if (shell?.asBinder() === binder) return
        shell = s
        runCatching {
            binder.linkToDeath({
                if (shell?.asBinder() === binder) {
                    shell = null
                    DiagLog.e("helper", "built-in helper stopped")
                    notifyChanged()
                }
            }, 0)
        }
        DiagLog.i("helper", "built-in helper connected: ${runCatching { s.info() }.getOrNull()}")
        notifyChanged()
    }

    private fun notifyChanged() = main.post { listeners.forEach { it() } }
}
