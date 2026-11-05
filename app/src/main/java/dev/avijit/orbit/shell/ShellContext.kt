package dev.avijit.orbit.shell

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper

/**
 * Presents the service's context as the shell package (uid 2000), the identity this process
 * really runs as, so system services accept calls made on its behalf (scrcpy's FakeContext).
 */
class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE
    override fun getOpPackageName(): String = SHELL_PACKAGE
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_UID = 2000
    }
}
