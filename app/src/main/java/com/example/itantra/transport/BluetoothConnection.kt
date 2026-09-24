package com.example.itantra.transport

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the single active Bluetooth socket.
 * Both server-accepted and client-connected sockets use this same holder.
 */
object BluetoothConnection {

    private val _socket = MutableStateFlow<BluetoothSocket?>(null)
    val socket: StateFlow<BluetoothSocket?> = _socket

    private val _connectedPeer = MutableStateFlow<String?>(null)
    val connectedPeer: StateFlow<String?> = _connectedPeer

    fun set(socket: BluetoothSocket?, peerName: String?) {
        _socket.value = socket
        _connectedPeer.value = peerName
    }

    fun clear() {
        try { _socket.value?.close() } catch (_: Exception) {}
        _socket.value = null
        _connectedPeer.value = null
    }

    fun isConnected(): Boolean = _socket.value?.isConnected == true
}