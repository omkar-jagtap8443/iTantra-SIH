package com.example.itantra.speech

import android.content.Context
import android.util.Log
import com.example.itantra.core.AppConstants
import com.example.itantra.core.Language
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File

class VoskSttManager {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var currentLang: Language? = null
    private var isReady = false

    fun start(
        context: Context,
        lang: Language,
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val currentModel = model
        if (currentModel == null || currentLang != lang) {
            loadModel(
                context = context,
                lang = lang,
                onReady = { start(context, lang, onResult, onPartial, onError) },
                onError = onError
            )
            return
        }

        stop()

        try {
            val recognizer = Recognizer(currentModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)

            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    hypothesis?.let {
                        val text = extractText(it)
                        if (text.isNotBlank()) onPartial(text)
                    }
                }

                override fun onResult(hypothesis: String?) {
                    hypothesis?.let {
                        val text = extractText(it)
                        if (text.isNotBlank()) onResult(text)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    hypothesis?.let {
                        val text = extractText(it)
                        if (text.isNotBlank()) onResult(text)
                    }
                }

                override fun onError(exception: Exception?) {
                    Log.e(AppConstants.TAG, "Vosk error", exception)
                    onError(exception?.message ?: "Recognition error")
                }

                override fun onTimeout() {
                    onError("Recognition timeout")
                }
            })
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "Vosk start failed", e)
            onError("Start failed: ${e.message}")
        }
    }

    private fun loadModel(
        context: Context,
        lang: Language,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        release()

        val assetName = assetNameFor(lang)
        val destFolder = "vosk-${lang.name.lowercase()}"

        Log.i(AppConstants.TAG, "=== loadModel: $assetName → $destFolder ===")

        // Diagnostic: list what's inside the asset folder
        try {
            val files = context.assets.list(assetName)
            Log.i(AppConstants.TAG, "Assets in '$assetName': ${files?.joinToString(", ") ?: "NONE"}")

            // Check if uuid exists in the asset
            val uuidCheck = context.assets.list("$assetName/uuid")
            Log.i(AppConstants.TAG, "uuid check: ${if (uuidCheck.isNullOrEmpty()) "MISSING" else "FOUND"}")

            // Check if am exists
            val amCheck = context.assets.list("$assetName/am")
            Log.i(AppConstants.TAG, "am check: ${amCheck?.joinToString(", ") ?: "MISSING"}")
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "Asset listing failed", e)
        }

        // Check if a previously-extracted copy exists — if broken, delete it
        val externalDir = context.getExternalFilesDir(null)
        val destDir = File(externalDir, destFolder)
        if (destDir.exists()) {
            Log.i(AppConstants.TAG, "Deleting stale cache: ${destDir.absolutePath}")
            destDir.deleteRecursively()
        }

        StorageService.unpack(
            context,
            assetName,
            destFolder,
            { loadedModel ->
                model = loadedModel
                currentLang = lang
                isReady = true
                Log.i(AppConstants.TAG, "Vosk model loaded OK for ${lang.display}")
                onReady()
            },
            { exception ->
                Log.e(AppConstants.TAG, "Vosk model load failed", exception)
                onError("Model load failed: ${exception.message}")
            }
        )
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null
    }

    fun release() {
        stop()
        try {
            model?.close()
        } catch (_: Exception) {}
        model = null
        currentLang = null
        isReady = false
    }

    private fun assetNameFor(lang: Language): String = when (lang) {
        Language.ENGLISH -> "vosk-model-small-en-in-0.4"
        Language.HINDI -> "vosk-model-small-hi-0.22"
        Language.GUJARATI -> "vosk-model-small-gu-0.42"
        Language.TELUGU -> "vosk-model-small-te-0.42"
    }

    private fun extractText(json: String): String {
        return try {
            val obj = JSONObject(json)
            when {
                obj.has("text") -> obj.getString("text").trim()
                obj.has("partial") -> obj.getString("partial").trim()
                else -> ""
            }
        } catch (_: Exception) {
            json.trim()
        }
    }
}