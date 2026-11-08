package dev.avijit.orbit.record

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import dev.avijit.orbit.diag.DiagLog
import java.util.concurrent.Executor

/** Which camera films you, and how its picture maps onto the recording. Decided before it opens. */
data class CameraSpec(
    val id: String,
    val front: Boolean,
    val previewW: Int,
    val previewH: Int,
    /** Degrees to turn the sensor picture upright, for how the phone stands. */
    val rotation: Int,
    /** Mixed reality output size (portrait on a portrait phone). */
    val outW: Int,
    val outH: Int,
    /** Vertical field of view of the recorded picture, before calibration zoom. */
    val vfovDeg: Float,
    /** Below 1 = the wide (0.5×/0.6×) lens of a logical camera. */
    val zoomRatio: Float,
    val label: String,
    /** The sensor's own turn (CameraCharacteristics.SENSOR_ORIENTATION). */
    val sensorDeg: Int = 90,
    /** The camera can expose for faces (CONTROL_SCENE_MODE_FACE_PRIORITY). */
    val facePriority: Boolean = false,
    val distortionCorrection: Boolean = false,
    val hqNoise: Boolean = false,
    val hqEdge: Boolean = false,
)

/** Camera2 feed: a full-size stream into the renderer's texture and a small one for person detection. */
class CameraFeed(private val context: Context, val spec: CameraSpec, private val onMaskFrame: ((android.media.Image, Int) -> Unit)?) {
    private val thread = HandlerThread("rec-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var closed = false

    @SuppressLint("MissingPermission") // checked by the caller
    fun open(target: Surface) {
        // A new texture for an open camera (the glasses' GL context was recreated): just re-target it.
        device?.let { d -> handler.post { runCatching { session?.close() }; startSession(d, target) }; return }
        val mgr = context.getSystemService(CameraManager::class.java)
        mgr.openCamera(spec.id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (closed) { camera.close(); return }
                device = camera
                startSession(camera, target)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
            override fun onError(camera: CameraDevice, error: Int) {
                DiagLog.e(TAG, "camera error $error"); camera.close(); device = null
            }
        })
    }

    private fun startSession(camera: CameraDevice, target: Surface) {
        val outputs = mutableListOf(OutputConfiguration(target))
        val mask = onMaskFrame?.let { cb ->
            ImageReader.newInstance(MASK_W, MASK_H, ImageFormat.YUV_420_888, 3).also { r ->
                r.setOnImageAvailableListener({ ir ->
                    val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    cb(img, spec.rotation) // the callback closes it
                }, handler)
                reader = r
                outputs += OutputConfiguration(r.surface)
            }
        }
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) { s.close(); return }
                session = s
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(target)
                    mask?.let { addTarget(it.surface) }
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    if (spec.zoomRatio != 1f) set(CaptureRequest.CONTROL_ZOOM_RATIO, spec.zoomRatio)
                    // Expose for your face, not the window behind you.
                    if (spec.facePriority) {
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                        set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)
                    }
                    // Stabilisation crops and shifts the picture, which would break the overlay's alignment.
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    // The best picture the phone can make at 30 fps: straight lines (no wide-lens bulge), clean and sharp.
                    if (spec.distortionCorrection) set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
                    if (spec.hqNoise) set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    if (spec.hqEdge) set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    // While recording: exposure and white balance hold still, so the picture doesn't pulse as screens change.
                    set(CaptureRequest.CONTROL_AE_LOCK, locked)
                    set(CaptureRequest.CONTROL_AWB_LOCK, locked)
                }
                builder = req
                runCatching { s.setRepeatingRequest(req.build(), null, handler) }.onFailure { DiagLog.e(TAG, "camera request failed", it) }
                DiagLog.i(TAG, "camera on: ${spec.label} ${spec.previewW}x${spec.previewH} rot ${spec.rotation} vfov %.1f° straighten=${spec.distortionCorrection} hqNoise=${spec.hqNoise} hqEdge=${spec.hqEdge}".format(spec.vfovDeg))
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { DiagLog.e(TAG, "camera session failed") }
        })
        runCatching { camera.createCaptureSession(cfg) }.onFailure { DiagLog.e(TAG, "camera session failed", it) }
    }

    private var builder: CaptureRequest.Builder? = null
    @Volatile private var locked = false

    /** Holds exposure and white balance (recording) or lets them follow the room again. */
    fun lockExposure(on: Boolean) {
        locked = on
        handler.post {
            val b = builder ?: return@post; val s = session ?: return@post
            b.set(CaptureRequest.CONTROL_AE_LOCK, on); b.set(CaptureRequest.CONTROL_AWB_LOCK, on)
            runCatching { s.setRepeatingRequest(b.build(), null, handler) }
        }
    }

    fun close() {
        closed = true
        handler.post {
            runCatching { session?.close() }; runCatching { device?.close() }; runCatching { reader?.close() }
            session = null; device = null; reader = null
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "record"
        const val MASK_W = 640
        const val MASK_H = 360

        /** Picks the camera and works out its geometry, for a phone currently turned [displayDeg]. */
        fun choose(context: Context, front: Boolean, wide: Boolean, displayDeg: Int): CameraSpec? {
            val mgr = context.getSystemService(CameraManager::class.java)
            val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val ids = runCatching { mgr.cameraIdList.toList() }.getOrDefault(emptyList())
            val chars = ids.mapNotNull { id -> runCatching { id to mgr.getCameraCharacteristics(id) }.getOrNull() }
                .filter { (_, c) -> c.get(CameraCharacteristics.LENS_FACING) == facing }
            if (chars.isEmpty()) return null
            fun minZoom(c: CameraCharacteristics) = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
            fun focal(c: CameraCharacteristics) = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 4f
            // Widest view: a logical camera that zooms below 1×, else the shortest lens; otherwise the first.
            val (id, c) = if (wide) chars.minBy { (_, c) -> focal(c) * minZoom(c).coerceAtMost(1f) } else chars.first()
            val zoom = if (wide) minZoom(c).coerceIn(0.5f, 1f) else 1f
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val preview = pick169(sizes) ?: return null
            val sensorDeg = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rotation = CameraOrient.rotation(sensorDeg, displayDeg, front)
            val (outW, outH) = CameraOrient.outputSize(rotation)
            val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val vfov = if (phys != null) {
                CameraOrient.verticalFovDeg(focal(c) * zoom, phys.width, phys.height, rotation, outW.toFloat() / outH)
            } else if (outW > outH) 45f else 70f
            val label = "${if (front) "front" else "back"}${if (zoom < 1f) " wide ${"%.1f".format(zoom)}×" else ""} camera"
            return CameraSpec(id, front, preview.width, preview.height, rotation, outW, outH, vfov, zoom, label, sensorDeg,
                facePriority = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.contains(CameraCharacteristics.CONTROL_SCENE_MODE_FACE_PRIORITY) == true,
                distortionCorrection = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.contains(CameraCharacteristics.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) == true,
                hqNoise = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true,
                hqEdge = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CameraCharacteristics.EDGE_MODE_HIGH_QUALITY) == true)
        }

        private fun pick169(sizes: List<Size>): Size? {
            val wide = sizes.filter { it.width * 9 == it.height * 16 }
            return wide.firstOrNull { it.width == 1920 } ?: wide.filter { it.width <= 1920 }.maxByOrNull { it.width } ?: wide.minByOrNull { it.width }
        }
    }
}

/** Finds you in the camera picture (on-device ML Kit), so screens behind you are hidden by you. */
class PersonMask {
    class Mask(val width: Int, val height: Int, val alpha: ByteArray, val serial: Int)

    @Volatile var latest: Mask? = null
        private set
    @Volatile private var busy = false
    private var serial = 0
    private val segmenter = com.google.mlkit.vision.segmentation.Segmentation.getClient(
        com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.Builder()
            .setDetectorMode(com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.STREAM_MODE)
            .build(),
    )

    /** Camera thread. Skips frames while the last one is still being processed. Closes [image]. */
    fun process(image: android.media.Image, rotation: Int) {
        if (busy) { image.close(); return }
        busy = true
        val input = com.google.mlkit.vision.common.InputImage.fromMediaImage(image, rotation)
        segmenter.process(input)
            .addOnSuccessListener { m ->
                val buf = m.buffer; buf.rewind()
                val out = ByteArray(m.width * m.height)
                for (i in out.indices) out[i] = (buf.float.coerceIn(0f, 1f) * 255f).toInt().toByte()
                latest = Mask(m.width, m.height, out, ++serial)
            }
            .addOnCompleteListener { image.close(); busy = false }
    }

    fun close() = runCatching { segmenter.close() }
}
