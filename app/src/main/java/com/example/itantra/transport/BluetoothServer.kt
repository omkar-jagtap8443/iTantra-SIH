package com.example.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
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

class BluetoothServer {

    private val json = Json { ignoreUnknownKeys = true }
    private val _incoming = MutableSharedFlow<WireMessage>()
    val incoming = _incoming.asSharedFlow()

    private val _status = MutableSharedFlow<String>()
    val status = _status.asSharedFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var readerThread: Thread? = null
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun start(context: Context, scope: CoroutineScope) {
        if (isRunning) {
            scope.launch { _status.emit("Already listening") }
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            scope.launch { _status.emit("Bluetooth permission not granted") }
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            scope.launch { _status.emit("Bluetooth not supported") }
            return
        }
        if (!adapter.isEnabled) {
            scope.launch { _status.emit("Turn on Bluetooth first") }
            return
        }

        isRunning = true
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = try {
                    adapter.listenUsingRfcommWithServiceRecord("iTantra", AppConstants.BT_UUID)
                } catch (e: Exception) {
                    Log.w(AppConstants.TAG, "Secure listen failed, trying insecure", e)
                    adapter.listenUsingInsecureRfcommWithServiceRecord(
                        "iTantra",
                        AppConstants.BT_UUID
                    )
                }
                _status.emit("Waiting for Bluetooth connection…")
                Log.i(AppConstants.TAG, "BT server listening on UUID ${AppConstants.BT_UUID}")

                while (isActive && isRunning) {
                    val socket: BluetoothSocket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        Log.e(AppConstants.TAG, "accept() failed: ${e.message}")
                        if (!isRunning) break
                        continue
                    }

                    val peerName = try {
                        socket.remoteDevice?.name ?: "Unknown"
                    } catch (_: SecurityException) { "Unknown" }

                    Log.i(AppConstants.TAG, "BT client accepted: $peerName, isConnected=${socket.isConnected}")
                    BluetoothConnection.set(socket, peerName)
                    _status.emit("Connected to $peerName")

                    startReading(socket)
                }
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "BT server error", e)
                _status.emit("BT server stopped: ${e.message}")
            } finally {
                isRunning = false
            }
        }
    }

    private fun startReading(socket: BluetoothSocket) {
        readerThread = Thread {
            Log.i(AppConstants.TAG, "BT reader thread started")
            var reader: BufferedReader? = null
            try {
                reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (true) {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(AppConstants.TAG, "BT reader: end of stream")
                        break
                    }
                    if (line.isBlank()) continue

                    Log.i(AppConstants.TAG, "BT raw line: $line")

                    runCatching {
                        json.decodeFromString(WireMessage.serializer(), line)
                    }.onSuccess { msg ->
                        Log.i(AppConstants.TAG, "BT received: ${msg.payload}")
                        _incoming.tryEmit(msg)
                    }.onFailure {
                        Log.e(AppConstants.TAG, "BT parse failed", it)
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "BT read error: ${e.message}", e)
            } finally {
                try { reader?.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
                BluetoothConnection.clear()
                Log.w(AppConstants.TAG, "BT reader thread ended, socket closed")
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { readerThread?.interrupt() } catch (_: Exception) {}
        BluetoothConnection.clear()
        serverSocket = null
        readerThread = null
    }
}