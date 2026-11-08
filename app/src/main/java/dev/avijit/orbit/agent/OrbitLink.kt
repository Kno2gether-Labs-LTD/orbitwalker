package dev.avijit.orbit.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** A pairing link from the Orbit Server dashboard: `orbitwalker://pair?u=&fp=&t=&n=[&m=pin|ca]` (or `orbit://pair`). */
data class PairLink(val url: String, val fingerprint: String?, val token: String, val name: String, val ca: Boolean) {
    companion object {
        private val FP = Regex("[0-9a-f]{64}")

        /** Null for anything that isn't a well-formed pairing link. */
        fun parse(link: String): PairLink? {
            val s = link.trim()
            val query = when {
                s.startsWith("orbitwalker://pair?") -> s.removePrefix("orbitwalker://pair?")
                s.startsWith("orbit://pair?") -> s.removePrefix("orbit://pair?")
                else -> return null
            }
            val q = runCatching {
                query.split('&').associate { kv -> kv.substringBefore('=') to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }
            }.getOrNull() ?: return null
            val url = q["u"]?.trimEnd('/') ?: return null
            val token = q["t"]?.takeIf { it.isNotBlank() } ?: return null
            val host = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() } ?: return null
            val ca = q["m"] == "ca"
            val fp = q["fp"]?.lowercase()
            if (!ca && (fp == null || !FP.matches(fp))) return null
            if (fp != null && !FP.matches(fp)) return null
            return PairLink(url, fp, token, q["n"]?.ifBlank { null } ?: host.host, ca)
        }
    }
}

/** This phone's link to the user's own Orbit Server: who it is, and how to trust its certificate. */
data class ServerLink(val url: String, val fingerprint: String?, val ca: Boolean, val token: String, val serverName: String) {
    fun toJson(): String = JSONObject().put("u", url).put("fp", fingerprint ?: "").put("ca", ca).put("t", token).put("n", serverName).toString()

    companion object {
        fun fromJson(s: String): ServerLink? = runCatching {
            val o = JSONObject(s)
            ServerLink(o.getString("u"), o.optString("fp").ifBlank { null }, o.getBoolean("ca"), o.getString("t"), o.optString("n"))
        }.getOrNull()
    }
}

class ServerException(val code: String, val status: Int) : Exception("server: $status $code")

/**
 * HTTPS to the Orbit Server. Pin mode trusts exactly the certificate with the paired SHA-256 fingerprint; ca mode
 * uses the system's trusted certificates. The device token lives in no-backup storage (never in cloud backups).
 */
class OrbitLink(context: Context) {
    private val file = File(context.noBackupFilesDir, "orbit-server.json")

    @Volatile var link: ServerLink? = runCatching { ServerLink.fromJson(file.readText()) }.getOrNull()
        private set

    val paired get() = link != null

    /** Redeems a pairing link: the server returns this phone's own device token. Network: call off the main thread. */
    fun pair(p: PairLink, deviceName: String): ServerLink {
        val body = JSONObject().put("token", p.token).put("deviceName", deviceName)
        val res = post(p.url, p.fingerprint, p.ca, null, "/v1/pair", body)
        val l = ServerLink(p.url, p.fingerprint, p.ca, res.getString("deviceToken"), res.optString("serverName").ifBlank { p.name })
        file.writeText(l.toJson())
        link = l
        return l
    }

    fun forget() { runCatching { file.delete() }; link = null }

    /** POST [path] with the device token. Network: call off the main thread. */
    fun post(path: String, body: JSONObject): JSONObject {
        val l = link ?: throw ServerException("not_paired", 0)
        return post(l.url, l.fingerprint, l.ca, l.token, path, body)
    }

    /** GET [path] with the device token (JSON). Network: call off the main thread. */
    fun get(path: String): JSONObject {
        val l = link ?: throw ServerException("not_paired", 0)
        return JSONObject(String(call(l.url, l.fingerprint, l.ca, l.token, "GET", path, null, 15_000, MAX_JSON), Charsets.UTF_8))
    }

    /** POST [path] and return the raw response (audio). Network: call off the main thread. */
    fun postForBytes(path: String, body: JSONObject, maxBytes: Int = MAX_AUDIO): ByteArray {
        val l = link ?: throw ServerException("not_paired", 0)
        return call(l.url, l.fingerprint, l.ca, l.token, "POST", path, body, 40_000, maxBytes)
    }

    private fun post(base: String, fp: String?, ca: Boolean, token: String?, path: String, body: JSONObject): JSONObject =
        // The server's own agent deadline is 150 s.
        JSONObject(String(call(base, fp, ca, token, "POST", path, body, 160_000, MAX_JSON), Charsets.UTF_8))

    private fun call(base: String, fp: String?, ca: Boolean, token: String?, method: String, path: String, body: JSONObject?, readMs: Int, maxBytes: Int): ByteArray {
        val c = URI(base + path).toURL().openConnection() as HttpsURLConnection
        if (!ca) {
            c.sslSocketFactory = pinned(fp!!)
            c.setHostnameVerifier { _, _ -> true } // the pinned certificate is the identity check
        }
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = readMs
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { readCapped(it, maxBytes) } ?: ByteArray(0)
            if (code !in 200..299) {
                val err = runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("error") }.getOrNull()?.ifBlank { null } ?: "http_$code"
                throw ServerException(err, code)
            }
            return bytes
        } finally {
            c.disconnect()
        }
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            if (out.size() + n > max) throw ServerException("too_large", 0)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_JSON = 2 * 1024 * 1024
        const val MAX_AUDIO = 8 * 1024 * 1024
    }

    private fun pinned(fp: String): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("client")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("no certificate")
                val got = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (got != fp) throw java.security.cert.CertificateException("certificate does not match the paired server")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }
}
