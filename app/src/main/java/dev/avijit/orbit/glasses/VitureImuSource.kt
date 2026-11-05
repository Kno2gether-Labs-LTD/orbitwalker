package dev.avijit.orbit.glasses

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.avijit.orbit.diag.DiagLog
import viture.glasses.VitureLogger
import viture.glasses.callback.General
import viture.glasses.callback.Viture
import viture.glasses.jni.GlassesBridge

/**
 * Owns the USB connection to VITURE Pro 2 and the VITURE SDK bring-up sequence, mirroring
 * SpaceWalker's ModernGlassProvider: initialize(fd, null, pid) → start → register raw → openImu.
 */
class VitureImuSource(
    private val context: Context,
    private val status: GlassesStatus,
    private val onSample: (ImuSample) -> Unit,
) {
    /** UI listener; set by the visible Activity, cleared when it goes away. */
    @Volatile
    var stateListener: ((GlassesState) -> Unit)? = null

    /** Firmware string read once during bring-up (never queried from the UI thread). */
    @Volatile
    var glassesVersion: String? = null
        private set

    private val usb = context.getSystemService(UsbManager::class.java)
    private val thread = HandlerThread("viture-imu").apply { start() }
    private val handler = Handler(thread.looper)

    /** Separate thread so the watchdog still runs if an SDK call hangs the bring-up thread. */
    private val watchdogThread = HandlerThread("viture-watchdog").apply { start() }
    private val watchdog = Handler(watchdogThread.looper)
    @Volatile
    private var lastProgressMs = 0L
    @Volatile
    private var samplesSeen = 0L
    private var connection: UsbDeviceConnection? = null
    @Volatile
    private var sdkStarted = false
    private val parser = RawImuParser()
    private var poseLogged = 0
    private var registered = false

    private val timestampScale = TimestampScale()

    @Volatile
    var lastRaw: FloatArray? = null
        private set

    val gyroInDegrees: Boolean get() = parser.gyroInDegrees

    // Runs on a VITURE native thread: must never throw back into JNI.
    private val rawCallback = Viture.Raw { imu, timestamp, _ ->
        if (imu == null) return@Raw
        try {
            handleRaw(imu, timestamp)
        } catch (t: Throwable) {
            if (callbackErrors++ < 5) DiagLog.e(TAG, "raw IMU callback failed", t)
        }
    }

    private var callbackErrors = 0

    private fun handleRaw(imu: FloatArray, timestamp: Long) {
        val hostNs = SystemClock.elapsedRealtimeNanos()
        val wasKnown = timestampScale.nsPerUnit != null
        timestampScale.observe(timestamp, hostNs)
        if (!wasKnown && timestampScale.nsPerUnit != null) {
            DiagLog.i(TAG, "IMU timestamp unit: ${timestampScale.nsPerUnit} ns per tick")
        }
        val ts = timestampScale.toNs(timestamp, hostNs)
        lastRaw = imu.copyOf()
        if (samplesSeen++ == 0L) DiagLog.i(TAG, "first raw IMU sample (${imu.size} floats) ts=$timestamp: ${imu.joinToString()}")
        val wasDegrees = parser.gyroInDegrees
        val sample = parser.parse(imu, ts) ?: return
        if (!wasDegrees && parser.gyroInDegrees) DiagLog.i(TAG, "gyro unit: deg/s (latched)")
        emit(GlassesEvent.Sample(ts))
        onSample(sample)
    }

    private val poseCallback = Viture.Pose { pose, timestamp ->
        if (pose != null && poseLogged < 5) {
            poseLogged++
            DiagLog.i(TAG, "pose[${pose.size}] @$timestamp = ${pose.joinToString()}")
        }
    }

    private val stateCallback = General.State { state, value ->
        DiagLog.i(TAG, "glasses state change: $state = $value")
    }

    private val logHook = VitureLogger.LogHook { level, tag, message ->
        DiagLog.i("viture", "L$level ${String(tag ?: ByteArray(0))}: ${String(message ?: ByteArray(0)).trimEnd()}")
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.parcelableDevice() ?: return
            if (!isPro2(device)) return
            when (intent.action) {
                ACTION_USB_PERMISSION -> handler.post {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (grant()) bringUp(device)
                    } else {
                        DiagLog.e(TAG, "USB permission denied for ${describe(device)}")
                        emit(GlassesEvent.PermissionDenied)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handler.post { connect(device) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handler.post {
                    DiagLog.i(TAG, "glasses detached")
                    tearDown()
                    emit(GlassesEvent.Detached)
                }
            }
        }
    }

    fun start() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        handler.post { findPro2()?.let { connect(it) } ?: DiagLog.i(TAG, "no VITURE Pro 2 on USB") }
    }

    /**
     * Switches the glasses' display mode on the SDK thread and reports the SDK result
     * (0 = OK) through [onResult] on that thread. Returns false if the SDK isn't running.
     */
    /** The glasses' current display mode as last read from them (-1 = unknown / not connected). */
    @Volatile var displayMode: Int = -1
        private set

    private fun readDisplayMode() {
        displayMode = runCatching { GlassesBridge.nativeGetDisplayMode() }.getOrDefault(-1)
    }

    fun setDisplayMode(mode: Int, onResult: (Int) -> Unit): Boolean {
        if (!sdkStarted) return false
        handler.post {
            val r = try {
                GlassesBridge.nativeSetDisplayMode(mode)
            } catch (t: Throwable) {
                DiagLog.e(TAG, "setDisplayMode threw", t)
                -102
            }
            readDisplayMode()
            DiagLog.i(TAG, "display mode 0x${mode.toString(16)} -> $r (now 0x${displayMode.toString(16)})")
            onResult(r)
        }
        return true
    }

    fun retry() {
        handler.post {
            tearDown()
            emit(GlassesEvent.Retry)
            findPro2()?.let { connect(it) }
        }
    }

    /** Tears down the SDK and waits for it, so a new session can never overlap the old one. */
    fun stop() {
        if (registered) {
            context.unregisterReceiver(usbReceiver)
            registered = false
        }
        handler.post { tearDown() }
        watchdog.removeCallbacksAndMessages(null)
        watchdogThread.quitSafely()
        thread.quitSafely()
        thread.join(STOP_TIMEOUT_MS)
    }

    private fun connect(device: UsbDevice) {
        val current = status.state
        if (sdkStarted || current is GlassesState.Initializing || current is GlassesState.Streaming) {
            DiagLog.i(TAG, "connect ignored: session already active ($current)")
            return
        }
        DiagLog.i(TAG, "found ${describe(device)}")
        if (current != GlassesState.AwaitingPermission) emit(GlassesEvent.Attached)
        if (usb.hasPermission(device)) {
            if (grant()) bringUp(device)
        } else {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(device, pi)
        }
    }

    /**
     * Mirrors SpaceWalker's ModernGlassProvider: initialize → register callbacks → start →
     * wait [IMU_OPEN_DELAY_MS] → closeImu → openImu(MODE_RAW).
     */
    private fun bringUp(device: UsbDevice) {
        tearDown()
        samplesSeen = 0
        armWatchdog()
        val ok = runStep(SdkStep.INITIALIZE) {
            try {
                GlassesBridge.nativeSetLogHook(logHook)
            } catch (t: UnsatisfiedLinkError) {
                DiagLog.e(TAG, "cannot load VITURE native library", t)
                return@runStep LINK_ERROR
            }
            val conn = usb.openDevice(device) ?: return@runStep OPEN_DEVICE_FAILED
            connection = conn
            DiagLog.i(TAG, "fd=${conn.fileDescriptor} pid=0x${device.productId.toString(16)}")
            if (GlassesBridge.nativeXRDeviceInitialize(conn.fileDescriptor, null, device.productId)) 0 else -1
        } && runStep(SdkStep.REGISTER_RAW) {
            runCatching { DiagLog.i(TAG, "device type=${GlassesBridge.nativeGetDeviceType()}") }
            GlassesBridge.nativeRegisterVitureRawCallback(rawCallback)
            GlassesBridge.nativeRegisterViturePoseCallback(poseCallback)
            GlassesBridge.nativeRegisterStateCallback(stateCallback)
            0
        } && runStep(SdkStep.START) {
            GlassesBridge.nativeXRDeviceStart()
            sdkStarted = true
            glassesVersion = runCatching { GlassesBridge.nativeGetGlassesVersion() }.getOrNull()
            // A crash or swipe-away may have left the glasses in 3D: always start in 2D.
            runCatching { GlassesBridge.nativeSetDisplayMode(MODE_2D) }
            readDisplayMode()
            DiagLog.i(TAG, "fw=$glassesVersion; opening IMU in ${IMU_OPEN_DELAY_MS} ms")
            0
        }
        if (ok) handler.postDelayed({ openImu() }, OPEN_IMU_TOKEN, IMU_OPEN_DELAY_MS)
    }

    private fun openImu() {
        if (!sdkStarted) return
        val ok = runStep(SdkStep.OPEN_IMU) {
            val closed = runCatching { GlassesBridge.nativeCloseImu(IMU_MODE_RAW) }.getOrNull()
            DiagLog.i(TAG, "closeImu before open -> $closed; calling nativeOpenImu(mode=$IMU_MODE_RAW, freq=$IMU_FREQUENCY)")
            GlassesBridge.nativeOpenImu(IMU_MODE_RAW, IMU_FREQUENCY).also { DiagLog.i(TAG, "nativeOpenImu returned $it") }
        }
        if (ok) DiagLog.i(TAG, "IMU opened; waiting for samples")
    }

    /** Fires [GlassesEvent.Stalled] if bring-up stops making progress for [STALL_TIMEOUT_MS]. */
    private fun armWatchdog() {
        lastProgressMs = SystemClock.elapsedRealtime()
        watchdog.removeCallbacksAndMessages(null)
        watchdog.post(object : Runnable {
            override fun run() {
                val st = status.state
                if (st !is GlassesState.Initializing) return
                val idle = SystemClock.elapsedRealtime() - lastProgressMs
                if (idle >= STALL_TIMEOUT_MS) {
                    DiagLog.e(TAG, "watchdog: no progress for ${idle} ms at '${st.step}' (raw samples seen: $samplesSeen)")
                    emit(GlassesEvent.Stalled)
                } else {
                    watchdog.postDelayed(this, 1_000)
                }
            }
        })
    }

    /** Runs one SDK step; 0 means success. Reports the outcome to [status]. */
    private inline fun runStep(step: SdkStep, block: () -> Int): Boolean {
        val code = try {
            block()
        } catch (t: Throwable) {
            DiagLog.e(TAG, "step $step threw", t)
            THREW
        }
        return if (code == 0) {
            lastProgressMs = SystemClock.elapsedRealtime()
            DiagLog.i(TAG, "step $step ok")
            emit(GlassesEvent.StepOk(step))
            true
        } else {
            DiagLog.e(TAG, "step $step failed with code $code")
            emit(GlassesEvent.StepFailed(step, code))
            false
        }
    }

    private fun tearDown() {
        handler.removeCallbacksAndMessages(OPEN_IMU_TOKEN)
        if (sdkStarted) {
            // Never leave the glasses in 3D side-by-side when we let go of them.
            runCatching { GlassesBridge.nativeSetDisplayMode(MODE_2D) }
            displayMode = -1
            runCatching { GlassesBridge.nativeCloseImu(IMU_MODE_RAW) }
            runCatching { GlassesBridge.nativeUnregisterVitureRawCallback() }
            runCatching { GlassesBridge.nativeUnregisterViturePoseCallback() }
            runCatching { GlassesBridge.nativeUnregisterStateCallback() }
            runCatching { GlassesBridge.nativeXRDeviceStop() }
            runCatching { GlassesBridge.nativeXRDeviceRelease() }
            sdkStarted = false
            glassesVersion = null
        }
        connection?.close()
        connection = null
    }

    private fun grant(): Boolean {
        val start = status.grantPermission()
        stateListener?.invoke(status.state)
        return start
    }

    private fun emit(e: GlassesEvent) {
        val (before, after) = synchronized(status) { status.state to status.onEvent(e) }
        if (after != before && (after !is GlassesState.Streaming || before !is GlassesState.Streaming)) {
            stateListener?.invoke(after)
        }
    }

    private fun findPro2(): UsbDevice? = usb.deviceList.values.firstOrNull(::isPro2)

    private fun isPro2(d: UsbDevice) = d.vendorId == VITURE_VID && d.productId in PRO2_PIDS

    private fun describe(d: UsbDevice) =
        "VID=0x${d.vendorId.toString(16)} PID=0x${d.productId.toString(16)} ${d.productName ?: ""}".trim()

    @Suppress("DEPRECATION")
    private fun Intent.parcelableDevice(): UsbDevice? =
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

    companion object {
        private const val TAG = "glasses"
        private const val ACTION_USB_PERMISSION = "dev.avijit.orbit.USB_PERMISSION"
        const val VITURE_VID = 0x35CA
        val PRO2_PIDS = setOf(0x1300, 0x1301)

        /** SpaceWalker mz5.J0: 0 = MODE_RAW, 1 = MODE_POSE. */
        const val IMU_MODE_RAW = 0

        /** SpaceWalker mz5.currentImuFrequency default (decompiled mz5.java:193). */
        const val IMU_FREQUENCY = 3

        /** SpaceWalker mz5.get1080pDisplayMode / get3DDisplayMode for non-legacy (Pro 2-class) devices. */
        const val MODE_2D = 0x31
        const val MODE_3D_SBS = 0x37

        private const val LINK_ERROR = -100
        private const val OPEN_DEVICE_FAILED = -101
        private const val THREW = -102
        private const val STOP_TIMEOUT_MS = 2_000L

        /** SpaceWalker mz5.z0(): IMU input starts 2–3 s after start(). */
        private const val IMU_OPEN_DELAY_MS = 3_000L
        private const val STALL_TIMEOUT_MS = 8_000L
        private val OPEN_IMU_TOKEN = Any()
    }
}
