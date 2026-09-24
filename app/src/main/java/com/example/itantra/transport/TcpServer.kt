package com.example.itantra.transport

import android.util.Log
import com.example.itantra.core.AppConstants
import com.example.itantra.data.model.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class TcpServer(private val port: Int = AppConstants.DEFAULT_PORT) {

    private val json = Json { ignoreUnknownKeys = true }
    private val _incoming = MutableSharedFlow<WireMessage>()
    val incoming = _incoming.asSharedFlow()

    private var serverSocket: ServerSocket? = null

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.i(AppConstants.TAG, "Server listening on $port")
                while (isActive) {
                    val client = serverSocket!!.accept()
                    Log.i(AppConstants.TAG, "Client connected: ${client.inetAddress}")
                    launch { handle(client) }
                }
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "server stopped", e)
            }
        }
    }

    private suspend fun handle(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                runCatching {
                    json.decodeFromString(WireMessage.serializer(), line)
                }.onSuccess {
                    Log.i(AppConstants.TAG, "Received: ${it.payload}")
                    _incoming.emit(it)
                }
            }
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "client handle error", e)
        } finally {
            runCatching { client.close() }
        }
    }

    fun stop() { runCatching { serverSocket?.close() } }
}