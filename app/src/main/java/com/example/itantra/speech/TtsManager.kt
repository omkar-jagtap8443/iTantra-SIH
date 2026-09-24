package com.example.itantra.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.itantra.core.AppConstants
import com.example.itantra.core.Language
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

class TtsManager {
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private var appContext: Context? = null
    private var toneGenerator: ToneGenerator? = null

    fun init(ctx: Context) {
        if (tts != null) return
        appContext = ctx.applicationContext
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureForLoudAudio()
            }
            ready.complete(status == TextToSpeech.SUCCESS)
        }
    }

    private fun configureForLoudAudio() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)          // Alarm stream = loudest, bypasses silent
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i(AppConstants.TAG, "TTS started: $utteranceId")
                }
                override fun onDone(utteranceId: String?) {
                    Log.i(AppConstants.TAG, "TTS done: $utteranceId")
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(AppConstants.TAG, "TTS error: $utteranceId")
                }
            })

            Log.i(AppConstants.TAG, "TTS configured for MAX alarm stream")
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "Failed to set audio attributes", e)
        }
    }

    private fun maximizeAlarmVolume() {
        try {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                val maxVol = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                it.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                Log.i(AppConstants.TAG, "Alarm volume maxed: $maxVol")
            }
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "Volume boost failed", e)
        }
    }

    private fun beepBeforeSpeaking() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {}
    }

    suspend fun speak(text: String, lang: Language) {
        if (!ready.await()) {
            Log.w(AppConstants.TAG, "TTS not ready")
            return
        }
        maximizeAlarmVolume()

        val locale = Locale.forLanguageTag(lang.bcp47)
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(AppConstants.TAG, "Language ${lang.bcp47} not supported, using US English")
            tts?.language = Locale.US
        }

        tts?.setSpeechRate(0.9f)
        tts?.setPitch(1.05f)

        // Optional beep to alert the user before speaking
        beepBeforeSpeaking()

        val utteranceId = "itantra-${System.nanoTime()}"
        Log.i(AppConstants.TAG, "TTS speaking: '$text' in ${lang.bcp47}")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        try { toneGenerator?.release() } catch (_: Exception) {}
        toneGenerator = null
        tts = null
    }
}