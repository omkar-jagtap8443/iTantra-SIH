package com.example.itantra.translation

import com.example.itantra.core.Language
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class TranslationOutcome {
    data class Success(
        val sourceText: String,
        val translatedText: String,
        val sourceLanguage: Language,
        val targetLanguage: Language,
    ) : TranslationOutcome()

    data class Failure(
        val sourceText: String,
        val sourceLanguage: Language,
        val targetLanguage: Language,
        val reason: String,
    ) : TranslationOutcome()
}

class TranslationManager : TranslationEngine {
    private val translators = mutableMapOf<String, Translator>()

    override suspend fun translate(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language,
    ): TranslationOutcome = withContext(Dispatchers.IO) {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return@withContext TranslationOutcome.Failure(
                sourceText = text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                reason = "No text to translate",
            )
        }

        if (sourceLanguage == targetLanguage) {
            return@withContext TranslationOutcome.Success(
                sourceText = normalized,
                translatedText = normalized,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )
        }

        val translator = getTranslator(sourceLanguage, targetLanguage)
        try {
            translator.downloadModelIfNeeded().awaitUnit()
            val translated = translator.translate(normalized).awaitString().trim()
            if (translated.isBlank()) {
                return@withContext TranslationOutcome.Failure(
                    sourceText = normalized,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    reason = "Translation returned empty text",
                )
            }
            TranslationOutcome.Success(
                sourceText = normalized,
                translatedText = translated,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )
        } catch (e: Exception) {
            TranslationOutcome.Failure(
                sourceText = normalized,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                reason = e.message ?: "Translation failed",
            )
        }
    }

    override fun close() {
        translators.values.forEach { translator ->
            runCatching { translator.close() }
        }
        translators.clear()
    }

    private fun getTranslator(sourceLanguage: Language, targetLanguage: Language): Translator {
        val key = "${sourceLanguage.name}:${targetLanguage.name}"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(toMlKitLanguage(sourceLanguage))
                .setTargetLanguage(toMlKitLanguage(targetLanguage))
                .build()
            Translation.getClient(options)
        }
    }

    private fun toMlKitLanguage(language: Language): String = when (language) {
        Language.ENGLISH -> TranslateLanguage.ENGLISH
        Language.HINDI -> TranslateLanguage.HINDI
        Language.GUJARATI -> TranslateLanguage.GUJARATI
        Language.TELUGU -> TranslateLanguage.TELUGU
        Language.MARATHI -> TranslateLanguage.MARATHI
    }
}

private suspend fun Task<Void>.awaitUnit() = suspendCancellableCoroutine<Unit> { continuation ->
    addOnSuccessListener {
        continuation.resume(Unit)
    }.addOnFailureListener { exception ->
        continuation.resumeWithException(exception)
    }
}

private suspend fun Task<String>.awaitString() = suspendCancellableCoroutine<String> { continuation ->
    addOnSuccessListener { value ->
        continuation.resume(value)
    }.addOnFailureListener { exception ->
        continuation.resumeWithException(exception)
    }
}