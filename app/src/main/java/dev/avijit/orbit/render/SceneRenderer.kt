package dev.avijit.orbit.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.layout.ScreenLayout
import dev.avijit.orbit.layout.ScreenPose
import dev.avijit.orbit.tracking.Quat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Glasses scene: up to [ScreenLayout.SLOTS] app screens (external-OES textures) placed by
 * [SceneSnapshot], a focus outline, a shared cursor, the drift ring with L/R markers, and a
 * head-locked centre dot. With no screens open it shows the Spike A grid panel.
 */
class SceneRenderer(
    private val poseProvider: () -> Quat,
    private val modeProvider: () -> AnchorMode,
    private val panelHost: PanelHost?,
    private val snapshotProvider: () -> SceneSnapshot,
    /** OrbitWalker logo + wordmark for the Logo background (made on the GL thread when needed). */
    private val logoBitmap: () -> android.graphics.Bitmap? = { null },
) : GLSurfaceView.Renderer {

    private val space = SpaceBackground()
    private var logoTexture = 0
    private var logoAspect = 1.6f
    private var logoFailed = false
    private val cardTextures = HashMap<String, Int>()
    private val cardModel = FloatArray(16)
    private val cardMvp = FloatArray(16)

    private val main = Handler(Looper.getMainLooper())
    private var lineProgram = 0
    private var uMvp = 0
    private var uColor = 0
    private var oesProgram = 0
    private var oesMvp = 0
    private var oesTexMatrix = 0
    private var oesUvRect = 0
    private var oesTexel = 0
    private var oesSharpen = 0
    private var frameSharpen = 0.35f
    private var outW = 1920
    private var outH = 1080
    private var currentEye = Eye.MONO
    private val eyeShift = FloatArray(16)
    private val eyeView = FloatArray(16)
    private val headProj = FloatArray(16)
    private var spriteProgram = 0
    private var spriteMvp = 0
    private var spriteAlpha = 0
    private var cursorTexture = 0

    // Recording (GL thread): encoder targets, the camera texture and the mixed reality buffers.
    private enum class Pass { GLASSES, REC_GLASSES, REC_MIXED }
    private var encoders = EncoderSurfaces()
    private var compositor: Compositor? = null
    private var camTex: CameraTexture? = null
    private var mrLayer: Fbo? = null
    private var mrOut: Fbo? = null
    private var mrFresh = false
    private val recThrottle = dev.avijit.orbit.record.FrameThrottle(dev.avijit.orbit.record.Mp4File.FPS)
    private val hudTextures = IntArray(3)
    private val hudUploaded = arrayOfNulls<android.graphics.Bitmap>(3)

    private val projection = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private class Batch(val buffer: FloatBuffer, val count: Int, val mode: Int, val color: FloatArray)

    private val gridBatches = mutableListOf<Batch>()
    private val ringBatches = mutableListOf<Batch>()
    private val headBatches = mutableListOf<Batch>()
    private val panels = arrayOfNulls<AppPanel>(ScreenLayout.SLOTS)

    /** Unit quad (x, y in −0.5..0.5; z 0) with SurfaceTexture UVs (v = 0 at the bottom). */
    private val texQuad = floats(listOf(
        -0.5f, -0.5f, 0f, 0f, 0f, 0.5f, -0.5f, 0f, 1f, 0f, 0.5f, 0.5f, 0f, 1f, 1f,
        -0.5f, -0.5f, 0f, 0f, 0f, 0.5f, 0.5f, 0f, 1f, 1f, -0.5f, 0.5f, 0f, 0f, 1f,
    ))
    private val model2 = FloatArray(16)

    /** Unit quad with bitmap UVs (t = 0 at the top), for Canvas-made textures. */
    private val texQuadTopDown = floats(listOf(
        -0.5f, -0.5f, 0f, 0f, 1f, 0.5f, -0.5f, 0f, 1f, 1f, 0.5f, 0.5f, 0f, 1f, 0f,
        -0.5f, -0.5f, 0f, 0f, 1f, 0.5f, 0.5f, 0f, 1f, 0f, -0.5f, 0.5f, 0f, 0f, 0f,
    ))

    /** Pointer quad: the arrow tip (sprite pixel TIP_X/TIP_Y) sits at the local origin; t = 0 is the bitmap top. */
    private val spriteQuad = run {
        val l = -CursorSprite.TIP_X; val r = 1f - CursorSprite.TIP_X
        val t = CursorSprite.TIP_Y; val b = CursorSprite.TIP_Y - 1f
        floats(listOf(
            l, b, 0f, 0f, 1f, r, b, 0f, 1f, 1f, r, t, 0f, 1f, 0f,
            l, b, 0f, 0f, 1f, r, t, 0f, 1f, 0f, l, t, 0f, 0f, 0f,
        ))
    }
    private val circle = floats((0 until CIRCLE_SEGMENTS).flatMap { i ->
        val a = 2.0 * Math.PI * i / CIRCLE_SEGMENTS
        listOf((cos(a) * 0.5).toFloat(), (sin(a) * 0.5).toFloat(), 0f)
    })

    private val outline = floats(listOf(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        lineProgram = buildProgram(LINE_VS, LINE_FS, "line")
        uMvp = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        uColor = GLES30.glGetUniformLocation(lineProgram, "uColor")
        oesProgram = buildProgram(OES_VS, OES_FS, "oes")
        oesMvp = GLES30.glGetUniformLocation(oesProgram, "uMvp")
        oesTexMatrix = GLES30.glGetUniformLocation(oesProgram, "uTexMatrix")
        oesUvRect = GLES30.glGetUniformLocation(oesProgram, "uUvRect")
        oesTexel = GLES30.glGetUniformLocation(oesProgram, "uTexel")
        oesSharpen = GLES30.glGetUniformLocation(oesProgram, "uSharpen")
        spriteProgram = buildProgram(SPRITE_VS, SPRITE_FS, "sprite")
        spriteMvp = GLES30.glGetUniformLocation(spriteProgram, "uMvp")
        spriteAlpha = GLES30.glGetUniformLocation(spriteProgram, "uAlpha")
        cursorTexture = CursorSprite.upload()
        space.create()
        logoTexture = 0
        cardTextures.clear() // textures died with the old context
        encoders = EncoderSurfaces(); compositor = null; camTex = null; mrLayer = null; mrOut = null
        hudTextures.fill(0); hudUploaded.fill(null)
        artTextures.fill(0) // keyboard + dock textures died with the old context
        buddyTexture = 0
        artUploaded.fill(null)
        GLES30.glClearColor(0f, 0f, 0f, 1f) // black = transparent on optical see-through glasses
        GLES30.glLineWidth(3f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        buildStaticScene()
        createPanels()
    }

    private fun createPanels() {
        val host = panelHost ?: return
        if (panels.any { it != null }) {
            // New GL context: the old textures are gone with it.
            panels.forEach { p -> runCatching { p?.surface?.release(); p?.surfaceTexture?.release() } }
            main.post { host.onPanelSurfacesGone() }
        }
        for (slot in 0 until ScreenLayout.SLOTS) {
            val panel = AppPanel.create(DEFAULT_W, DEFAULT_H)
            panels[slot] = panel
            main.post {
                host.onPanelSurface(slot, panel.surface) { w, h ->
                    panel.surfaceTexture.setDefaultBufferSize(w, h)
                    panel.width = w; panel.height = h
                }
            }
        }
    }

    /** GL thread: frees every panel (called before the presentation goes away). */
    fun release() {
        panels.forEachIndexed { i, p -> runCatching { p?.release() }; panels[i] = null }
        camTex?.release(); camTex = null
        mrLayer?.release(); mrLayer = null; mrOut?.release(); mrOut = null
        encoders.sync(emptyList())
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        outW = width
        outH = height
        DiagLog.i(TAG, "glasses output ${width}x$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val headView = ViewPose.viewMatrix(poseProvider(), modeProvider())
        val scene = snapshotProvider()
        frameSharpen = scene.sharpen
        panels.forEach { it?.update() }
        val eyes = if (scene.stereo) STEREO_EYES else MONO_EYES
        frameTimeS = OrbitSky.clockS() // shared with planet clicks
        frameNowMs = android.os.SystemClock.uptimeMillis()
        val stereo = eyes.size > 1
        val recordNow = prepareRecording(scene)
        GLES30.glViewport(0, 0, outW, outH)
        if (stereo) GLES30.glEnable(GLES30.GL_SCISSOR_TEST) // keep each eye (and wide lines) in its half
        for (eye in eyes) {
            val vpRect = Stereo.viewport(eye, outW, outH)
            GLES30.glViewport(vpRect[0], vpRect[1], vpRect[2], vpRect[3])
            if (stereo) GLES30.glScissor(vpRect[0], vpRect[1], vpRect[2], vpRect[3])
            val aspect = if (eye == Eye.MONO) outW.toFloat() / outH else Stereo.eyeAspect()
            eyeAspect = aspect
            Matrix.perspectiveM(projection, 0, VERTICAL_FOV_DEG, aspect, 0.1f, 20f)
            // Eye offset in view space (rotation-only head, so this is pure parallax).
            Matrix.setIdentityM(eyeShift, 0)
            Matrix.translateM(eyeShift, 0, Stereo.eyeShiftX(eye, scene.ipdM), 0f, 0f)
            Matrix.multiplyMM(eyeView, 0, eyeShift, 0, headView, 0)
            Matrix.multiplyMM(vp, 0, projection, 0, eyeView, 0)
            Matrix.multiplyMM(headProj, 0, projection, 0, eyeShift, 0)
            currentEye = eye
            drawEye(scene, headView)
        }
        if (stereo) GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        captureRequest?.let { file -> captureRequest = null; capture(file) }
        if (recordNow) writeRecording(scene, headView)
    }

    // ---------------------------------------------------------------- recording

    /**
     * Start of frame: keeps the camera texture in step with the recorder and, at 30 fps, draws the
     * mixed reality view (for its file and the in-glasses monitor). True if this frame is recorded.
     */
    private fun prepareRecording(scene: SceneSnapshot): Boolean {
        val rec = dev.avijit.orbit.record.Recorder
        rec.lastFrameMs = frameNowMs
        val files = rec.files
        encoders.sync(files)
        val feed = rec.camera
        if (camTex?.feed !== feed) {
            camTex?.release(); camTex = null
            if (feed != null) camTex = runCatching { CameraTexture(feed) }.onFailure { DiagLog.e(TAG, "camera texture failed", it) }.getOrNull()
        }
        camTex?.update()
        if (files.isEmpty() && !rec.monitorOn) return false
        if (!recThrottle.take(System.nanoTime())) return false
        val mixed = files.firstOrNull { it.view == dev.avijit.orbit.record.RecordingView.MIXED }
        val cam = camTex
        mrFresh = false
        if ((mixed != null || rec.monitorOn) && cam != null) {
            // Full size for the monitor too: lining up needs a sharp picture, and it's what the file will get.
            val w = mixed?.width ?: cam.spec.outW
            val h = mixed?.height ?: cam.spec.outH
            drawMixed(scene, cam, w, h, rec)
        }
        return files.isNotEmpty()
    }

    private fun drawMixed(scene: SceneSnapshot, cam: CameraTexture, w: Int, h: Int, rec: dev.avijit.orbit.record.Recorder) {
        if (mrLayer?.width != w || mrLayer?.height != h) {
            mrLayer?.release(); mrOut?.release()
            mrLayer = Fbo(w, h, withDepth = true, mipmaps = true); mrOut = Fbo(w, h, withDepth = false)
        }
        val layer = mrLayer!!; val out = mrOut!!
        val comp = compositor ?: Compositor().also { compositor = it }
        val rig = rec.rig
        val aspect = w.toFloat() / h
        // 1. Your screens as the phone's camera sees them, over a transparent background (with depth).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, layer.framebuffer)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        val proj = dev.avijit.orbit.record.CameraRig.projection(dev.avijit.orbit.record.CameraRig.zoomed(cam.spec.vfovDeg, rig.zoom), aspect)
        val view = dev.avijit.orbit.record.CameraRig.viewMatrix(rig)
        System.arraycopy(proj, 0, projection, 0, 16)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        System.arraycopy(projection, 0, headProj, 0, 16)
        eyeAspect = aspect; currentEye = Eye.MONO
        mixedVirtual = rec.background == dev.avijit.orbit.record.MixedBackground.VIRTUAL
        prepareStage(scene, rec, dev.avijit.orbit.record.CameraRig.zoomed(cam.spec.vfovDeg, rig.zoom), aspect)
        drawEye(scene, view, Pass.REC_MIXED)
        // 2. Camera picture + screens, with you in front of whatever is behind you.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, out.framebuffer)
        GLES30.glViewport(0, 0, w, h)
        val hasMask = comp.uploadMask(rec.mask?.latest)
        comp.composite(cam, layer, aspect, hasMask, dev.avijit.orbit.record.CameraRig.personDepth(rig), MR_OPACITY, virtual = mixedVirtual,
            glow = if (stageMode == dev.avijit.orbit.record.ScreenStaging.REAL) 0f else SCREEN_GLOW)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        mrFresh = true
    }

    /** End of frame: the glasses view and the mixed reality view into their files. */
    private fun writeRecording(scene: SceneSnapshot, headView: FloatArray) {
        val now = System.nanoTime()
        for (file in dev.avijit.orbit.record.Recorder.files) {
            if (!encoders.begin(file)) continue
            GLES30.glViewport(0, 0, file.width, file.height)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            if (file.view == dev.avijit.orbit.record.RecordingView.GLASSES) {
                val aspect = file.width.toFloat() / file.height
                Matrix.perspectiveM(projection, 0, VERTICAL_FOV_DEG, aspect, 0.1f, 20f)
                Matrix.multiplyMM(vp, 0, projection, 0, headView, 0)
                System.arraycopy(projection, 0, headProj, 0, 16)
                eyeAspect = aspect; currentEye = Eye.MONO
                drawEye(scene, headView, Pass.REC_GLASSES)
                val cam = camTex
                if (dev.avijit.orbit.record.Recorder.faceBubble && cam != null && cam.hasFrame) {
                    val size = (file.height * 0.28f).toInt(); val m = (file.height * 0.04f).toInt()
                    GLES30.glViewport(file.width - size - m, m, size, size)
                    (compositor ?: Compositor().also { compositor = it }).bubble(cam)
                }
            } else if (file.view == dev.avijit.orbit.record.RecordingView.CLEAN) {
                drawClean(scene, file.width, file.height)
            } else {
                val out = mrOut
                if (out != null && mrFresh) (compositor ?: Compositor().also { compositor = it }).copy(out.color)
            }
            runCatching { encoders.end(file, now) }.onFailure { DiagLog.e(TAG, "encoder frame failed", it) }
        }
        GLES30.glViewport(0, 0, outW, outH)
    }

    /** Glasses only: the red REC pill (top left) and, while lining up the camera, the camera monitor. */
    private fun drawRecordingHud() {
        val rec = dev.avijit.orbit.record.Recorder
        rec.statusArt?.let { headLocked(it, 0, -0.43f, 0.27f, 0.035f) }
        if (!rec.monitorOn || rec.studioOpen) return // the studio screen carries the picture instead
        val out = mrOut ?: return
        // Longest side 0.36 m at 1.5 m: landscape or portrait, it sits in the lower-right corner.
        val aspect = out.width.toFloat() / out.height
        val w = if (aspect >= 1f) 0.36f else 0.36f * aspect; val h = w / aspect
        val cx = 0.52f - w / 2; val cy = -0.27f + h / 2 + 0.045f
        Matrix.setIdentityM(cardModel, 0)
        Matrix.translateM(cardModel, 0, cx, cy, -CARD_DISTANCE_M)
        Matrix.scaleM(cardModel, 0, w, h, 1f)
        Matrix.multiplyMM(cardMvp, 0, headProj, 0, cardModel, 0)
        spriteDraw(out.color, cardMvp, texQuad, 1f)
        rec.monitorArt?.let { art -> headLocked(art, 1, 0.16f, -0.29f, 0.36f * art.height / art.width, width = 0.36f) }
    }

    /** The mixed reality picture on the camera studio screen, fitted into its picture area. */
    private fun drawStudioPicture(pose: dev.avijit.orbit.layout.ScreenPose) {
        val out = mrOut ?: return
        if (!dev.avijit.orbit.record.Recorder.monitorOn) return
        val a = dev.avijit.orbit.record.StudioLayout.PICTURE
        // The area in metres, then the picture's shape fitted inside it.
        val areaW = (a[2] - a[0]) * pose.widthM; val areaH = (a[3] - a[1]) * pose.heightM
        val aspect = out.width.toFloat() / out.height
        val (w, h) = if (areaW / areaH > aspect) (areaH * aspect) to areaH else areaW to (areaW / aspect)
        modelFor(pose, 1f)
        val cx = (a[0] + a[2]) / 2 - 0.5f; val cy = 0.5f - (a[1] + a[3]) / 2
        Matrix.translateM(model, 0, cx, cy, 0f)
        Matrix.scaleM(model, 0, w / pose.widthM, h / pose.heightM, 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST) // drawn right after its own screen, onto it
        spriteDraw(out.color, mvp, texQuad, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun rotationOnly(view: FloatArray): FloatArray = rotView.also {
        System.arraycopy(view, 0, it, 0, 16); it[12] = 0f; it[13] = 0f; it[14] = 0f
    }
    private val rotView = FloatArray(16)
    /** This frame's mixed reality view puts you in the virtual space instead of the room. */
    private var mixedVirtual = false

    // ---------------------------------------------------------------- clean screen file

    /** The screen in the clean file: the one you're using (the studio never). */
    private var cleanSlot = -1
    private val cleanView = FloatArray(16)

    /** Just the screen you're using, square-on and filling the file (letterboxed if its shape differs), with the pointer. */
    private fun drawClean(scene: SceneSnapshot, w: Int, h: Int) {
        val apps = scene.screens.filter { !it.studio }
        val s = apps.firstOrNull { it.focused } ?: apps.firstOrNull { it.slot == cleanSlot } ?: apps.firstOrNull() ?: return
        cleanSlot = s.slot
        val panel = panels.getOrNull(s.slot) ?: return
        if (!panel.hasFrame) return
        val p = s.pose
        val shape = p.widthM / p.heightM
        val (vw, vh) = if (w.toFloat() / h > shape) (h * shape).toInt() to h else w to (w / shape).toInt()
        GLES30.glViewport((w - vw) / 2, (h - vh) / 2, vw, vh)
        // A camera square in front of the screen, seeing exactly its edges.
        val (r, u, f) = p.basis(); val c = p.centre()
        Matrix.setLookAtM(cleanView, 0, c[0] - f[0], c[1] - f[1], c[2] - f[2], c[0], c[1], c[2], u[0], u[1], u[2])
        Matrix.orthoM(projection, 0, -p.widthM / 2, p.widthM / 2, -p.heightM / 2, p.heightM / 2, 0.01f, 5f)
        Matrix.multiplyMM(vp, 0, projection, 0, cleanView, 0)
        currentEye = Eye.MONO
        modelFor(p, 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        drawTexture(panel, Stereo.contentUv(s.content, currentEye, if (s.content == ContentMode.FLAT) 0f else Stereo.VIDEO_BAR))
        scene.cursor?.takeIf { dev.avijit.orbit.record.Staging.onScreen(it.centre(), p) }?.let { drawPointer(it, scene) }
    }

    // ---------------------------------------------------------------- staging (mixed reality only)

    private var stageMode = dev.avijit.orbit.record.ScreenStaging.REAL
    private val stageCam = FloatArray(3)
    /** Framed: the screen in the shot, and where it is (eased, so moves and swaps glide). */
    private var heroSlot = -1
    private var heroPlace: dev.avijit.orbit.record.Placement? = null

    private fun prepareStage(scene: SceneSnapshot, rec: dev.avijit.orbit.record.Recorder, vfovDeg: Float, aspect: Float) {
        stageMode = rec.staging
        System.arraycopy(dev.avijit.orbit.record.CameraRig.position(rec.rig), 0, stageCam, 0, 3)
        if (stageMode != dev.avijit.orbit.record.ScreenStaging.FRAMED) { heroPlace = null; heroSlot = -1; return }
        val apps = scene.screens.filter { !it.studio }
        val pick = apps.firstOrNull { it.focused } ?: apps.firstOrNull { it.slot == heroSlot } ?: apps.firstOrNull()
        if (pick == null) { heroPlace = null; heroSlot = -1; return }
        val target = dev.avijit.orbit.record.Staging.framed(pick.pose, rec.rig, vfovDeg, aspect, rec.heroFill, rec.heroSide)
        val prev = heroPlace
        heroPlace = if (prev == null || pick.slot != heroSlot) dev.avijit.orbit.record.Staging.entrance(target)
        else dev.avijit.orbit.record.Staging.lerp(prev, target, HERO_EASE)
        heroSlot = pick.slot
    }

    /** Where [s] goes in the mixed reality view; null = left out of the shot. */
    private fun staged(s: ScreenDraw): dev.avijit.orbit.record.Placement? = when (stageMode) {
        dev.avijit.orbit.record.ScreenStaging.FRAMED -> if (s.slot == heroSlot) heroPlace else null
        dev.avijit.orbit.record.ScreenStaging.BIGGER -> dev.avijit.orbit.record.Staging.bigger(s.pose, stageCam)
        dev.avijit.orbit.record.ScreenStaging.REAL -> dev.avijit.orbit.record.Placement.of(s.pose)
    }

    /** A panel attached to [anchor] (title bar, dock, pointer), moved along with it. Null = hidden. */
    private fun stagedWith(pose: ScreenPose, anchor: ScreenDraw?): dev.avijit.orbit.record.Placement? {
        val a = anchor ?: return null
        val to = staged(a) ?: return null
        return dev.avijit.orbit.record.Staging.attached(pose, a.pose, to)
    }

    private var buddyTexture = 0

    /** The agent's buddy standing on the dock's top edge; with no dock, it shows above the caption while Orbit is busy. */
    private fun drawBuddy(dock: dev.avijit.orbit.layout.ScreenPose?) {
        if (!dev.avijit.orbit.agent.OrbitBuddy.enabled || !dev.avijit.orbit.agent.OrbitAgent.paired) return
        val agentBusy = dev.avijit.orbit.agent.OrbitAgent.inSession || dev.avijit.orbit.agent.OrbitAgent.phase != dev.avijit.orbit.agent.OrbitAgent.Phase.IDLE
        if (dock == null && !agentBusy) return
        val f = dev.avijit.orbit.agent.OrbitBuddy.frame(frameNowMs)
        if (buddyTexture == 0) {
            val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); buddyTexture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, buddyTexture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, f.art, 0)
        } else if (f.fresh) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, buddyTexture)
            android.opengl.GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, f.art)
        }
        if (dock == null) {
            // Head-locked, just above the caption.
            Matrix.setIdentityM(cardModel, 0)
            Matrix.translateM(cardModel, 0, 0f, -0.09f, -CARD_DISTANCE_M)
            Matrix.scaleM(cardModel, 0, 0.12f, 0.12f, 1f)
            Matrix.multiplyMM(cardMvp, 0, headProj, 0, cardModel, 0)
            spriteDraw(buddyTexture, cardMvp, texQuadTopDown, 1f)
            return
        }
        val q = dock.heightM * 0.95f // the picture's size; the buddy itself is about half of it
        val dist = (dock.distanceM - 0.01f).coerceAtLeast(0.3f)
        val xOff = f.x * (dock.widthM / 2f - q * 0.3f)
        val yOff = dock.heightM / 2f + q / 2f - q * (1f - dev.avijit.orbit.agent.OrbitBuddy.FOOT_LINE) + q * 0.08f
        val pose = dev.avijit.orbit.layout.ScreenPose(
            dock.yawDeg + Math.toDegrees(kotlin.math.atan2(xOff.toDouble(), dist.toDouble())).toFloat(),
            dock.pitchDeg + Math.toDegrees(kotlin.math.atan2(yOff.toDouble(), dist.toDouble())).toFloat(),
            dist, q, q,
        )
        modelFor(pose, null, 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        // In front of everything around it, and its see-through corners never hide the pointer.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        spriteDraw(buddyTexture, mvp, texQuadTopDown, 1f)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun headLocked(art: android.graphics.Bitmap, slot: Int, x: Float, y: Float, h: Float, width: Float = h * art.width / art.height) {
        if (hudUploaded[slot] !== art) {
            if (hudTextures[slot] == 0) {
                val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); hudTextures[slot] = ids[0]
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hudTextures[slot])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, art, 0)
            hudUploaded[slot] = art
        }
        Matrix.setIdentityM(cardModel, 0)
        Matrix.translateM(cardModel, 0, x, y, -CARD_DISTANCE_M)
        Matrix.scaleM(cardModel, 0, width, h, 1f)
        Matrix.multiplyMM(cardMvp, 0, headProj, 0, cardModel, 0)
        spriteDraw(hudTextures[slot], cardMvp, texQuadTopDown, 1f)
    }

    private fun spriteDraw(texture: Int, m: FloatArray, quad: FloatBuffer, alpha: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(spriteProgram)
        GLES30.glUniformMatrix4fv(spriteMvp, 1, false, m, 0)
        GLES30.glUniform1f(spriteAlpha, alpha)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        quad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, quad)
        quad.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(1)
        quad.position(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Test harness: save the next frame (exactly what the glasses show) as a PNG. */
    @Volatile var captureRequest: java.io.File? = null

    private fun capture(file: java.io.File) {
        val buf = java.nio.ByteBuffer.allocateDirect(outW * outH * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val bmp = android.graphics.Bitmap.createBitmap(outW, outH, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, outW, outH, android.graphics.Matrix().apply { preScale(1f, -1f) }, false)
        runCatching { file.outputStream().use { flipped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            .onSuccess { DiagLog.i("test", "frame saved: ${file.absolutePath}") }
            .onFailure { DiagLog.e("test", "frame save failed", it) }
        bmp.recycle(); flipped.recycle()
    }

    /** The current eye's width / height, for placing head-locked hints. */
    private var eyeAspect = 16f / 9f

    /** Sampled once per frame so both eyes animate identically. */
    private var frameTimeS = 0f
    private var frameNowMs = 0L

    private fun drawEye(scene: SceneSnapshot, headView: FloatArray, pass: Pass = Pass.GLASSES) {
        val glasses = pass == Pass.GLASSES
        // Mixed reality: the camera picture is the background, and nothing is locked to your head.
        // (or, with the virtual space chosen, the space you see, seen from the phone).
        if (pass != Pass.REC_MIXED) drawBackground(scene, headView) // at infinity: same for both eyes
        else if (mixedVirtual) drawBackground(scene, rotationOnly(headView))

        var drewScreen = false
        val stage = pass == Pass.REC_MIXED && stageMode != dev.avijit.orbit.record.ScreenStaging.REAL
        val framed = stage && stageMode == dev.avijit.orbit.record.ScreenStaging.FRAMED
        for (s in scene.screens) {
            if (s.studio && !glasses) continue // your recording controls stay out of the recording
            val panel = panels.getOrNull(s.slot) ?: continue
            if (!panel.hasFrame) continue
            val place = if (stage) staged(s) ?: continue else null
            drewScreen = true
            modelFor(s.pose, place, 1f)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            drawTexture(panel, Stereo.contentUv(s.content, currentEye, if (s.content == ContentMode.FLAT) 0f else Stereo.VIDEO_BAR))
            if (s.focused && !framed) {
                modelFor(s.pose, place, 1f + OUTLINE_MARGIN)
                Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                drawLine(outline, 4, GLES30.GL_LINE_LOOP, FOCUS, mvp)
            }
            if (s.studio) drawStudioPicture(s.pose)
        }

        // Never in recordings: the pressed-key highlight (and the pointer on it) would show what you type, passwords included.
        if (glasses) scene.keyboard?.let { kb -> scene.keyboardArt?.let { art -> drawKeyboard(kb, art) } }
        val focusedScreen = scene.screens.firstOrNull { it.focused }
        if (!framed) scene.dock?.let { d -> scene.dockArt?.let { art ->
            val centre = scene.screens.filter { !it.studio }.minByOrNull { kotlin.math.abs(it.pose.yawDeg) }
            val place = if (stage) stagedWith(d, centre) else null
            if (!stage || place != null) drawArtPanel(d, art, 1, place = place)
        } }
        // The title bar belongs to the focused screen: none in recordings when that's the studio.
        // Framed shots stay clean: no title bar (its hints read as clutter in a video).
        if ((glasses || focusedScreen?.studio != true) && !framed) scene.windowBar?.let { b -> scene.windowBarArt?.let { art ->
            val place = if (stage) stagedWith(b, focusedScreen) else null
            if (!stage || place != null) drawArtPanel(b, art, 2, place = place)
        } }
        if ((pass != Pass.REC_MIXED || mixedVirtual) && scene.background == BackgroundMode.ORBIT && scene.orbitApps && scene.backgroundLevel > 0.01f) drawOrbitLabels(scene.backgroundLevel, OrbitSky.skyTime(frameTimeS, scene.orbitStill))

        // Orbit, the agent's buddy, on top of the dock (glasses only): always in front of the screens.
        if (glasses) drawBuddy(scene.dock)

        GLES30.glUseProgram(lineProgram)
        if (glasses && !drewScreen && scene.showGrid && scene.showMarkers) gridBatches.forEach { draw(it, vp) }
        if (glasses && scene.showMarkers) ringBatches.forEach { draw(it, vp) }
        scene.cursor?.let { c ->
            val place = if (!stage) null else {
                val on = scene.screens.firstOrNull { !it.studio && dev.avijit.orbit.record.Staging.onScreen(c.centre(), it.pose) }
                stagedWith(c, on ?: if (framed) null else focusedScreen)
            }
            if ((glasses || scene.keyboard == null) && (!stage || place != null)) drawPointer(c, scene, place)
            if (glasses) drawCursorHint(c, headView)
        }
        if (!glasses) return // recordings: no head-locked aids, notifications (private) or recording HUD
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        // The green centre dot is a head-tracking test aid: only with the alignment markers.
        if (scene.showHeadDot && scene.showMarkers) headBatches.forEach { draw(it, headProj) }
        drawNotifications()
        drawRecordingHud()
        // The Orbit agent's caption, low in the middle of the view (glasses only: never recorded).
        dev.avijit.orbit.agent.OrbitAgent.captionArt?.let { headLocked(it, 2, 0f, -0.19f, 0.5f * it.height / it.width, width = 0.5f) }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawBackground(scene: SceneSnapshot, view: FloatArray) {
        when (scene.background) {
            BackgroundMode.BLACK -> Unit
            BackgroundMode.SPACE, BackgroundMode.ORBIT -> {
                space.draw(projection, view, frameTimeS, scene.backgroundLevel, model2, orbit = scene.background == BackgroundMode.ORBIT, skyT = OrbitSky.skyTime(frameTimeS, scene.orbitStill))
            }
            BackgroundMode.LOGO -> drawLogo(scene.backgroundLevel)
        }
    }

    /** Big OrbitWalker logo pinned 3 m ahead, softly lit by [level]. */
    private fun drawLogo(level: Float) {
        if (level <= 0.001f) return
        if (logoTexture == 0) {
            if (logoFailed) return
            val b = logoBitmap() ?: run { logoFailed = true; return }
            logoAspect = b.width.toFloat() / b.height
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            b.recycle()
            logoTexture = ids[0]
        }
        val w = 1.3f
        modelFor(dev.avijit.orbit.layout.ScreenPose(0f, -1f, 3f, w, w / logoAspect), 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST) // background: must never hide screens
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(spriteProgram)
        GLES30.glUniformMatrix4fv(spriteMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(spriteAlpha, level)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, logoTexture)
        texQuadTopDown.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, texQuadTopDown)
        texQuadTopDown.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, texQuadTopDown)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(1)
        texQuadTopDown.position(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** The in-glasses keyboard: a textured panel under the focused screen. */
    private fun drawKeyboard(pose: dev.avijit.orbit.layout.ScreenPose, art: android.graphics.Bitmap) = drawArtPanel(pose, art, 0)

    /** Textured panels drawn from bitmaps: 0 = keyboard, 1 = app dock, 2 = window bar, 3+ = Orbit sky labels. */
    private val artTextures = IntArray(3 + OrbitSky.PLANETS.size)
    private val artUploaded = arrayOfNulls<android.graphics.Bitmap>(3 + OrbitSky.PLANETS.size)
    /** Planet captions for the Orbit sky (made once, on the GL thread). */
    private val orbitLabels by lazy { OrbitSky.PLANETS.map { OrbitLabelArt.render(it) } }

    /** Each family app's caption floats under its planet, dimmed with the background. */
    private fun drawOrbitLabels(level: Float, skyT: Float) {
        OrbitSky.PLANETS.forEachIndexed { i, p ->
            drawArtPanel(OrbitSky.labelPose(p, skyT), orbitLabels[i], 3 + i, alpha = level.coerceIn(0f, 1f) * 0.9f)
        }
    }

    private fun drawArtPanel(pose: dev.avijit.orbit.layout.ScreenPose, art: android.graphics.Bitmap, which: Int, alpha: Float = 1f, place: dev.avijit.orbit.record.Placement? = null) {
        if (artTextures[which] == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            artTextures[which] = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        }
        val tex = artTextures[which]
        if (artUploaded[which] !== art) { // a new picture (shift, page, key press, hover)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, art, 0)
            artUploaded[which] = art
        }
        modelFor(pose, place, 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(spriteProgram)
        GLES30.glUniformMatrix4fv(spriteMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(spriteAlpha, alpha)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        texQuadTopDown.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, texQuadTopDown)
        texQuadTopDown.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, texQuadTopDown)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(1)
        texQuadTopDown.position(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Head-locked notification cards, low in the view: slide up and fade in, then fade out. */
    private fun drawNotifications() {
        val now = frameNowMs
        val cards = dev.avijit.orbit.notify.NotificationFeed.visible(now)
        // Keyed per card instance: an updated notification (same key, new text) gets a new texture.
        val live = cards.map { it.key + "@" + it.postedMs }.toSet()
        cardTextures.keys.filter { it !in live }.forEach { key ->
            GLES30.glDeleteTextures(1, intArrayOf(cardTextures.remove(key)!!), 0)
        }
        if (cards.isEmpty()) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(spriteProgram)
        cards.asReversed().forEachIndexed { i, card ->
            val tex = cardTextures.getOrPut(card.key + "@" + card.postedMs) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, card.art, 0)
                ids[0]
            }
            val age = now - card.postedMs
            val appear = (age / 300f).coerceIn(0f, 1f)
            val fade = 1f - ((age - card.showMs) / dev.avijit.orbit.notify.NotificationFeed.FADE_MS.toFloat()).coerceIn(0f, 1f)
            val alpha = appear * fade
            val w = CARD_W_M; val h = w * card.art.height / card.art.width
            val y = CARD_BOTTOM_M + i * (h + 0.015f) - (1f - appear) * 0.04f
            Matrix.setIdentityM(cardModel, 0)
            Matrix.translateM(cardModel, 0, 0f, y + h / 2, -CARD_DISTANCE_M)
            Matrix.scaleM(cardModel, 0, w, h, 1f)
            Matrix.multiplyMM(cardMvp, 0, headProj, 0, cardModel, 0)
            GLES30.glUniformMatrix4fv(spriteMvp, 1, false, cardMvp, 0)
            GLES30.glUniform1f(spriteAlpha, alpha)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            texQuadTopDown.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, texQuadTopDown)
            texQuadTopDown.position(3)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, texQuadTopDown)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
            GLES30.glDisableVertexAttribArray(1)
            texQuadTopDown.position(0)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawPointer(c: ScreenPose, scene: SceneSnapshot, place: dev.avijit.orbit.record.Placement? = null) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA) // Android bitmaps are premultiplied
        // Away from screens the pointer stays full size and gets a soft halo, so it never gets lost in space.
        modelFor(c, place, if (scene.cursorOverScreen) 1f else 1.15f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        if (!scene.cursorOverScreen) {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            modelFor(c, place, 0.75f)
            Matrix.multiplyMM(model2, 0, vp, 0, model, 0)
            drawLine(circle, CIRCLE_SEGMENTS, GLES30.GL_LINE_LOOP, HALO, model2)
            modelFor(c, place, 1.15f)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        if (scene.clickAgeMs >= 0) {
            val t = scene.clickAgeMs / dev.avijit.orbit.ScreensSession.RIPPLE_MS.toFloat()
            val ease = 1f - (1f - t) * (1f - t)
            val ring = floatArrayOf(FOCUS[0], FOCUS[1], FOCUS[2], 1f - t)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            modelFor(c, place, 0.25f + ease * 0.9f)
            Matrix.multiplyMM(model2, 0, vp, 0, model, 0)
            drawLine(circle, CIRCLE_SEGMENTS, GLES30.GL_LINE_LOOP, ring, model2)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        GLES30.glUseProgram(spriteProgram)
        GLES30.glUniformMatrix4fv(spriteMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(spriteAlpha, 1f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cursorTexture)
        spriteQuad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, spriteQuad)
        spriteQuad.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, spriteQuad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(1)
        spriteQuad.position(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** When the cursor is out of view, an arrow at the edge of the glasses points the way to it. */
    private fun drawCursorHint(c: ScreenPose, headView: FloatArray) {
        val y = Math.toRadians(c.yawDeg.toDouble()); val p = Math.toRadians(c.pitchDeg.toDouble())
        val dx = (kotlin.math.sin(y) * kotlin.math.cos(p)).toFloat()
        val dy = kotlin.math.sin(p).toFloat()
        val dz = (-kotlin.math.cos(y) * kotlin.math.cos(p)).toFloat()
        val v = floatArrayOf(
            headView[0] * dx + headView[4] * dy + headView[8] * dz,
            headView[1] * dx + headView[5] * dy + headView[9] * dz,
            headView[2] * dx + headView[6] * dy + headView[10] * dz,
        )
        val a = dev.avijit.orbit.layout.CursorHint.edgeArrow(v, VERTICAL_FOV_DEG, eyeAspect) ?: return
        val tanV = kotlin.math.tan(Math.toRadians(VERTICAL_FOV_DEG / 2.0)).toFloat()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, a.x * tanV * eyeAspect * HINT_DIST_M, a.y * tanV * HINT_DIST_M, -HINT_DIST_M)
        Matrix.rotateM(model, 0, a.angleDeg, 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, headProj, 0, model, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        drawLine(hintArrow, 3, GLES30.GL_TRIANGLES, HINT, mvp)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** A small arrow pointing along +x, sized for [HINT_DIST_M]. */
    private val hintArrow = floats(listOf(0.026f, 0f, 0f, -0.014f, 0.0105f, 0f, -0.014f, -0.0105f, 0f))

    /** Column-major model matrix: unit quad → screen of [pose], scaled by [grow]. */
    private fun modelFor(p: dev.avijit.orbit.record.Placement, grow: Float) {
        val w = p.w * grow; val h = p.h * grow
        model[0] = p.r[0] * w; model[1] = p.r[1] * w; model[2] = p.r[2] * w; model[3] = 0f
        model[4] = p.u[0] * h; model[5] = p.u[1] * h; model[6] = p.u[2] * h; model[7] = 0f
        model[8] = -p.f[0]; model[9] = -p.f[1]; model[10] = -p.f[2]; model[11] = 0f
        model[12] = p.c[0]; model[13] = p.c[1]; model[14] = p.c[2]; model[15] = 1f
    }

    /** [place] when staged for the camera, else where [pose] really is. */
    private fun modelFor(pose: ScreenPose, place: dev.avijit.orbit.record.Placement?, grow: Float) =
        if (place != null) modelFor(place, grow) else modelFor(pose, grow)

    private fun modelFor(pose: ScreenPose, grow: Float) {
        val (r, u, f) = pose.basis()
        val c = pose.centre()
        val w = pose.widthM * grow; val h = pose.heightM * grow
        model[0] = r[0] * w; model[1] = r[1] * w; model[2] = r[2] * w; model[3] = 0f
        model[4] = u[0] * h; model[5] = u[1] * h; model[6] = u[2] * h; model[7] = 0f
        model[8] = -f[0]; model[9] = -f[1]; model[10] = -f[2]; model[11] = 0f
        model[12] = c[0]; model[13] = c[1]; model[14] = c[2]; model[15] = 1f
    }

    private fun drawTexture(panel: AppPanel, uvRect: FloatArray) {
        GLES30.glUseProgram(oesProgram)
        GLES30.glUniformMatrix4fv(oesMvp, 1, false, mvp, 0)
        GLES30.glUniform4fv(oesUvRect, 1, uvRect, 0)
        GLES30.glUniform2f(oesTexel, 1f / panel.width, 1f / panel.height)
        GLES30.glUniform1f(oesSharpen, frameSharpen)
        GLES30.glUniformMatrix4fv(oesTexMatrix, 1, false, panel.transform, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, panel.textureId)
        texQuad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, texQuad)
        texQuad.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, texQuad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(1)
        texQuad.position(0)
    }

    private fun drawLine(buf: FloatBuffer, count: Int, mode: Int, color: FloatArray, m: FloatArray) {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, m, 0)
        GLES30.glUniform4fv(uColor, 1, color, 0)
        buf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, buf)
        GLES30.glDrawArrays(mode, 0, count)
    }

    private fun draw(b: Batch, m: FloatArray) = drawLine(b.buffer, b.count, b.mode, b.color, m)

    private fun buildStaticScene() {
        gridBatches.clear(); ringBatches.clear(); headBatches.clear()
        val w = 1.6f; val h = w * 9f / 16f; val z = -1.5f
        val x0 = -w / 2; val y0 = -h / 2
        gridBatches += batch(GLES30.GL_TRIANGLES, PANEL_FILL, listOf(
            x0, y0, z, x0 + w, y0, z, x0 + w, y0 + h, z,
            x0, y0, z, x0 + w, y0 + h, z, x0, y0 + h, z,
        ))
        val grid = mutableListOf<Float>()
        val zl = z + 0.002f
        for (i in 0..10) { val x = x0 + w * i / 10; grid += listOf(x, y0, zl, x, y0 + h, zl) }
        for (j in 0..6) { val y = y0 + h * j / 6; grid += listOf(x0, y, zl, x0 + w, y, zl) }
        gridBatches += batch(GLES30.GL_LINES, GRID, grid)
        val c = 0.08f
        gridBatches += batch(GLES30.GL_LINES, CROSSHAIR, listOf(-c, 0f, zl, c, 0f, zl, 0f, -c, zl, 0f, c, zl))

        val ring = mutableListOf<Float>()
        for (deg in -180 until 180 step 15) {
            val a = Math.toRadians(deg.toDouble())
            val x = (sin(a) * RING_M).toFloat(); val zz = (-cos(a) * RING_M).toFloat()
            val len = if (deg % 90 == 0) 0.12f else 0.05f
            ring += listOf(x, -len - RING_DROP_M, zz, x, len - RING_DROP_M, zz)
        }
        ringBatches += batch(GLES30.GL_LINES, RING, ring)
        ringBatches += batch(GLES30.GL_LINES, LEFT_MARK, letterL().placedAtYaw(-60f))
        ringBatches += batch(GLES30.GL_LINES, RIGHT_MARK, letterR().placedAtYaw(60f))

        val d = 0.01f; val dz = -1f
        headBatches += batch(GLES30.GL_TRIANGLES, HEAD_DOT, listOf(-d, -d, dz, d, -d, dz, d, d, dz, -d, -d, dz, d, d, dz, -d, d, dz))
    }

    private fun letterL() = listOf(-0.1f, 0.15f, 0f, -0.1f, -0.15f, 0f, -0.1f, -0.15f, 0f, 0.1f, -0.15f, 0f)

    private fun letterR() = listOf(
        -0.1f, -0.15f, 0f, -0.1f, 0.15f, 0f, -0.1f, 0.15f, 0f, 0.08f, 0.15f, 0f,
        0.08f, 0.15f, 0f, 0.1f, 0.05f, 0f, 0.1f, 0.05f, 0f, 0.08f, 0f, 0f,
        0.08f, 0f, 0f, -0.1f, 0f, 0f, -0.02f, 0f, 0f, 0.1f, -0.15f, 0f,
    )

    /** Places a glyph drawn in the XY plane on the ring at [yawDeg] (positive = right), facing the user. */
    private fun List<Float>.placedAtYaw(yawDeg: Float): List<Float> {
        val a = Math.toRadians(yawDeg.toDouble())
        val s = sin(a).toFloat(); val co = cos(a).toFloat()
        val out = ArrayList<Float>(size)
        for (i in indices step 3) {
            val gx = this[i]; val gy = this[i + 1]
            out += RING_M * s + gx * co
            out += gy - RING_DROP_M
            out += -RING_M * co + gx * s
        }
        return out
    }

    private fun batch(mode: Int, color: FloatArray, v: List<Float>) = Batch(floats(v), v.size / 3, mode, color)

    private fun floats(v: List<Float>): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        v.forEach { buf.put(it) }
        buf.position(0)
        return buf
    }

    private fun buildProgram(vsSrc: String, fsSrc: String, name: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc, name)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc, name)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) DiagLog.e(TAG, "$name program link failed: ${GLES30.glGetProgramInfoLog(p)}")
        return p
    }

    private fun compile(type: Int, src: String, name: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) DiagLog.e(TAG, "$name shader compile failed: ${GLES30.glGetShaderInfoLog(s)}")
        return s
    }

    private companion object {
        const val TAG = "render"
        const val VERTICAL_FOV_DEG = 24f
        const val RING_M = 2.2f
        /** The drift ring sits a little below eye level so it doesn't cut through screens. */
        const val RING_DROP_M = 0.75f
        const val OUTLINE_MARGIN = 0.015f
        const val CIRCLE_SEGMENTS = 40
        val STEREO_EYES = listOf(Eye.LEFT, Eye.RIGHT)
        val MONO_EYES = listOf(Eye.MONO)
        const val CARD_W_M = 0.42f
        const val CARD_DISTANCE_M = 1.5f
        /** Bottom of the lowest card, head-locked (just above the lower edge of a 24° view at 1.5 m). */
        const val CARD_BOTTOM_M = -0.27f
        const val DEFAULT_W = 1280
        const val DEFAULT_H = 800
        val PANEL_FILL = floatArrayOf(0.08f, 0.10f, 0.14f, 1f)
        val GRID = floatArrayOf(0.35f, 0.75f, 0.95f, 1f)
        val CROSSHAIR = floatArrayOf(1f, 1f, 1f, 1f)
        val RING = floatArrayOf(0.45f, 0.45f, 0.5f, 1f)
        val LEFT_MARK = floatArrayOf(0.3f, 0.55f, 1f, 1f)
        val RIGHT_MARK = floatArrayOf(1f, 0.6f, 0.2f, 1f)
        val HEAD_DOT = floatArrayOf(0.2f, 1f, 0.4f, 1f)
        val FOCUS = floatArrayOf(0.49f, 0.61f, 1f, 1f)
        private val HALO = floatArrayOf(0.49f, 0.96f, 0.84f, 0.85f)
        private val HINT = floatArrayOf(0.49f, 0.96f, 0.84f, 0.8f)
        private const val HINT_DIST_M = 1f
        /** Screens in mixed reality: a touch see-through, like a hologram. */
        private const val MR_OPACITY = 1f
        /** Framed screen: share of the way to its target each frame (30 fps: settles in about half a second). */
        const val HERO_EASE = 0.18f
        /** How strongly staged screens light the room around them. */
        const val SCREEN_GLOW = 0.45f

        val LINE_VS = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); }
        """.trimIndent()
        val LINE_FS = """
            #version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() { fragColor = uColor; }
        """.trimIndent()
        val SPRITE_VS = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uMvp;
            out vec2 vUv;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv; }
        """.trimIndent()
        val SPRITE_FS = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            in vec2 vUv;
            out vec4 fragColor;
            void main() { fragColor = texture(uTex, vUv) * uAlpha; }
        """.trimIndent()
        val OES_VS = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            uniform vec4 uUvRect; // which part of the image this eye sees (3D content)
            out vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vec2 uv = uUvRect.xy + aUv * uUvRect.zw;
                vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val OES_FS = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform vec2 uTexel;
            uniform float uSharpen;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uTex, vUv);
                if (uSharpen <= 0.0) { fragColor = c; return; }
                // Light unsharp mask: restores text edges softened by resampling onto the glasses.
                vec3 n = texture(uTex, vUv + vec2(0.0, uTexel.y)).rgb + texture(uTex, vUv - vec2(0.0, uTexel.y)).rgb
                       + texture(uTex, vUv + vec2(uTexel.x, 0.0)).rgb + texture(uTex, vUv - vec2(uTexel.x, 0.0)).rgb;
                vec3 sharp = c.rgb + uSharpen * (4.0 * c.rgb - n);
                fragColor = vec4(clamp(sharp, 0.0, 1.0), c.a);
            }
        """.trimIndent()
    }
}
