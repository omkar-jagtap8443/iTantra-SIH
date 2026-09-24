package com.example.itantra.transport

import com.example.itantra.data.model.WireMessage
import kotlinx.coroutines.flow.Flow

interface Transport {
    val events: Flow<TransportEvent>
    suspend fun connect(host: String, port: Int)
    suspend fun send(message: WireMessage)
    fun close()
}

sealed class TransportEvent {
    data object Connected : TransportEvent()
    data object Disconnected : TransportEvent()
    data class MessageReceived(val msg: WireMessage) : TransportEvent()
    data class Error(val reason: String) : TransportEvent()
}