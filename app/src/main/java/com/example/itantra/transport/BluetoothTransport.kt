package com.example.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.itantra.core.AppConstants
import com.example.itantra.data.model.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

class BluetoothTransport {

    private val json = Json { ignoreUnknownKeys = true }

    private val _incoming = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    val incoming = _incoming.asSharedFlow()

    private var readerThread: Thread? = null

    @Volatile
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun listPairedDevices(context: Context): List<BluetoothDevice> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return emptyList()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, device: BluetoothDevice): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: return@withContext Result.failure(Exception("No Bluetooth adapter"))

                adapter.cancelDiscovery()
                Thread.sleep(300)

                val socket: BluetoothSocket = try {
                    device.createRfcommSocketToServiceRecord(AppConstants.BT_UUID)
                } catch (e: Exception) {
                    Log.w(AppConstants.TAG, "Secure socket failed, trying insecure", e)
                    device.createInsecureRfcommSocketToServiceRecord(AppConstants.BT_UUID)
                }

                Log.i(AppConstants.TAG, "BT client: connecting to ${device.address}")
                socket.connect()
                Log.i(AppConstants.TAG, "BT client: connected, isConnected=${socket.isConnected}")

                val peerName = try {
                    device.name ?: "Unknown"
                } catch (_: SecurityException) { "Unknown" }

                BluetoothConnection.set(socket, peerName)
                startReader(socket)

                Result.success(peerName)
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "BT client connect failed", e)
                Result.failure(e)
            }
        }

    /**
     * Client-side reader thread. Reads incoming messages from the server.
     * Note: no `break` or `continue` — uses a running flag instead.
     */
    private fun startReader(socket: BluetoothSocket) {
        readerThread?.interrupt()
        isRunning = true
        readerThread = Thread {
            Log.i(AppConstants.TAG, "BT client reader STARTED")
            var reader: BufferedReader? = null
            try {
                reader = BufferedReader(InputStreamReader(socket.inputStream))
                var running = true
                while (running) {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(AppConstants.TAG, "BT client: end of stream")
                        running = false
                    } else if (line.isNotBlank()) {
                        runCatching {
                            json.decodeFromString(WireMessage.serializer(), line)
                        }.onSuccess { msg ->
                            Log.i(AppConstants.TAG, "BT client RECEIVED: ${msg.payload}")
                            _incoming.tryEmit(msg)
                        }.onFailure {
                            Log.e(AppConstants.TAG, "BT client parse failed", it)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(AppConstants.TAG, "BT client read error", e)
            } finally {
                try { reader?.close() } catch (_: Exception) {}
                if (isRunning) {
                    try { socket.close() } catch (_: Exception) {}
                }
                Log.w(AppConstants.TAG, "BT client reader ENDED")
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    suspend fun send(message: WireMessage): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val socket = BluetoothConnection.socket.value
                if (socket == null || !socket.isConnected) {
                    Log.w(AppConstants.TAG, "BT send: socket not ready")
                    return@withContext false
                }
                val writer = PrintWriter(OutputStreamWriter(socket.outputStream), true)
                val line = json.encodeToString(WireMessage.serializer(), message)
                writer.println(line)
                writer.flush()
                Log.i(AppConstants.TAG, "BT client SENT: ${message.payload}")
                true
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "BT client send failed", e)
                false
            }
        }

    fun disconnect() {
        isRunning = false
        try { readerThread?.interrupt() } catch (_: Exception) {}
        try { BluetoothConnection.socket.value?.close() } catch (_: Exception) {}
        BluetoothConnection.clear()
        readerThread = null
    }

    fun isConnected(): Boolean = BluetoothConnection.isConnected()
}