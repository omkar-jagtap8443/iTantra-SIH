package com.example.itantra.core

import com.example.itantra.speech.TtsManager
import com.example.itantra.speech.VoskSttManager
import com.example.itantra.translation.TranslationEngine
import com.example.itantra.translation.TranslationManager

object ServiceLocator {
    val ttsManager by lazy { TtsManager() }
    val voskSttManager by lazy { VoskSttManager() }
    val translationEngine: TranslationEngine by lazy { TranslationManager() }
}