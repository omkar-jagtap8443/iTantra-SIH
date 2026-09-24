package com.example.itantra.transport

import android.util.Log
import com.example.itantra.core.AppConstants
import com.example.itantra.data.model.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class LocalTcpTransport : Transport {

    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableSharedFlow<TransportEvent>()
    override val events = _events.asSharedFlow()

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    override suspend fun connect(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                Log.i(AppConstants.TAG, "Connected to $host:$port")
                _events.emit(TransportEvent.Connected)
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "connect failed", e)
                _events.emit(TransportEvent.Error(e.message ?: "connect failed"))
            }
        }
    }

    override suspend fun send(message: WireMessage) {
        withContext(Dispatchers.IO) {
            try {
                val line = json.encodeToString(WireMessage.serializer(), message) + "\n"
                writer?.write(line)
                writer?.flush()
                Log.i(AppConstants.TAG, "Sent: ${message.payload}")
            } catch (e: Exception) {
                _events.emit(TransportEvent.Error("send failed: ${e.message}"))
            }
        }
    }

    override fun close() {
        runCatching { writer?.close(); socket?.close() }
        socket = null; writer = null
    }
}