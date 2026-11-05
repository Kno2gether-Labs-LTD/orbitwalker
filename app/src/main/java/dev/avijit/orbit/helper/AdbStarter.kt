package dev.avijit.orbit.helper

import android.content.Context
import android.os.Build
import android.os.Process
import dev.avijit.orbit.diag.DiagLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Talks to this phone's own adbd over Wireless debugging, the way Shizuku does: pairs once with
 * the 6-digit code, then on each start connects and launches [HelperMain]. Worker threads only.
 * The key pair stays in the app's private no-backup storage.
 */
object AdbStarter {
    private const val TAG = "helper"
    private const val CONNECT_TIMEOUT_MS = 8_000L
    /** The launch command waits up to 5 s for the helper, then reports. */
    private const val LAUNCH_OUTPUT_MS = 10_000L

    /** Pairs with the "Pair device with pairing code" dialog. Returns null on success, else a reason. */
    fun pair(context: Context, host: String, port: Int, code: String): String? = withManager(context) { m ->
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return@withManager "The pairing code has 6 digits"
        try {
            m.pair(host, port, clean)
            DiagLog.i(TAG, "paired with Wireless debugging")
            null
        } catch (t: Throwable) {
            DiagLog.e(TAG, "pairing failed", t)
            "Pairing failed. Check the code and try again (${t.javaClass.simpleName})"
        }
    }

    /**
     * Connects to Wireless debugging and starts the helper. Returns null when the launch command ran
     * (the helper then reaches the app within a second or two), else a reason for the user.
     */
    fun start(context: Context): String? = withManager(context) { m ->
        try {
            if (!m.connectTls(context, CONNECT_TIMEOUT_MS)) return@withManager "Wireless debugging refused the connection"
        } catch (e: AdbPairingRequiredException) {
            // Pairing was forgotten (revoked in Settings, new phone, …): back to the Pair step.
            setPaired(context, false)
            return@withManager "OrbitWalker isn't paired any more. Pair again with the code"
        } catch (e: InterruptedException) {
            return@withManager "Couldn't find Wireless debugging. Turn it on (Wi-Fi must be connected)"
        } catch (t: Throwable) {
            DiagLog.e(TAG, "adb connect failed", t)
            return@withManager "Couldn't connect to Wireless debugging (${t.javaClass.simpleName}). " +
                "On Samsung, turn off Auto Blocker for now"
        }
        val cmd = HelperLaunch.command(context.applicationInfo.sourceDir, Process.myUid())
        val out = try {
            m.openStream("shell:$cmd").use { s -> readUntilMarker(s) }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "helper launch failed", t)
            return@withManager "Couldn't start the helper (${t.javaClass.simpleName})"
        }
        if (out.contains(HelperLaunch.FAILED_MARKER)) {
            DiagLog.e(TAG, "helper process exited right after launch")
            return@withManager "The helper exited right after starting. Share logs"
        }
        if (!out.contains(HelperLaunch.LAUNCHED_MARKER)) {
            DiagLog.e(TAG, "helper launch: unexpected output ${out.take(200)}")
            return@withManager "The helper didn't start. Share logs"
        }
        DiagLog.i(TAG, "helper launched over Wireless debugging")
        null
    }

    /**
     * Reads the launch command's output until it reports success or failure. Never waits for the
     * end of the stream: libadb sometimes misses it and would block forever (seen on the emulator).
     */
    private fun readUntilMarker(stream: io.github.muntashirakon.adb.AdbStream): String {
        val input = stream.openInputStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        val until = android.os.SystemClock.uptimeMillis() + LAUNCH_OUTPUT_MS
        while (android.os.SystemClock.uptimeMillis() < until) {
            val text = out.toString(Charsets.UTF_8.name())
            if (text.contains(HelperLaunch.LAUNCHED_MARKER) || text.contains(HelperLaunch.FAILED_MARKER)) return text
            val ready = runCatching { stream.available() }.getOrDefault(0)
            if (ready > 0) {
                val n = runCatching { input.read(buf, 0, minOf(buf.size, ready)) }.getOrDefault(-1)
                if (n < 0) break
                out.write(buf, 0, n)
            } else {
                if (stream.isClosed) break
                android.os.SystemClock.sleep(50)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private inline fun <T> withManager(context: Context, block: (OrbitAdbManager) -> T): T {
        // Pairing needs the platform TLS provider's keying-material export (a hidden API).
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        val m = OrbitAdbManager(context.applicationContext)
        return try { block(m) } finally { runCatching { m.close() } }
    }

    /** Paired = the flag *and* our key still exist (prefs can be restored from backup without the key). */
    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(KEY_PAIRED, false) && File(context.noBackupFilesDir, "adb.key").exists()

    fun setPaired(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_PAIRED, on).apply()

    private const val PREFS = "helper"
    private const val KEY_PAIRED = "paired"
}

/** libadb connection manager with a persistent RSA key and self-signed certificate. */
private class OrbitAdbManager(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        setTimeout(10, TimeUnit.SECONDS)
        val dir = context.noBackupFilesDir
        val keyFile = File(dir, "adb.key")
        val certFile = File(dir, "adb.crt")
        val loaded = runCatching {
            val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val c = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            k to c
        }.getOrNull()
        if (loaded != null) {
            key = loaded.first
            cert = loaded.second
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=OrbitWalker")
            val now = Date()
            val until = Date(now.time + 30L * 365 * 24 * 3600 * 1000)
            val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.time), now, until, name, kp.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            key = kp.private
            cert = JcaX509CertificateConverter().getCertificate(holder)
            writeAtomically(keyFile, key.encoded)
            writeAtomically(certFile, cert.encoded)
        }
    }

    private fun writeAtomically(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "OrbitWalker"
}
