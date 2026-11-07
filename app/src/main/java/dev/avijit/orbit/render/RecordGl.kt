package dev.avijit.orbit.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.record.CameraFeed
import dev.avijit.orbit.record.CameraSpec
import dev.avijit.orbit.record.Mp4File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** GL thread only. EGL window surfaces for the encoders' input surfaces, sharing the glasses' context. */
internal class EncoderSurfaces {
    private val surfaces = HashMap<Mp4File, EGLSurface>()
    private var dpy = EGL14.EGL_NO_DISPLAY
    private var draw = EGL14.EGL_NO_SURFACE
    private var read = EGL14.EGL_NO_SURFACE
    private var ctx = EGL14.EGL_NO_CONTEXT
    private val broken = HashSet<Mp4File>()

    /** Drops surfaces of files that are no longer recording. */
    fun sync(files: List<Mp4File>) {
        val gone = surfaces.keys - files.toSet()
        if (gone.isEmpty()) return
        val d = EGL14.eglGetCurrentDisplay()
        gone.forEach { f -> surfaces.remove(f)?.let { EGL14.eglDestroySurface(d, it) } }
        broken.retainAll(files.toSet())
    }

    /** Makes [file]'s encoder the draw target. False if it can't (the frame is skipped). */
    fun begin(file: Mp4File): Boolean {
        if (file in broken) return false
        dpy = EGL14.eglGetCurrentDisplay()
        draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        ctx = EGL14.eglGetCurrentContext()
        val s = surfaces[file] ?: create(file) ?: run { broken += file; return false }
        if (!EGL14.eglMakeCurrent(dpy, s, s, ctx)) {
            DiagLog.e(TAG, "eglMakeCurrent(encoder) failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            broken += file
            EGL14.eglMakeCurrent(dpy, draw, read, ctx)
            return false
        }
        return true
    }

    fun end(file: Mp4File, ptsNs: Long) {
        surfaces[file]?.let { s ->
            EGLExt.eglPresentationTimeANDROID(dpy, s, ptsNs)
            EGL14.eglSwapBuffers(dpy, s)
        }
        EGL14.eglMakeCurrent(dpy, draw, read, ctx)
    }

    private fun create(file: Mp4File): EGLSurface? {
        val id = IntArray(1)
        EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
        val cfg = configs[0] ?: return null
        val s = EGL14.eglCreateWindowSurface(dpy, cfg, file.inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            DiagLog.e(TAG, "encoder surface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return null
        }
        surfaces[file] = s
        return s
    }

    private companion object { const val TAG = "record" }
}

/** GL thread only. The camera picture as an external texture. */
internal class CameraTexture(val feed: CameraFeed) {
    val spec: CameraSpec get() = feed.spec
    val texture: Int
    private val st: SurfaceTexture
    private val surface: Surface
    @Volatile private var fresh = false
    var hasFrame = false
        private set
    val matrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    init {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        st = SurfaceTexture(texture).apply {
            setDefaultBufferSize(spec.previewW, spec.previewH)
            setOnFrameAvailableListener { fresh = true }
        }
        surface = Surface(st)
        dev.avijit.orbit.record.Recorder.onCameraSurface(feed, surface)
    }

    fun update() {
        if (!fresh) return
        fresh = false
        runCatching { st.updateTexImage(); st.getTransformMatrix(matrix); hasFrame = true }
    }

    fun release() {
        runCatching { surface.release() }; runCatching { st.release() }
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/** GL thread only. An offscreen colour (+ optional depth) target that can be sampled afterwards. */
internal class Fbo(val width: Int, val height: Int, withDepth: Boolean) {
    val framebuffer: Int
    val color: Int
    val depth: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0); color = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, color)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        linear(GLES30.GL_TEXTURE_2D)
        depth = if (withDepth) {
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, width, height, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            ids[0]
        } else 0
        GLES30.glGenFramebuffers(1, ids, 0); framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, color, 0)
        if (withDepth) GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, depth, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) DiagLog.e("record", "framebuffer incomplete: 0x${Integer.toHexString(status)}")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(if (depth != 0) 2 else 1, intArrayOf(color, depth), 0)
    }

    private fun linear(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }
}

/**
 * GL thread only. Full-screen passes: the mixed reality composite (camera + screens + you in
 * front), the face bubble, and plain copies.
 */
internal class Compositor {
    private val program: Int
    private val u = HashMap<String, Int>()
    private var maskTex = 0
    private var maskSerial = -1
    private var maskBuf: ByteBuffer? = null
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(6 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)); position(0) }

    init {
        program = link(VS, FS)
        listOf("uCam", "uCamMatrix", "uRot", "uCrop", "uLayer", "uDepth", "uMask", "uHasMask", "uPersonDepth",
            "uNearFar", "uOpacity", "uMode", "uHasCam").forEach { u[it] = GLES30.glGetUniformLocation(program, it) }
    }

    fun uploadMask(m: dev.avijit.orbit.record.PersonMask.Mask?): Boolean {
        if (m == null) return false
        if (m.serial == maskSerial && maskTex != 0) return true
        if (maskTex == 0) {
            val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); maskTex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        // A direct buffer: heap buffers corrupt uploads on some GL drivers (seen on the emulator).
        val n = m.width * m.height
        val buf = maskBuf?.takeIf { it.capacity() >= n } ?: ByteBuffer.allocateDirect(n).also { maskBuf = it }
        buf.clear(); buf.put(m.alpha, 0, n); buf.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, m.width, m.height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        maskSerial = m.serial
        return true
    }

    /** Output-uv → upright-camera-uv crop (offset x, y, scale x, y) filling [outAspect] from the camera. */
    fun crop(cam: CameraSpec, outAspect: Float): FloatArray {
        val (iw, ih) = if (cam.rotation % 180 == 90) cam.previewH.toFloat() to cam.previewW.toFloat() else cam.previewW.toFloat() to cam.previewH.toFloat()
        return if (iw / ih > outAspect) {
            val s = ih * outAspect / iw; floatArrayOf((1 - s) / 2, 0f, s, 1f)
        } else {
            val s = iw / outAspect / ih; floatArrayOf(0f, (1 - s) / 2, 1f, s)
        }
    }

    /** Mixed reality: camera + [layer] (screens, with depth) + person mask. Draws into the bound target. */
    fun composite(cam: CameraTexture?, layer: Fbo, outAspect: Float, hasMask: Boolean, personDepth: Float, opacity: Float) {
        begin(MODE_COMPOSITE)
        bindCamera(cam, outAspect)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.color)
        GLES30.glUniform1i(u["uLayer"]!!, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.depth)
        GLES30.glUniform1i(u["uDepth"]!!, 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasMask) maskTex else 0)
        GLES30.glUniform1i(u["uMask"]!!, 3)
        GLES30.glUniform1f(u["uHasMask"]!!, if (hasMask) 1f else 0f)
        GLES30.glUniform1f(u["uPersonDepth"]!!, personDepth)
        GLES30.glUniform2f(u["uNearFar"]!!, dev.avijit.orbit.record.CameraRig.NEAR_M, dev.avijit.orbit.record.CameraRig.FAR_M)
        GLES30.glUniform1f(u["uOpacity"]!!, opacity)
        drawQuad()
    }

    /** Round face bubble from the camera, into the current viewport (blended). */
    fun bubble(cam: CameraTexture) {
        begin(MODE_BUBBLE)
        bindCamera(cam, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        drawQuad()
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Copies [texture] to the current viewport. */
    fun copy(texture: Int) {
        begin(MODE_COPY)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(u["uLayer"]!!, 1)
        drawQuad()
    }

    private fun begin(mode: Int) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(u["uMode"]!!, mode)
    }

    private fun bindCamera(cam: CameraTexture?, outAspect: Float) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cam?.texture ?: 0)
        GLES30.glUniform1i(u["uCam"]!!, 0)
        GLES30.glUniform1f(u["uHasCam"]!!, if (cam?.hasFrame == true) 1f else 0f)
        if (cam != null) {
            GLES30.glUniformMatrix4fv(u["uCamMatrix"]!!, 1, false, cam.matrix, 0)
            GLES30.glUniform1i(u["uRot"]!!, cam.spec.rotation)
            GLES30.glUniform4fv(u["uCrop"]!!, 1, crop(cam.spec, outAspect), 0)
        } else GLES30.glUniform4f(u["uCrop"]!!, 0f, 0f, 1f, 1f)
    }

    private fun drawQuad() {
        quad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun link(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src); GLES30.glCompileShader(it)
            val ok = IntArray(1); GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) DiagLog.e("record", "compositor shader: ${GLES30.glGetShaderInfoLog(it)}")
        }
        return GLES30.glCreateProgram().also { p ->
            GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glBindAttribLocation(p, 0, "aPos"); GLES30.glLinkProgram(p)
        }
    }

    companion object {
        const val MODE_COMPOSITE = 0
        const val MODE_BUBBLE = 1
        const val MODE_COPY = 2

        val VS = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 vUv;
            void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
        """.trimIndent()

        val FS = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uCam;
            uniform mat4 uCamMatrix;
            uniform int uRot;
            uniform vec4 uCrop;
            uniform float uHasCam;
            uniform sampler2D uLayer;
            uniform sampler2D uDepth;
            uniform sampler2D uMask;
            uniform float uHasMask;
            uniform float uPersonDepth;
            uniform vec2 uNearFar;
            uniform float uOpacity;
            uniform int uMode;
            in vec2 vUv;
            out vec4 fragColor;

            vec2 sensorUv(vec2 f) {
                if (uRot == 90) return vec2(1.0 - f.y, f.x);
                if (uRot == 180) return vec2(1.0 - f.x, 1.0 - f.y);
                if (uRot == 270) return vec2(f.y, 1.0 - f.x);
                return f;
            }

            vec3 camera(vec2 f) {
                if (uHasCam < 0.5) return vec3(0.06, 0.05, 0.12);
                vec2 s = sensorUv(f);
                return texture(uCam, (uCamMatrix * vec4(s, 0.0, 1.0)).xy).rgb;
            }

            void main() {
                if (uMode == 2) { fragColor = texture(uLayer, vUv); return; }
                vec2 f = uCrop.xy + vUv * uCrop.zw;
                if (uMode == 1) {
                    float d = length(vUv - 0.5);
                    float a = 1.0 - smoothstep(0.485, 0.5, d);
                    vec3 c = mix(camera(f), vec3(0.49, 0.96, 0.84), smoothstep(0.455, 0.47, d));
                    fragColor = vec4(c * a, a);
                    return;
                }
                vec3 cam = camera(f);
                vec4 layer = texture(uLayer, vUv);
                float z = texture(uDepth, vUv).r * 2.0 - 1.0;
                float n = uNearFar.x; float fr = uNearFar.y;
                float lin = 2.0 * n * fr / (fr + n - z * (fr - n));
                float m = uHasMask > 0.5 ? texture(uMask, vec2(f.x, 1.0 - f.y)).r : 0.0;
                // Screens nearer the camera than you stay in front of you; the rest go behind you.
                float screenInFront = 1.0 - smoothstep(uPersonDepth - 0.3, uPersonDepth - 0.1, lin);
                float vis = (1.0 - m * (1.0 - screenInFront)) * uOpacity;
                fragColor = vec4(cam * (1.0 - layer.a * vis) + layer.rgb * vis, 1.0);
            }
        """.trimIndent()
    }
}
