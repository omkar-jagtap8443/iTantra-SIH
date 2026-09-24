package com.example.itantra.data.prefs

import android.content.Context

object DeviceIdStore {
    private const val PREFS = "itantra_prefs"
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "device_name"

    fun getOrCreateId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_ID, null)
        if (existing != null) return existing
        val fresh = "IT-DEVICE-" + (100..999).random()
        prefs.edit().putString(KEY_ID, fresh).apply()
        return fresh
    }

    fun getName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NAME, "") ?: ""
    }

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, name)
            .apply()
    }
}