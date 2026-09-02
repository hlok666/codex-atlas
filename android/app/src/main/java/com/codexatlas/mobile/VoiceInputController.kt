package com.codexatlas.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class VoiceInputPhase {
    Idle,
    Listening,
    Processing,
    Review,
    Failed,
}

/** State rendered by the voice panel. Error details are intentionally separate from copy. */
data class VoiceInputSnapshot(
    val phase: VoiceInputPhase = VoiceInputPhase.Idle,
    val continuous: Boolean = false,
    val muted: Boolean = false,
    val partialTranscript: String = "",
    val transcript: String = "",
    val volume: Float = 0f,
    val durationSeconds: Int = 0,
    val error: String? = null,
    val errorCode: Int? = null,
    val providerName: String = "",
    val serviceAvailable: Boolean = true,
) {
    val active: Boolean
        get() = phase != VoiceInputPhase.Idle
}

data class VoiceServiceStatus(
    val available: Boolean,
    val providerName: String,
)

/**
 * Owns one Android SpeechRecognizer operation at a time.
 *
 * Several OEM speech services (notably ColorOS builds) do not tolerate a
 * cancel/start pair on the same recognizer. Every attempt therefore gets a
 * fresh recognizer and a short main-thread handoff. Stale callbacks are
 * rejected with operation and recognizer tokens so a previous session cannot
 * overwrite the current UI.
 */
class VoiceInputController(
    context: Context,
    private val chinese: Boolean,
    private val onSnapshot: (VoiceInputSnapshot) -> Unit,
) {
    private val appContext: Context = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())

    private var provider = detectProvider()
    private var recognizer: SpeechRecognizer? = null
    private var snapshot = VoiceInputSnapshot(
        providerName = provider.providerName,
        serviceAvailable = provider.available,
    )
    private var draftText = ""
    private var operationToken = 0L
    private var recognizerToken = 0L
    private var restartAttempts = 0
    private var startFailures = 0
    private var explicitStop = false
    private var destroyed = false
    private var restartRunnable: Runnable? = null
    private var readyTimeoutRunnable: Runnable? = null
    private var durationRunnable: Runnable? = null
    private var finishTimeoutRunnable: Runnable? = null
    private var onContinuousTranscript: ((String) -> Unit)? = null

    fun setContinuousTranscriptListener(listener: ((String) -> Unit)?) {
        onContinuousTranscript = listener
    }

    fun snapshot(): VoiceInputSnapshot = snapshot

    fun serviceStatus(): VoiceServiceStatus {
        provider = detectProvider()
        return VoiceServiceStatus(provider.available, provider.providerName)
    }

    fun start(draft: String = "", continuous: Boolean = false) {
        if (destroyed) return

        operationToken += 1
        val expectedOperation = operationToken
        explicitStop = false
        draftText = draft.trim()
        restartAttempts = 0
        startFailures = 0
        cancelScheduledRestart()
        cancelReadyTimeout()
        cancelFinishTimeout()
        destroyRecognizer()

        provider = detectProvider()
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(
                if (chinese) "麦克风权限未开启，请允许录音权限后重试" else "Microphone permission is required; allow it and try again",
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            )
            return
        }
        publish(
            phase = VoiceInputPhase.Listening,
            continuous = continuous,
            muted = false,
            partialTranscript = "",
            transcript = "",
            volume = 0f,
            durationSeconds = 0,
            error = null,
            errorCode = null,
            providerName = provider.providerName,
            serviceAvailable = provider.available,
        )
        startDurationClock()
        scheduleRecognitionStart(expectedOperation, 120L)
    }

    fun stop() {
        if (snapshot.phase != VoiceInputPhase.Listening) return

        explicitStop = true
        cancelScheduledRestart()
        cancelReadyTimeout()
        cancelFinishTimeout()
        publish(phase = VoiceInputPhase.Processing, volume = 0f, error = null, errorCode = null)

        val expectedOperation = operationToken
        val timeout = Runnable {
            if (isCurrentOperation(expectedOperation) && snapshot.phase == VoiceInputPhase.Processing) {
                val partial = snapshot.partialTranscript.trim()
                if (partial.isNotBlank()) {
                    handleResult(expectedOperation, recognizerToken, partial)
                } else {
                    fail(noSpeechMessage(), SpeechRecognizer.ERROR_NO_MATCH)
                }
            }
        }
        finishTimeoutRunnable = timeout
        handler.postDelayed(timeout, 1_800L)

        val current = recognizer
        if (current == null) {
            fail(noSpeechMessage(), SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        runCatching { current.stopListening() }
            .onFailure { fail(if (chinese) "无法停止语音识别，请重试" else "Could not stop speech recognition; try again", SpeechRecognizer.ERROR_CLIENT) }
    }

    fun cancel() {
        if (destroyed) return
        operationToken += 1
        explicitStop = true
        cancelScheduledRestart()
        cancelFinishTimeout()
        stopDurationClock()
        destroyRecognizer()
        draftText = ""
        publish(
            phase = VoiceInputPhase.Idle,
            continuous = false,
            muted = false,
            partialTranscript = "",
            transcript = "",
            volume = 0f,
            durationSeconds = 0,
            error = null,
            errorCode = null,
            providerName = provider.providerName,
            serviceAvailable = provider.available,
        )
    }

    fun retry() {
        if (snapshot.phase != VoiceInputPhase.Failed) return
        start(draftText, snapshot.continuous)
    }

    fun discard() = cancel()

    fun toggleMute() {
        if (!snapshot.continuous || snapshot.phase == VoiceInputPhase.Idle) return
        if (!snapshot.muted) {
            operationToken += 1
            cancelScheduledRestart()
            cancelReadyTimeout()
            cancelFinishTimeout()
            destroyRecognizer()
            publish(muted = true, volume = 0f, error = null, errorCode = null)
            return
        }

        operationToken += 1
        explicitStop = false
        restartAttempts = 0
        startFailures = 0
        publish(muted = false, error = null, errorCode = null, phase = VoiceInputPhase.Listening)
        scheduleRecognitionStart(operationToken, 120L)
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
        if (destroyed) return
        destroyed = true
        operationToken += 1
        cancelScheduledRestart()
        cancelReadyTimeout()
        cancelFinishTimeout()
        stopDurationClock()
        destroyRecognizer()
        onContinuousTranscript = null
    }

    private fun scheduleRecognitionStart(expectedOperation: Long, delayMs: Long) {
        if (!isCurrentOperation(expectedOperation) || snapshot.phase != VoiceInputPhase.Listening || snapshot.muted) return
        cancelScheduledRestart()
        val runnable = Runnable {
            restartRunnable = null
            startRecognition(expectedOperation)
        }
        restartRunnable = runnable
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    private fun startRecognition(expectedOperation: Long) {
        if (!isCurrentOperation(expectedOperation) || snapshot.phase != VoiceInputPhase.Listening || snapshot.muted) return
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(
                if (chinese) "麦克风权限已被撤回，请在系统设置中允许后重试" else "Microphone permission is no longer granted; allow it in Settings",
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            )
            return
        }

        provider = detectProvider()
        // ColorOS can report no available recognizer even when its default
        // binder service works. Creation plus callback watchdog is the real
        // capability test, so provider.available is diagnostic only.
        destroyRecognizer()
        val token = ++recognizerToken
        val created = runCatching {
            provider.component?.let { component ->
                SpeechRecognizer.createSpeechRecognizer(appContext, component)
            } ?: SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrNull()
        if (created == null) {
            handleStartFailure(expectedOperation, token, null)
            return
        }

        recognizer = created
        runCatching {
            created.setRecognitionListener(listenerFor(expectedOperation, token))
        }.onFailure {
            handleStartFailure(expectedOperation, token, it)
            return
        }

        val intent = recognitionIntent()
        // Give ColorOS time to release the previous binder connection after
        // destroy(); starting synchronously is a common source of ERROR_CLIENT.
        val launch = Runnable {
            restartRunnable = null
            if (!isCurrentRecognizer(expectedOperation, token) || snapshot.phase != VoiceInputPhase.Listening || snapshot.muted) return@Runnable
            runCatching { created.startListening(intent) }
                .onSuccess { scheduleReadyTimeout(expectedOperation, token) }
                .onFailure { error -> handleStartFailure(expectedOperation, token, error) }
        }
        restartRunnable = launch
        handler.postDelayed(launch, 120L)
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        val language = if (chinese) Locale.SIMPLIFIED_CHINESE.toLanguageTag() else Locale.getDefault().toLanguageTag()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
    }

    private fun listenerFor(expectedOperation: Long, expectedRecognizer: Long): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isCurrentRecognizer(expectedOperation, expectedRecognizer)) return
                cancelReadyTimeout()
                startFailures = 0
                restartAttempts = 0
                publish(
                    error = null,
                    errorCode = null,
                    serviceAvailable = true,
                    providerName = snapshot.providerName.ifBlank {
                        if (chinese) "系统语音服务" else "System speech service"
                    },
                )
            }

            override fun onBeginningOfSpeech() {
                if (!isCurrentRecognizer(expectedOperation, expectedRecognizer)) return
                cancelReadyTimeout()
                startFailures = 0
                restartAttempts = 0
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isCurrentRecognizer(expectedOperation, expectedRecognizer)) return
                cancelReadyTimeout()
                val normalized = ((rmsdB + 2f) / 14f).coerceIn(0f, 1f)
                publish(volume = normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!isCurrentRecognizer(expectedOperation, expectedRecognizer)) return
                publish(volume = 0f)
            }

            override fun onPartialResults(results: Bundle?) {
                if (!isCurrentRecognizer(expectedOperation, expectedRecognizer)) return
                cancelReadyTimeout()
                val text = bestResult(results)
                if (text.isNotBlank() && snapshot.phase == VoiceInputPhase.Listening) {
                    publish(partialTranscript = text, volume = snapshot.volume)
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrentRecognizer(expectedOperation, expectedRecognizer)) return
                cancelReadyTimeout()
                handleResult(expectedOperation, expectedRecognizer, bestResult(results))
            }

            override fun onError(error: Int) {
                if (!isCurrentRecognizer(expectedOperation, expectedRecognizer)) return
                cancelReadyTimeout()
                handleError(expectedOperation, expectedRecognizer, error)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun scheduleReadyTimeout(expectedOperation: Long, expectedRecognizer: Long) {
        cancelReadyTimeout()
        val timeout = Runnable {
            readyTimeoutRunnable = null
            if (!isCurrentRecognizer(expectedOperation, expectedRecognizer)) return@Runnable
            handleStartFailure(expectedOperation, expectedRecognizer, null)
        }
        readyTimeoutRunnable = timeout
        handler.postDelayed(timeout, READY_TIMEOUT_MS)
    }

    private fun handleStartFailure(expectedOperation: Long, expectedRecognizer: Long, error: Throwable?) {
        if (!isCurrentOperation(expectedOperation)) return
        destroyRecognizer()
        if (startFailures < MAX_START_FAILURES) {
            startFailures += 1
            scheduleRecognitionStart(expectedOperation, 250L * startFailures)
            return
        }
        val detail = error?.message?.takeIf { it.isNotBlank() }
        fail(
            if (chinese) {
                "语音识别服务没有响应，请检查系统语音输入设置后重试${if (detail != null) "（$detail）" else ""}"
            } else {
                "The speech service did not respond. Check voice input settings and try again${if (detail != null) " ($detail)" else ""}"
            },
            SpeechRecognizer.ERROR_CLIENT,
        )
    }

    private fun handleResult(expectedOperation: Long, expectedRecognizer: Long, rawText: String) {
        if (!isCurrentOperation(expectedOperation)) return
        cancelFinishTimeout()
        val normalized = rawText.trim().ifBlank { snapshot.partialTranscript.trim() }
        if (snapshot.continuous && !explicitStop) {
            destroyRecognizer()
            if (normalized.isNotBlank()) runCatching { onContinuousTranscript?.invoke(normalized) }
            publish(
                phase = VoiceInputPhase.Listening,
                partialTranscript = "",
                transcript = "",
                volume = 0f,
                error = null,
                errorCode = null,
            )
            restartAttempts = 0
            scheduleRecognitionStart(expectedOperation, 140L)
            return
        }

        destroyRecognizer()
        stopDurationClock()
        if (normalized.isBlank()) {
            fail(noSpeechMessage(), SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        publish(
            phase = VoiceInputPhase.Review,
            partialTranscript = "",
            transcript = normalized,
            volume = 0f,
            error = null,
            errorCode = null,
        )
    }

    private fun handleError(expectedOperation: Long, expectedRecognizer: Long, errorCode: Int) {
        if (!isCurrentOperation(expectedOperation) || snapshot.phase == VoiceInputPhase.Idle || snapshot.muted) return
        cancelFinishTimeout()

        // A stop is a user decision. OEMs often report ERROR_CLIENT instead of
        // onResults after stopListening; surface the partial text immediately
        // instead of leaving the panel in Processing until a timeout.
        if (explicitStop || snapshot.phase == VoiceInputPhase.Processing) {
            val partial = snapshot.partialTranscript.trim()
            if (partial.isNotBlank()) {
                handleResult(expectedOperation, expectedRecognizer, partial)
            } else {
                fail(errorMessage(errorCode), errorCode)
            }
            return
        }

        val transient = errorCode in setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            ERROR_SERVER_DISCONNECTED_COMPAT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            ERROR_TOO_MANY_REQUESTS_COMPAT,
        )
        val retryable = (snapshot.continuous && transient && restartAttempts < MAX_RESTART_ATTEMPTS) ||
            (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && restartAttempts < 2)
        if (retryable) {
            restartAttempts += 1
            destroyRecognizer()
            val delayMs = min(2_500L, 180L * (1L shl min(restartAttempts, 4)))
            scheduleRecognitionStart(expectedOperation, delayMs)
            return
        }

        fail(errorMessage(errorCode), errorCode)
    }

    private fun errorMessage(errorCode: Int): String {
        val message = when (errorCode) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (chinese) "没有录音权限" else "Microphone permission is required"
            SpeechRecognizer.ERROR_AUDIO -> if (chinese) "麦克风不可用，请检查系统权限或其他录音应用" else "The microphone is unavailable; check permissions and other recording apps"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (chinese) "语音服务网络异常" else "Speech service network error"
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (chinese) "没有识别到语音，请靠近麦克风再试" else "No speech was recognized; try speaking closer to the microphone"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (chinese) "系统语音服务正忙，请重试" else "The system speech service is busy; try again"
            SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED_COMPAT -> if (chinese) "系统语音服务暂时不可用" else "The system speech service is temporarily unavailable"
            ERROR_TOO_MANY_REQUESTS_COMPAT -> if (chinese) "语音请求过于频繁，请稍后重试" else "Too many speech requests; try again shortly"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> if (chinese) "系统语音服务不支持当前语言" else "The speech service does not support this language"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> if (chinese) "当前语言包未安装，请在系统语音设置中下载" else "The language pack is unavailable; download it in voice settings"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> if (chinese) "无法检查语音服务能力" else "Could not check speech service support"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> if (chinese) "无法获取语音语言包状态" else "Could not read speech model download status"
            SpeechRecognizer.ERROR_CLIENT -> if (chinese) "语音识别服务没有响应，请重试" else "The speech service did not respond; try again"
            else -> if (chinese) "语音识别失败，请重试" else "Speech recognition failed; try again"
        }
        val providerSuffix = provider.providerName.takeIf { it.isNotBlank() }?.let {
            if (chinese) "（服务：$it，错误码 $errorCode）" else " (service: $it, error $errorCode)"
        }.orEmpty()
        return message + providerSuffix
    }

    private fun noSpeechMessage(): String = if (chinese) "没有识别到语音，请重试" else "No speech was recognized; try again"

    private fun detectProvider(): RecognitionProvider {
        val packageManager = appContext.packageManager
        val services = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(
                Intent("android.speech.RecognitionService"),
                PackageManager.MATCH_ALL,
            )
        }.getOrDefault(emptyList())

        val configured = runCatching {
            // Android does not expose this key as a public constant on all API levels.
            Settings.Secure.getString(appContext.contentResolver, "voice_recognition_service")
        }.getOrNull().orEmpty()
        val configuredComponent = ComponentName.unflattenFromString(configured)
        val configuredService = services.firstOrNull { info ->
            val service = info.serviceInfo ?: return@firstOrNull false
            configuredComponent?.let { it.packageName == service.packageName && it.className == service.name }
                ?: configured.substringBefore('/').takeIf { it.isNotBlank() }?.let { it == service.packageName }
                ?: false
        }?.serviceInfo
        val selected = configuredService ?: services.firstOrNull()?.serviceInfo

        // Let Android choose its default recognizer unless the user has an
        // explicit configured service. Query order is not a quality signal.
        val component = configuredService?.let { ComponentName(it.packageName, it.name) }
        val available = selected != null || runCatching {
            SpeechRecognizer.isRecognitionAvailable(appContext)
        }.getOrDefault(false)
        val label = configuredService?.applicationInfo?.loadLabel(packageManager)?.toString()?.trim().orEmpty()
        return RecognitionProvider(
            available = available,
            providerName = label.ifBlank {
                component?.packageName?.takeIf { it.isNotBlank() }
                    ?: if (available) (if (chinese) "系统语音服务" else "System speech service") else ""
            },
            component = component,
        )
    }

    private fun isCurrentOperation(expectedOperation: Long): Boolean =
        !destroyed && expectedOperation == operationToken

    private fun isCurrentRecognizer(expectedOperation: Long, expectedRecognizer: Long): Boolean =
        isCurrentOperation(expectedOperation) && expectedRecognizer == recognizerToken && recognizer != null

    private fun destroyRecognizer() {
        cancelReadyTimeout()
        recognizer?.let { current -> runCatching { current.cancel() }; runCatching { current.destroy() } }
        recognizer = null
    }

    private fun cancelScheduledRestart() {
        restartRunnable?.let(handler::removeCallbacks)
        restartRunnable = null
    }

    private fun cancelReadyTimeout() {
        readyTimeoutRunnable?.let(handler::removeCallbacks)
        readyTimeoutRunnable = null
    }

    private fun fail(message: String, errorCode: Int?) {
        cancelScheduledRestart()
        cancelFinishTimeout()
        stopDurationClock()
        destroyRecognizer()
        publish(phase = VoiceInputPhase.Failed, volume = 0f, error = message, errorCode = errorCode)
    }

    private fun startDurationClock() {
        stopDurationClock()
        val startedAt = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (!snapshot.active) return
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                publish(durationSeconds = max(0, seconds))
                handler.postDelayed(this, 1_000L)
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
        errorCode: Int? = snapshot.errorCode,
        providerName: String = snapshot.providerName,
        serviceAvailable: Boolean = snapshot.serviceAvailable,
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
            errorCode = errorCode,
            providerName = providerName,
            serviceAvailable = serviceAvailable,
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

    private data class RecognitionProvider(
        val available: Boolean,
        val providerName: String,
        val component: ComponentName?,
    )

    private companion object {
        const val MAX_START_FAILURES = 2
        const val MAX_RESTART_ATTEMPTS = 8
        const val READY_TIMEOUT_MS = 4_000L
        // These callback values are stable but their SDK constants were added
        // after Atlas's API 26 minimum, so keep numeric compatibility here.
        const val ERROR_TOO_MANY_REQUESTS_COMPAT = 10
        const val ERROR_SERVER_DISCONNECTED_COMPAT = 11
    }
}
