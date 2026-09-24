package com.example.itantra.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.itantra.core.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

data class DiscoveredDevice(
    val deviceId: String,
    val displayName: String,
    val ipAddress: String,
    val lastSeen: Long = System.currentTimeMillis()
)

class DiscoveryService(private val context: Context) {

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices

    private var listenSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(scope: CoroutineScope, myDeviceId: String, myName: String) {
        acquireMulticastLock()
        startListener(scope)
        startBroadcaster(scope, myDeviceId, myName)
        startPruner(scope)
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("itantra-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "multicast lock failed", e)
        }
    }

    private fun startListener(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(AppConstants.DISCOVERY_PORT))
                }
                listenSocket = socket
                val buffer = ByteArray(2048)
                Log.i(AppConstants.TAG, "Discovery listening on ${AppConstants.DISCOVERY_PORT}")

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    handleAnnouncement(text, packet.address.hostAddress ?: "")
                }
            } catch (e: Exception) {
                Log.d(AppConstants.TAG, "discovery listener stopped: ${e.message}")
            }
        }
    }

    private fun handleAnnouncement(json: String, senderIp: String) {
        try {
            val obj = JSONObject(json)
            if (obj.optString("type") != "announce") return
            val deviceId = obj.getString("deviceId")
            val name = obj.optString("name", deviceId)

            val now = System.currentTimeMillis()
            val existing = _devices.value.toMutableList()
            val idx = existing.indexOfFirst { it.deviceId == deviceId }
            val entry = DiscoveredDevice(deviceId, name, senderIp, now)

            if (idx >= 0) existing[idx] = entry else existing.add(entry)
            _devices.value = existing
        } catch (e: Exception) {
            Log.d(AppConstants.TAG, "bad announcement")
        }
    }

    private fun startBroadcaster(scope: CoroutineScope, myDeviceId: String, myName: String) {
        scope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("type", "announce")
                put("deviceId", myDeviceId)
                put("name", myName)
                put("port", AppConstants.DEFAULT_PORT)
            }.toString().toByteArray()

            val address = InetAddress.getByName("255.255.255.255")

            while (isActive) {
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        socket.send(
                            DatagramPacket(
                                payload, payload.size,
                                address, AppConstants.DISCOVERY_PORT
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Wi-Fi likely off — harmless. Log at debug level so it doesn't spam.
                    Log.d(AppConstants.TAG, "Wi-Fi discovery skipped (network unreachable)")
                }
                delay(3000)
            }
        }
    }

    private fun startPruner(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val cutoff = System.currentTimeMillis() - 10_000
                _devices.value = _devices.value.filter { it.lastSeen > cutoff }
                delay(3000)
            }
        }
    }

    fun stop() {
        try {
            listenSocket?.close()
        } catch (_: Exception) {}
        listenSocket = null
        try {
            multicastLock?.release()
        } catch (_: Exception) {}
    }
}