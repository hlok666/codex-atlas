package com.codexatlas.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Paseo-style speech lifecycle for the mobile companion.
 *
 * Android's SpeechRecognizer owns the microphone and produces partial/final
 * hypotheses. This controller keeps that lifecycle out of Compose so a
 * recognizer restart, cancellation, or transient network error cannot leave
 * the UI stuck in a recording state.
 */
enum class VoiceInputPhase {
    Idle,
    Listening,
    Processing,
    Review,
    Failed,
}

data class VoiceInputSnapshot(
    val phase: VoiceInputPhase = VoiceInputPhase.Idle,
    val continuous: Boolean = false,
    val muted: Boolean = false,
    val partialTranscript: String = "",
    val transcript: String = "",
    val volume: Float = 0f,
    val durationSeconds: Int = 0,
    val error: String? = null,
) {
    val active: Boolean
        get() = phase != VoiceInputPhase.Idle
}

class VoiceInputController(
    private val context: Context,
    private val chinese: Boolean,
    private val onSnapshot: (VoiceInputSnapshot) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer: SpeechRecognizer? = if (SpeechRecognizer.isRecognitionAvailable(context)) {
        SpeechRecognizer.createSpeechRecognizer(context)
    } else {
        null
    }

    private var snapshot = VoiceInputSnapshot()
    private var draftText = ""
    private var generation = 0L
    private var restartAttempts = 0
    private var explicitStop = false
    private var restartRunnable: Runnable? = null
    private var durationRunnable: Runnable? = null
    private var finishTimeoutRunnable: Runnable? = null
    private var onContinuousTranscript: ((String) -> Unit)? = null

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (snapshot.phase == VoiceInputPhase.Listening) publish(volume = snapshot.volume)
            }

            override fun onBeginningOfSpeech() {
                restartAttempts = 0
            }

            override fun onRmsChanged(rmsdB: Float) {
                // RMS values are usually in [-2, 12], but OEMs vary widely.
                val normalized = ((rmsdB + 2f) / 14f).coerceIn(0f, 1f)
                publish(volume = normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (snapshot.phase == VoiceInputPhase.Listening) {
                    publish(volume = 0f)
                }
            }

            override fun onPartialResults(results: Bundle?) {
                val text = bestResult(results)
                if (text.isNotBlank() && snapshot.phase == VoiceInputPhase.Listening) {
                    publish(partialTranscript = text, volume = snapshot.volume)
                }
            }

            override fun onResults(results: Bundle?) {
                handleResult(bestResult(results))
            }

            override fun onError(error: Int) {
                handleError(error)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    fun isAvailable(): Boolean = recognizer != null

    fun setContinuousTranscriptListener(listener: ((String) -> Unit)?) {
        onContinuousTranscript = listener
    }

    fun snapshot(): VoiceInputSnapshot = snapshot

    fun start(draft: String = "", continuous: Boolean = false) {
        if (recognizer == null) {
            publish(
                phase = VoiceInputPhase.Failed,
                error = if (chinese) "系统不支持语音识别" else "Speech recognition is unavailable",
            )
            return
        }

        generation += 1
        explicitStop = false
        restartAttempts = 0
        draftText = draft.trim()
        cancelScheduledRestart()
        publish(
            phase = VoiceInputPhase.Listening,
            continuous = continuous,
            muted = false,
            partialTranscript = "",
            transcript = "",
            volume = 0f,
            durationSeconds = 0,
            error = null,
        )
        startDurationClock()
        startRecognition(generation)
    }

    fun stop() {
        if (snapshot.phase != VoiceInputPhase.Listening) return
        explicitStop = true
        publish(phase = VoiceInputPhase.Processing, volume = 0f, error = null)
        cancelFinishTimeout()
        val expectedGeneration = generation
        val timeout = Runnable {
            if (generation == expectedGeneration && snapshot.phase == VoiceInputPhase.Processing) {
                fail(if (chinese) "没有识别到语音" else "No speech was recognized")
            }
        }
        finishTimeoutRunnable = timeout
        handler.postDelayed(timeout, 2500L)
        recognizer?.stopListening()
    }

    fun cancel() {
        generation += 1
        explicitStop = true
        cancelScheduledRestart()
        cancelFinishTimeout()
        recognizer?.cancel()
        stopDurationClock()
        draftText = ""
        snapshot = VoiceInputSnapshot()
        onSnapshot(snapshot)
    }

    fun retry() {
        if (snapshot.phase != VoiceInputPhase.Failed) return
        start(draftText, snapshot.continuous)
    }

    fun discard() {
        cancel()
    }

    fun toggleMute() {
        if (!snapshot.continuous || snapshot.phase == VoiceInputPhase.Idle) return
        val nextMuted = !snapshot.muted
        if (nextMuted) {
            generation += 1
            recognizer?.cancel()
            cancelScheduledRestart()
            cancelFinishTimeout()
            publish(muted = true, volume = 0f)
        } else {
            publish(muted = false, error = null)
            restartAttempts = 0
            startRecognition(generation)
        }
    }

    /** Accepts the reviewed transcript and resets the controller. */
    fun acceptTranscript(onAccepted: (String) -> Unit): Boolean {
        val text = snapshot.transcript.trim()
        if (text.isBlank() || snapshot.phase != VoiceInputPhase.Review) return false
        onAccepted(text)
        cancel()
        return true
    }

    fun destroy() {
        generation += 1
        cancelScheduledRestart()
        cancelFinishTimeout()
        stopDurationClock()
        recognizer?.cancel()
        recognizer?.destroy()
    }

    private fun startRecognition(expectedGeneration: Long) {
        if (expectedGeneration != generation || snapshot.phase != VoiceInputPhase.Listening || snapshot.muted) return
        val speechRecognizer = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (chinese) "zh-CN" else Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, if (chinese) "zh-CN" else Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Let OEM speech services use an offline model when available.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        runCatching {
            speechRecognizer.cancel()
            speechRecognizer.startListening(intent)
        }.onFailure { error ->
            fail(error.message ?: if (chinese) "无法开始语音识别" else "Could not start speech recognition")
        }
    }

    private fun handleResult(text: String) {
        if (snapshot.phase != VoiceInputPhase.Listening && snapshot.phase != VoiceInputPhase.Processing) return
        cancelFinishTimeout()
        val normalized = text.trim()
        if (snapshot.continuous && !explicitStop) {
            if (normalized.isNotBlank()) {
                onContinuousTranscript?.invoke(normalized)
            }
            publish(
                phase = VoiceInputPhase.Listening,
                partialTranscript = "",
                transcript = "",
                volume = 0f,
                error = null,
            )
            restartAttempts = 0
            scheduleRecognitionRestart()
            return
        }

        stopDurationClock()
        if (normalized.isBlank()) {
            fail(if (chinese) "没有识别到语音" else "No speech was recognized")
            return
        }
        publish(
            phase = VoiceInputPhase.Review,
            partialTranscript = "",
            transcript = normalized,
            volume = 0f,
            error = null,
        )
    }

    private fun handleError(errorCode: Int) {
        if (snapshot.phase == VoiceInputPhase.Idle || snapshot.muted || explicitStop) return
        val transient = when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            -> true
            else -> false
        }
        if (snapshot.continuous && transient && !snapshot.muted) {
            scheduleRecognitionRestart()
            return
        }
        val message = when (errorCode) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (chinese) "没有录音权限" else "Microphone permission is required"
            SpeechRecognizer.ERROR_AUDIO -> if (chinese) "麦克风不可用" else "The microphone is unavailable"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (chinese) "语音服务网络异常" else "Speech service network error"
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (chinese) "没有识别到语音" else "No speech was recognized"
            else -> if (chinese) "语音识别失败，请重试" else "Speech recognition failed; try again"
        }
        fail(message)
    }

    private fun scheduleRecognitionRestart() {
        if (snapshot.phase != VoiceInputPhase.Listening || snapshot.muted) return
        cancelScheduledRestart()
        val delayMs = min(3000L, 250L * (1L shl min(restartAttempts, 3)))
        restartAttempts += 1
        val expectedGeneration = generation
        val runnable = Runnable { startRecognition(expectedGeneration) }
        restartRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelScheduledRestart() {
        restartRunnable?.let(handler::removeCallbacks)
        restartRunnable = null
    }

    private fun fail(message: String) {
        stopDurationClock()
        publish(phase = VoiceInputPhase.Failed, volume = 0f, error = message)
    }

    private fun startDurationClock() {
        stopDurationClock()
        val startedAt = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (!snapshot.active) return
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                publish(durationSeconds = max(0, seconds))
                handler.postDelayed(this, 1000L)
            }
        }
        durationRunnable = runnable
        handler.post(runnable)
    }

    private fun stopDurationClock() {
        durationRunnable?.let(handler::removeCallbacks)
        durationRunnable = null
    }

    private fun cancelFinishTimeout() {
        finishTimeoutRunnable?.let(handler::removeCallbacks)
        finishTimeoutRunnable = null
    }

    private fun publish(
        phase: VoiceInputPhase = snapshot.phase,
        continuous: Boolean = snapshot.continuous,
        muted: Boolean = snapshot.muted,
        partialTranscript: String = snapshot.partialTranscript,
        transcript: String = snapshot.transcript,
        volume: Float = snapshot.volume,
        durationSeconds: Int = snapshot.durationSeconds,
        error: String? = snapshot.error,
    ) {
        snapshot = VoiceInputSnapshot(
            phase = phase,
            continuous = continuous,
            muted = muted,
            partialTranscript = partialTranscript,
            transcript = transcript,
            volume = volume.coerceIn(0f, 1f),
            durationSeconds = durationSeconds.coerceAtLeast(0),
            error = error,
        )
        onSnapshot(snapshot)
    }

    private fun bestResult(results: Bundle?): String {
        return results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.asSequence()
            ?.map(String::trim)
            ?.firstOrNull(String::isNotBlank)
            .orEmpty()
    }
}
