package com.example.itantra.data.prefs

import android.content.Context
import com.example.itantra.core.Language

object TranslationPrefs {
    private const val PREFS = "itantra_translation"
    private const val KEY_TARGET_LANGUAGE = "target_language"

    fun getTargetLanguage(context: Context): Language {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TARGET_LANGUAGE, Language.HINDI.name)
        return Language.fromTag(saved)
    }

    fun setTargetLanguage(context: Context, language: Language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_LANGUAGE, language.name)
            .apply()
    }
}