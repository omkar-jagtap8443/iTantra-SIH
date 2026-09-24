package com.example.itantra.core

enum class Language(val bcp47: String, val display: String) {
    ENGLISH("en-IN", "English"),
    HINDI("hi-IN", "हिन्दी"),
    GUJARATI("gu-IN", "ગુજરાતી"),
    TELUGU("te-IN", "తెలుగు");

    companion object {
        fun fromTag(tag: String?) =
            entries.firstOrNull { it.name == tag } ?: ENGLISH
    }
}