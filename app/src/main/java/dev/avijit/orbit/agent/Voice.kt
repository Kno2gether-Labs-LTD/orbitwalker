package dev.avijit.orbit.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.avijit.orbit.diag.DiagLog

/** One spoken request, via the phone's speech recognition (on-device when the phone has it). Main thread. */
object Voice {
    private var current: SpeechRecognizer? = null
    /** Set once the offline recogniser turns out unusable on this phone (until the app restarts). */
    @Volatile private var onDeviceBroken = false
    private val ON_DEVICE_UNREADY = setOf(
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
    )

    /** Calls [done] once: (text, null) when heard, (null, message) on a problem, (null, null) when nothing was said. */
    fun listenOnce(context: Context, onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {}, onSpeech: () -> Unit = {}, done: (String?, String?) -> Unit) {
        current?.destroy(); current = null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { done(null, "This phone has no speech recognition"); return }
        val onDevice = !onDeviceBroken && android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val sr = runCatching { if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null }.getOrNull()
            ?: SpeechRecognizer.createSpeechRecognizer(context)
        current = sr
        var finished = false
        fun finish(text: String?, error: String?) {
            if (finished) return
            finished = true
            runCatching { sr.destroy() }
            if (current === sr) current = null
            done(text, error)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = onSpeech()
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }, null)
            }
            override fun onError(error: Int) {
                DiagLog.i("agent", "speech error $error (on-device=$onDevice)")
                if (onDevice && error in ON_DEVICE_UNREADY && !finished) {
                    // The phone's offline recogniser isn't ready (language not downloaded…): use its normal one from now on.
                    finished = true
                    runCatching { sr.destroy() }
                    if (current === sr) current = null
                    onDeviceBroken = true
                    listenOnce(context, onPartial, onLevel, onSpeech, done)
                    return
                }
                finish(null, when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone for OrbitWalker"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "Speech recognition needs the network"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Speech language not downloaded on this phone"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy"
                    else -> MISSED
                })
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        runCatching { sr.startListening(intent) }.onFailure { finish(null, "Couldn't start listening") }
    }

    /** A miss worth just listening again for (not a real problem). */
    const val MISSED = "Didn't catch that"

    fun cancel() { runCatching { current?.cancel(); current?.destroy() }; current = null }
}
