package com.example.itantra.core

import java.util.UUID

object AppConstants {
    const val DEFAULT_PORT = 8988
    const val DISCOVERY_PORT = 8989
    const val TAG = "iTantra"
    const val DEVICE_ID_PREFIX = "IT-DEVICE-"

    // Standard Bluetooth Serial Port Profile UUID — most compatible across devices
    val BT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}