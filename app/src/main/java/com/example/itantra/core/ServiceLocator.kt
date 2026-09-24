package com.example.itantra.core

import com.example.itantra.speech.TtsManager
import com.example.itantra.speech.VoskSttManager

object ServiceLocator {
    val ttsManager by lazy { TtsManager() }
    val voskSttManager by lazy { VoskSttManager() }
}