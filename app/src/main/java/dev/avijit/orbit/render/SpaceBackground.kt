package dev.avijit.orbit.render

import android.opengl.GLES30
import android.opengl.Matrix
import dev.avijit.orbit.diag.DiagLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The OrbitWalker space environment: a procedural 360° sky drawn as one full-screen pass.
 * For each pixel the view ray is reconstructed from the head pose, then:
 *  - three layers of hashed stars with gentle twinkle,
 *  - a soft indigo/teal nebula (fbm noise) that drifts very slowly,
 *  - two planets and a small moon (lit sphere impostors) on slow orbits,
 *  - an occasional shooting star.
 * No image assets, so nothing to licence. [brightness] dims the whole scene.
 */
class SpaceBackground {
    private var program = 0
    private var uInvVp = 0
    private var uTime = 0
    private var uBrightness = 0
    private var uMode = 0
    private var uPC = 0; private var uPBase = 0; private var uPAlt = 0; private var uPKind = 0; private var uPOrbit = 0; private var uRingIdx = 0
    private val pc = FloatArray(OrbitSky.PLANETS.size * 4)
    private val pBase = FloatArray(OrbitSky.PLANETS.size * 3).also { a -> OrbitSky.PLANETS.forEachIndexed { i, p -> p.base.copyInto(a, i * 3) } }
    private val pAlt = FloatArray(OrbitSky.PLANETS.size * 3).also { a -> OrbitSky.PLANETS.forEachIndexed { i, p -> p.alt.copyInto(a, i * 3) } }
    private val pKind = FloatArray(OrbitSky.PLANETS.size * 2).also { a -> OrbitSky.PLANETS.forEachIndexed { i, p -> a[i * 2] = p.kind; a[i * 2 + 1] = p.bands } }
    private val pOrbit = FloatArray(OrbitSky.PLANETS.size * 2).also { a -> OrbitSky.PLANETS.forEachIndexed { i, p -> a[i * 2] = p.elev; a[i * 2 + 1] = p.tilt } }
    private val ringIdx = OrbitSky.PLANETS.indexOfFirst { it.rings }
    private val inv = FloatArray(16)
    private val tri = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0)
    }

    /** GL thread. */
    fun create() {
        program = link(VS, FS)
        uInvVp = GLES30.glGetUniformLocation(program, "uInvVp")
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uBrightness = GLES30.glGetUniformLocation(program, "uBrightness")
        uMode = GLES30.glGetUniformLocation(program, "uMode")
        uPC = GLES30.glGetUniformLocation(program, "uPC")
        uPBase = GLES30.glGetUniformLocation(program, "uPBase")
        uPAlt = GLES30.glGetUniformLocation(program, "uPAlt")
        uPKind = GLES30.glGetUniformLocation(program, "uPKind")
        uPOrbit = GLES30.glGetUniformLocation(program, "uPOrbit")
        uRingIdx = GLES30.glGetUniformLocation(program, "uRingIdx")
    }

    /**
     * GL thread. [projection] and [view] are the frame's matrices (the view is rotation-only),
     * [timeS] is seconds since start, [brightness] is 0..1.
     */
    fun draw(projection: FloatArray, view: FloatArray, timeS: Float, brightness: Float, scratch: FloatArray, orbit: Boolean = false, skyT: Float = timeS) {
        if (brightness <= 0.001f || program == 0) return
        Matrix.multiplyMM(scratch, 0, projection, 0, view, 0)
        if (!Matrix.invertM(inv, 0, scratch, 0)) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uInvVp, 1, false, inv, 0)
        GLES30.glUniform1f(uTime, timeS)
        GLES30.glUniform1f(uBrightness, brightness)
        GLES30.glUniform1i(uMode, if (orbit) 1 else 0)
        if (orbit) {
            OrbitSky.PLANETS.forEachIndexed { i, p ->
                OrbitSky.centre(p, skyT).copyInto(pc, i * 4)
                pc[i * 4 + 3] = p.radius
            }
            val n = OrbitSky.PLANETS.size
            GLES30.glUniform4fv(uPC, n, pc, 0)
            GLES30.glUniform3fv(uPBase, n, pBase, 0)
            GLES30.glUniform3fv(uPAlt, n, pAlt, 0)
            GLES30.glUniform2fv(uPKind, n, pKind, 0)
            GLES30.glUniform2fv(uPOrbit, n, pOrbit, 0)
            GLES30.glUniform1i(uRingIdx, ringIdx)
        }
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, tri)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun link(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) DiagLog.e("render", "space shader compile failed: ${GLES30.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vsSrc))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fsSrc))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            DiagLog.e("render", "space program link failed: ${GLES30.glGetProgramInfoLog(p)}")
            return 0
        }
        return p
    }

    private companion object {
        val VS = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 vNdc;
            void main() { vNdc = aPos; gl_Position = vec4(aPos, 0.9999, 1.0); }
        """.trimIndent()

        val FS = """
            #version 300 es
            precision highp float;
            in vec2 vNdc;
            uniform mat4 uInvVp;
            uniform float uTime;
            uniform float uBrightness;
            uniform int uMode; // 0 = space, 1 = orbit (a solar system circling the viewer)
            // Orbit mode: the family's apps as planets (from OrbitSky): centre+radius, colours,
            // kind+bands, orbit elev+tilt; which one has rings.
            uniform vec4 uPC[7];
            uniform vec3 uPBase[7];
            uniform vec3 uPAlt[7];
            uniform vec2 uPKind[7];
            uniform vec2 uPOrbit[7];
            uniform int uRingIdx;
            out vec4 fragColor;

            float hash13(vec3 p) {
                p = fract(p * 0.1031);
                p += dot(p, p.zyx + 31.32);
                return fract((p.x + p.y) * p.z);
            }
            vec3 hash33(vec3 p) {
                p = fract(p * vec3(0.1031, 0.1030, 0.0973));
                p += dot(p, p.yxz + 33.33);
                return fract((p.xxy + p.yxx) * p.zyx);
            }
            float noise(vec3 p) {
                vec3 i = floor(p); vec3 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float n000 = hash13(i), n100 = hash13(i + vec3(1,0,0)), n010 = hash13(i + vec3(0,1,0)), n110 = hash13(i + vec3(1,1,0));
                float n001 = hash13(i + vec3(0,0,1)), n101 = hash13(i + vec3(1,0,1)), n011 = hash13(i + vec3(0,1,1)), n111 = hash13(i + vec3(1,1,1));
                return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y), mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
            }
            float fbm(vec3 p) {
                float a = 0.5, s = 0.0;
                for (int i = 0; i < 4; i++) { s += a * noise(p); p *= 2.03; a *= 0.5; }
                return s;
            }
            vec3 stars(vec3 d, float scale, float density, float t) {
                vec3 p = d * scale;
                vec3 cell = floor(p);
                vec3 h = hash33(cell);
                if (h.x > density) return vec3(0.0);
                vec3 starPos = cell + 0.25 + 0.5 * h;
                float dist = length(p - starPos);
                float size = 0.06 + 0.10 * h.y;
                float core = 1.0 - smoothstep(0.0, size, dist);
                float tw = 0.75 + 0.25 * sin(t * (1.0 + 3.0 * h.z) + h.y * 40.0);
                vec3 tint = mix(vec3(0.75, 0.82, 1.0), vec3(1.0, 0.9, 0.78), h.z);
                return tint * core * tw;
            }
            // Lit sphere impostor seen in direction d; returns rgb and coverage in a.
            vec4 planet(vec3 d, vec3 center, float radius, vec3 base, vec3 rim, vec3 light) {
                float c = dot(d, center);
                float ang = acos(clamp(c, -1.0, 1.0));
                if (ang > radius) return vec4(0.0);
                vec3 tangentU = normalize(cross(center, vec3(0.0, 1.0, 0.0)));
                vec3 tangentV = cross(tangentU, center);
                vec2 q = vec2(dot(d, tangentU), dot(d, tangentV)) / sin(radius);
                float z = sqrt(max(0.0, 1.0 - dot(q, q)));
                vec3 n = normalize(q.x * tangentU + q.y * tangentV - z * center);
                float lit = clamp(dot(n, light), 0.0, 1.0);
                float bands = 0.85 + 0.15 * sin(q.y * 18.0 + fbm(vec3(q * 3.0, 1.0)) * 4.0);
                vec3 col = base * (0.08 + 0.92 * lit) * bands + rim * pow(1.0 - z, 3.0) * 0.8;
                float edge = 1.0 - smoothstep(radius * 0.97, radius, ang);
                return vec4(col, edge);
            }

            // A planet's direction on its orbit around the viewer: azimuth a, height elev, plane tilt.
            vec3 orbitPos(float a, float elev, float tilt) {
                return normalize(vec3(sin(a), elev + tilt * cos(a), -cos(a)));
            }
            // Faint orbit path: how close direction d is to the orbit's circle.
            float orbitLine(vec3 d, float elev, float tilt) {
                float a = atan(d.x, -d.z);
                vec3 p = orbitPos(a, elev, tilt);
                float dist = length(d - p);
                return exp(-dist * dist / 0.0000045);
            }
            // Rocky / gas / ocean planet with procedural surface; light from the sun direction.
            vec4 world(vec3 d, vec3 center, float radius, vec3 base, vec3 alt, float bandFreq, float kind, vec3 sunDir) {
                vec3 light = normalize(sunDir - center * 0.98); // from the planet towards the sun: real phases
                float c = dot(d, center);
                float ang = acos(clamp(c, -1.0, 1.0));
                if (ang > radius) return vec4(0.0);
                vec3 tu = normalize(cross(center, vec3(0.0, 1.0, 0.0)));
                vec3 tv = cross(tu, center);
                vec2 q = vec2(dot(d, tu), dot(d, tv)) / sin(radius);
                float z = sqrt(max(0.0, 1.0 - dot(q, q)));
                vec3 n = normalize(q.x * tu + q.y * tv - z * center);
                float lit = clamp(dot(n, light) * 1.1 + 0.05, 0.0, 1.0);
                vec3 sp = vec3(q * 2.5, z * 2.5 + kind * 11.0);
                float f = fbm(sp);
                vec3 surf;
                if (kind < 0.5) {            // gas giant: soft bands
                    float b = sin(q.y * bandFreq + f * 3.0) * 0.5 + 0.5;
                    surf = mix(base, alt, b);
                } else if (kind < 1.5) {     // rocky: mottled
                    surf = mix(base, alt, smoothstep(0.35, 0.75, f));
                } else {                     // ocean world: seas, land, clouds
                    surf = mix(base, alt, smoothstep(0.52, 0.58, f));
                    float cloud = smoothstep(0.55, 0.75, fbm(sp * 1.7 + vec3(uTime * 0.01, 0.0, 0.0)));
                    surf = mix(surf, vec3(0.95), cloud * 0.8);
                }
                vec3 col = surf * (0.04 + 0.96 * lit);
                col += vec3(0.45, 0.6, 1.0) * pow(1.0 - z, 4.0) * 0.35 * lit; // atmosphere rim on the day side
                float edge = 1.0 - smoothstep(radius * 0.96, radius, ang);
                return vec4(col, edge);
            }

            void main() {
                vec4 far = uInvVp * vec4(vNdc, 1.0, 1.0);
                vec3 d = normalize(far.xyz / far.w);
                float t = uTime;

                // Deep background gradient.
                vec3 col = mix(vec3(0.004, 0.006, 0.018), vec3(0.012, 0.010, 0.035), 0.5 + 0.5 * d.y);

                // Nebula: two coloured clouds drifting very slowly.
                vec3 np = d * 2.2 + vec3(t * 0.004, 0.0, -t * 0.003);
                float n1 = fbm(np);
                float n2 = fbm(np * 1.7 + 7.3);
                float bandX = d.y * 2.2 - 0.3 * sin(d.x * 2.0);
                float band = exp(-bandX * bandX); // pow() of a negative base is undefined in GLSL ES
                float neb = uMode == 1 ? 0.45 : 1.0;
                col += vec3(0.18, 0.12, 0.42) * pow(n1, 3.0) * 0.9 * band * neb;
                col += vec3(0.05, 0.30, 0.32) * pow(n2, 4.0) * 0.8 * band * neb;

                // Stars (three layers for depth).
                col += stars(d, 90.0, 0.10, t) * 1.0;
                col += stars(d, 180.0, 0.06, t * 1.3) * 0.75;
                col += stars(d, 360.0, 0.05, t * 1.7) * 0.5;

                if (uMode == 1) {
                    // Orbit: a sun with a soft glow, and seven planets circling you on tilted orbits.
                    vec3 sun = normalize(vec3(-0.78, 0.16, -0.6));
                    float sd = max(dot(d, sun), 0.0);
                    col += vec3(1.0, 0.86, 0.6) * (smoothstep(0.99955, 0.99975, sd) * 3.0 + pow(sd, 900.0) * 1.2 + pow(sd, 60.0) * 0.18 + pow(sd, 8.0) * 0.04);
                    // Orbit paths (very faint), then each app's planet.
                    for (int i = 0; i < 7; i++) col += vec3(0.35, 0.45, 0.8) * 0.05 * orbitLine(d, uPOrbit[i].x, uPOrbit[i].y);
                    for (int i = 0; i < 7; i++) {
                        vec3 c = uPC[i].xyz;
                        float r = uPC[i].w;
                        vec4 w = world(d, c, r, uPBase[i], uPAlt[i], uPKind[i].y, uPKind[i].x, sun);
                        col = mix(col, w.rgb, w.a);
                        if (i == uRingIdx) {
                            // Rings: an ellipse around the planet, in front of it on the lower half.
                            vec3 tu = normalize(cross(c, vec3(0.0, 1.0, 0.0)));
                            vec3 tv = cross(tu, c);
                            vec2 q = vec2(dot(d, tu), dot(d, tv)) / sin(r);
                            float rr = length(vec2(q.x, q.y / 0.28));
                            float ring = smoothstep(1.35, 1.45, rr) * (1.0 - smoothstep(2.25, 2.35, rr)) * step(0.0, dot(d, c));
                            float ringBands = 0.6 + 0.4 * sin(rr * 28.0);
                            float behind = step(dot(q, q), 1.0) * step(0.0, q.y);
                            col = mix(col, uPBase[i] * ringBands * 0.8, ring * (1.0 - behind) * 0.85);
                        }
                    }
                    // A gentle shooting star now and then (every ~40 s, brief and dim): atmosphere,
                    // never a big moving shape that could make the view feel like it moves.
                    {
                        float sc = floor(t / 40.0);
                        float sph = fract(t / 40.0);
                        if (sph < 0.025) {
                            vec3 h = hash33(vec3(sc, 5.3, 1.9));
                            vec3 start = normalize(vec3(h.x * 2.0 - 1.0, 0.3 + 0.4 * h.y, h.z * 2.0 - 1.0));
                            vec3 dir = normalize(cross(start, vec3(0.3, 1.0, 0.2)));
                            float k = sph / 0.025;
                            vec3 head = normalize(start + dir * k * 0.35);
                            vec3 tail = normalize(start + dir * max(0.0, k * 0.35 - 0.08));
                            vec3 seg = head - tail;
                            float u = clamp(dot(d - tail, seg) / max(dot(seg, seg), 1e-6), 0.0, 1.0);
                            float dist = length(d - (tail + seg * u));
                            col += vec3(0.85, 0.92, 1.0) * (1.0 - smoothstep(0.0, 0.0018, dist)) * u * (1.0 - k) * 0.9;
                        }
                    }
                    fragColor = vec4(col * uBrightness, 1.0);
                    return;
                }

                // Planets on slow orbits; light from the "sun" direction.
                vec3 light = normalize(vec3(-0.6, 0.35, -0.7));
                float a1 = 0.9 + t * 0.002;
                vec4 p1 = planet(d, normalize(vec3(sin(a1), 0.28, -cos(a1))), 0.11,
                                 vec3(0.42, 0.36, 0.78), vec3(0.49, 0.61, 1.0), light);
                float a2 = -2.2 + t * 0.0013;
                vec4 p2 = planet(d, normalize(vec3(sin(a2), -0.12, -cos(a2))), 0.06,
                                 vec3(0.20, 0.55, 0.52), vec3(0.37, 0.92, 0.83), light);
                float a3 = 2.6 - t * 0.003;
                vec4 p3 = planet(d, normalize(vec3(sin(a3), 0.5, -cos(a3))), 0.025,
                                 vec3(0.70, 0.66, 0.62), vec3(0.9, 0.9, 1.0), light);
                col = mix(col, p1.rgb, p1.a);
                col = mix(col, p2.rgb, p2.a);
                col = mix(col, p3.rgb, p3.a);

                // Shooting star: one every ~14 s, crossing a random part of the sky.
                float cycle = floor(t / 14.0);
                float ph = fract(t / 14.0);
                if (ph < 0.12) {
                    vec3 h = hash33(vec3(cycle, 3.1, 7.7));
                    vec3 start = normalize(vec3(h.x * 2.0 - 1.0, 0.25 + 0.5 * h.y, h.z * 2.0 - 1.0));
                    vec3 dir = normalize(cross(start, vec3(0.3, 1.0, 0.2)));
                    float k = ph / 0.12;
                    vec3 head = normalize(start + dir * k * 0.6);
                    vec3 tail = normalize(start + dir * max(0.0, k * 0.6 - 0.12));
                    vec3 seg = head - tail;
                    float u = clamp(dot(d - tail, seg) / max(dot(seg, seg), 1e-6), 0.0, 1.0);
                    float dist = length(d - (tail + seg * u));
                    float streak = (1.0 - smoothstep(0.0, 0.0025, dist)) * u * (1.0 - k);
                    col += vec3(0.85, 0.92, 1.0) * streak * 1.6;
                }

                fragColor = vec4(col * uBrightness, 1.0);
            }
        """.trimIndent()
    }
}
