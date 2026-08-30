package com.codexatlas.mobile

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Small lifecycle wrapper for optional assistant reply playback. */
class SpeechOutputController(
    context: Context,
    private val chinese: Boolean,
) : TextToSpeech.OnInitListener {
    private val engine = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val locale = if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.US
        val result = engine.setLanguage(locale)
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun isReady(): Boolean = ready

    fun speak(text: String) {
        val normalized = text.trim()
        if (!ready || normalized.isBlank()) return
        engine.speak(normalized.take(4000), TextToSpeech.QUEUE_FLUSH, null, "atlas-reply")
    }

    fun stop() {
        engine.stop()
    }

    fun destroy() {
        engine.stop()
        engine.shutdown()
    }
}
