package com.example.itantra.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.itantra.core.AppConstants

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(AppConstants.TAG, "Boot completed — starting MessageService")
            MessageService.start(context)
        }
    }
}