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
    private val _incoming = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    val incoming = _incoming.asSharedFlow()

    private val _status = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val status = _status.asSharedFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var readerThread: Thread? = null

    @Volatile
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
                    adapter.listenUsingInsecureRfcommWithServiceRecord("iTantra", AppConstants.BT_UUID)
                }
                _status.emit("Waiting for connection…")
                Log.i(AppConstants.TAG, "BT server listening")

                // Restructured loop — no break/continue inside lambda
                while (isActive && isRunning) {
                    acceptAndHandle()
                }
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "BT server error", e)
                _status.emit("Server error: ${e.message}")
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * Accept one client and start its reader thread.
     * Called from the outer while loop — keeps break/continue out of lambdas.
     */
    private suspend fun acceptAndHandle() {
        val socket: BluetoothSocket = try {
            serverSocket?.accept() ?: return
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(AppConstants.TAG, "accept() failed", e)
            }
            return
        }

        val peerName = try {
            socket.remoteDevice?.name ?: "Unknown"
        } catch (_: SecurityException) {
            "Unknown"
        }

        Log.i(AppConstants.TAG, "BT server accepted: $peerName")
        BluetoothConnection.set(socket, peerName)
        _status.emit("Connected to $peerName")
        startReading(socket)
    }

    private fun startReading(socket: BluetoothSocket) {
        readerThread?.interrupt()
        readerThread = Thread {
            Log.i(AppConstants.TAG, "BT server reader STARTED")
            var reader: BufferedReader? = null
            try {
                reader = BufferedReader(InputStreamReader(socket.inputStream))
                var running = true
                while (running) {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(AppConstants.TAG, "BT server: end of stream")
                        running = false
                    } else if (line.isNotBlank()) {
                        runCatching {
                            json.decodeFromString(WireMessage.serializer(), line)
                        }.onSuccess { msg ->
                            Log.i(AppConstants.TAG, "BT server RECEIVED: ${msg.payload}")
                            _incoming.tryEmit(msg)
                        }.onFailure {
                            Log.e(AppConstants.TAG, "BT server parse failed", it)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(AppConstants.TAG, "BT server read error", e)
            } finally {
                try { reader?.close() } catch (_: Exception) {}
                if (isRunning) {
                    try { socket.close() } catch (_: Exception) {}
                }
                Log.w(AppConstants.TAG, "BT server reader ENDED")
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
        try { BluetoothConnection.socket.value?.close() } catch (_: Exception) {}
        BluetoothConnection.clear()
        serverSocket = null
        readerThread = null
    }
}