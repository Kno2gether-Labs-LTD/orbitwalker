package dev.avijit.orbit.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import dev.avijit.orbit.diag.DiagLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One recording file in Movies/OrbitWalker: H.264 video drawn by the renderer into [inputSurface],
 * plus (optionally) AAC audio fed by [Mic]. Never logs anything but sizes and errors.
 */
class Mp4File private constructor(
    private val context: Context,
    val view: RecordingView,
    val width: Int,
    val height: Int,
    private val uri: Uri,
    private val pfd: ParcelFileDescriptor,
    withAudio: Boolean,
) {
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val gate = MuxGate(if (withAudio) 2 else 1)
    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private val thread = HandlerThread("rec-${view.name.lowercase()}").apply { start() }
    private val videoEos = CountDownLatch(1)
    @Volatile private var failed = false
    @Volatile var frames = 0
        private set

    private val video: MediaCodec = MediaCodec.createEncoderByType(MIME_VIDEO)
    val inputSurface: Surface
    private val audio: MediaCodec? = if (withAudio) MediaCodec.createEncoderByType(MIME_AUDIO) else null

    init {
        val vf = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        video.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                synchronized(lock) { videoTrack = muxer.addTrack(format); if (gate.addTrack()) muxer.start() }
            }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    synchronized(lock) {
                        if (gate.shouldWrite(video = true, keyFrame = key)) {
                            codec.getOutputBuffer(index)?.let { muxer.writeSampleData(videoTrack, it, info); frames++ }
                        } else if (gate.started && !key) {
                            // Waiting for the first key frame (e.g. the audio track started late).
                            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (eos) videoEos.countDown()
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                DiagLog.e(TAG, "video encoder error", e); failed = true; videoEos.countDown()
            }
        }, Handler(thread.looper))
        video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = video.createInputSurface()
        audio?.configure(
            MediaFormat.createAudioFormat(MIME_AUDIO, Mic.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        video.start()
        audio?.start()
    }

    // ---------------------------------------------------------------- audio (Mic thread)

    private val audioInfo = MediaCodec.BufferInfo()

    /** Mic thread: one chunk of 16-bit mono PCM captured at [ptsUs]. */
    fun feedAudio(pcm: ByteArray, length: Int, ptsUs: Long, eos: Boolean = false) {
        val a = audio ?: return
        runCatching {
            val i = a.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val buf = a.getInputBuffer(i)!!
                buf.clear()
                val n = minOf(length, buf.remaining())
                buf.put(pcm, 0, n)
                a.queueInputBuffer(i, 0, n, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            }
            drainAudio(if (eos) 50_000 else 0)
        }.onFailure { DiagLog.e(TAG, "audio encode failed", it) }
    }

    private fun drainAudio(timeoutUs: Long) {
        val a = audio ?: return
        while (true) {
            val o = a.dequeueOutputBuffer(audioInfo, timeoutUs)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    audioTrack = muxer.addTrack(a.outputFormat); if (gate.addTrack()) muxer.start()
                }
                o >= 0 -> {
                    val eos = audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (audioInfo.size > 0 && audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        synchronized(lock) {
                            if (gate.shouldWrite(video = false, keyFrame = false)) a.getOutputBuffer(o)?.let { muxer.writeSampleData(audioTrack, it, audioInfo) }
                        }
                    }
                    a.releaseOutputBuffer(o, false)
                    if (eos) return
                }
            }
        }
    }

    // ---------------------------------------------------------------- finish

    /** Any thread but the renderer's, after the renderer has stopped drawing into [inputSurface]. True if saved. */
    fun finish(): Boolean {
        runCatching { video.signalEndOfInputStream() }
        videoEos.await(3, TimeUnit.SECONDS)
        val ok = synchronized(lock) { gate.started && frames > 0 && !failed }
        runCatching { video.stop() }; runCatching { video.release() }
        runCatching { audio?.stop() }; runCatching { audio?.release() }
        runCatching { inputSurface.release() }
        val saved = ok && runCatching { synchronized(lock) { muxer.stop() } }.isSuccess
        runCatching { muxer.release() }
        runCatching { pfd.close() }
        thread.quitSafely()
        if (saved) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        DiagLog.i(TAG, "${view.suffix} file ${if (saved) "saved" else "discarded"}: ${width}x$height, $frames frames")
        return saved
    }

    companion object {
        private const val TAG = "record"
        const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MIME_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC
        const val FPS = 30
        private const val BITRATE = 12_000_000

        /** Largest of [sizes] the phone's H.264 encoder supports (portrait 1080×1920 isn't universal). */
        fun supportedSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int> {
            val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME_VIDEO, true) } }
                .map { it.getCapabilitiesForType(MIME_VIDEO).videoCapabilities }
            return sizes.firstOrNull { (w, h) -> caps.any { it.isSizeSupported(w, h) } } ?: sizes.last()
        }

        fun create(context: Context, name: String, view: RecordingView, width: Int, height: Int, withAudio: Boolean): Mp4File {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OrbitWalker")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("can't create $name")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("can't open $name")
            return try {
                Mp4File(context, view, width, height, uri, pfd, withAudio)
            } catch (t: Throwable) {
                runCatching { pfd.close() }; runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
        }
    }
}

/** The phone's microphone, encoded into every recording file at once. */
class Mic(private val sinks: List<Mp4File>) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @android.annotation.SuppressLint("MissingPermission") // checked by the caller
    fun start() {
        val min = android.media.AudioRecord.getMinBufferSize(SAMPLE_RATE, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val rec = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4),
        )
        running = true
        thread = Thread({
            val buf = ByteArray(CHUNK)
            runCatching { rec.startRecording() }.onFailure { DiagLog.e("record", "mic failed", it); running = false }
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                // Timestamp of the chunk's first sample (same clock as the video frames).
                val pts = System.nanoTime() / 1000 - n / 2 * 1_000_000L / SAMPLE_RATE
                sinks.forEach { it.feedAudio(buf, n, pts) }
            }
            runCatching { rec.stop() }; rec.release()
            val end = System.nanoTime() / 1000
            sinks.forEach { it.feedAudio(buf, 0, end, eos = true) }
        }, "rec-mic").apply { start() }
    }

    fun stop() { running = false; thread?.join(2_000) }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val CHUNK = 4096
    }
}
