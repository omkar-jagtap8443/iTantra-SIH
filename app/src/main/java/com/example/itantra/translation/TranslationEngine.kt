package com.example.itantra.translation

import com.example.itantra.core.Language

interface TranslationEngine {
    suspend fun translate(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language,
    ): TranslationOutcome

    fun close() {}
}