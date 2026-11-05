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
    private val startNs = System.nanoTime()
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
        artTextures.fill(0) // keyboard + dock textures died with the old context
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
        frameTimeS = ((System.nanoTime() - startNs) / 1e9).toFloat() % 100_000f
        frameNowMs = android.os.SystemClock.uptimeMillis()
        val stereo = eyes.size > 1
        if (stereo) GLES30.glEnable(GLES30.GL_SCISSOR_TEST) // keep each eye (and wide lines) in its half
        for (eye in eyes) {
            val vpRect = Stereo.viewport(eye, outW, outH)
            GLES30.glViewport(vpRect[0], vpRect[1], vpRect[2], vpRect[3])
            if (stereo) GLES30.glScissor(vpRect[0], vpRect[1], vpRect[2], vpRect[3])
            val aspect = if (eye == Eye.MONO) outW.toFloat() / outH else Stereo.eyeAspect()
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

    /** Sampled once per frame so both eyes animate identically. */
    private var frameTimeS = 0f
    private var frameNowMs = 0L

    private fun drawEye(scene: SceneSnapshot, headView: FloatArray) {
        drawBackground(scene, headView) // at infinity: same for both eyes

        var drewScreen = false
        for (s in scene.screens) {
            val panel = panels.getOrNull(s.slot) ?: continue
            if (!panel.hasFrame) continue
            drewScreen = true
            modelFor(s.pose, 1f)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            drawTexture(panel, Stereo.contentUv(s.content, currentEye, if (s.content == ContentMode.FLAT) 0f else Stereo.VIDEO_BAR))
            if (s.focused) {
                modelFor(s.pose, 1f + OUTLINE_MARGIN)
                Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                drawLine(outline, 4, GLES30.GL_LINE_LOOP, FOCUS, mvp)
            }
        }

        scene.keyboard?.let { kb -> scene.keyboardArt?.let { art -> drawKeyboard(kb, art) } }
        scene.dock?.let { d -> scene.dockArt?.let { art -> drawArtPanel(d, art, 1) } }
        scene.windowBar?.let { b -> scene.windowBarArt?.let { art -> drawArtPanel(b, art, 2) } }
        if (scene.background == BackgroundMode.ORBIT && scene.orbitApps && scene.backgroundLevel > 0.01f) drawOrbitLabels(scene.backgroundLevel, OrbitSky.skyTime(frameTimeS, scene.orbitStill))

        GLES30.glUseProgram(lineProgram)
        if (!drewScreen && scene.showGrid && scene.showMarkers) gridBatches.forEach { draw(it, vp) }
        if (scene.showMarkers) ringBatches.forEach { draw(it, vp) }
        scene.cursor?.let { c -> drawPointer(c, scene) }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        // The green centre dot is a head-tracking test aid: only with the alignment markers.
        if (scene.showHeadDot && scene.showMarkers) headBatches.forEach { draw(it, headProj) }
        drawNotifications()
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

    private fun drawArtPanel(pose: dev.avijit.orbit.layout.ScreenPose, art: android.graphics.Bitmap, which: Int, alpha: Float = 1f) {
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
        modelFor(pose, 1f)
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

    private fun drawPointer(c: ScreenPose, scene: SceneSnapshot) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA) // Android bitmaps are premultiplied
        val scale = if (scene.cursorOverScreen) 1f else 0.8f
        modelFor(c, scale)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        if (scene.clickAgeMs >= 0) {
            val t = scene.clickAgeMs / dev.avijit.orbit.ScreensSession.RIPPLE_MS.toFloat()
            val ease = 1f - (1f - t) * (1f - t)
            val ring = floatArrayOf(FOCUS[0], FOCUS[1], FOCUS[2], 1f - t)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            modelFor(c, 0.25f + ease * 0.9f)
            Matrix.multiplyMM(model2, 0, vp, 0, model, 0)
            drawLine(circle, CIRCLE_SEGMENTS, GLES30.GL_LINE_LOOP, ring, model2)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        GLES30.glUseProgram(spriteProgram)
        GLES30.glUniformMatrix4fv(spriteMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(spriteAlpha, if (scene.cursorOverScreen) 1f else 0.7f)
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

    /** Column-major model matrix: unit quad → screen of [pose], scaled by [grow]. */
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
