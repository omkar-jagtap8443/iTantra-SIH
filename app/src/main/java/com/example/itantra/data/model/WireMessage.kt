package com.example.itantra.data.model

import kotlinx.serialization.Serializable

@Serializable
data class WireMessage(
    val v: Int = 1,
    val type: String = "text",
    val from: String,
    val lang: String,
    val payload: String,
    val priority: String = "NORMAL",  // "NORMAL", "URGENT", "SOS"
    val senderName: String = "",
    val ts: Long = System.currentTimeMillis()
)