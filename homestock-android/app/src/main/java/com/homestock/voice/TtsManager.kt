package com.homestock.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Android's [TextToSpeech] used by the voice-search flow
 * to speak the answer to "où est X ?". Singleton-scoped so the engine is
 * initialized once per process — TTS init is slow (sometimes >1s on cold
 * start). Calls before init resolve are buffered into a single "pending"
 * utterance so a quick voice query right after app launch isn't dropped.
 */
@Singleton
class TtsManager @Inject constructor(@ApplicationContext context: Context) {

    private val ready = AtomicBoolean(false)
    private var pending: Pair<String, String>? = null  // (text, locale-language)

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready.set(true)
            pending?.let { (text, lang) ->
                applyLanguage(lang)
                speakNow(text)
            }
            pending = null
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    /**
     * Speaks [text]. [language] is a BCP-47 tag like "fr-FR"; falls back to
     * device default if unsupported. New calls interrupt anything in flight
     * (search-by-voice is interactive, not a long-form reader).
     */
    fun speak(text: String, language: String = "fr-FR") {
        if (text.isBlank()) return
        if (!ready.get()) {
            pending = text to language
            return
        }
        applyLanguage(language)
        speakNow(text)
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private fun applyLanguage(lang: String) {
        val locale = Locale.forLanguageTag(lang)
        val status = tts.setLanguage(locale)
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "homestock-tts")
    }
}
