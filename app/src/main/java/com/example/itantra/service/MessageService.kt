package com.example.itantra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.itantra.MainActivity
import com.example.itantra.R
import com.example.itantra.core.AppConstants
import com.example.itantra.core.Language
import com.example.itantra.core.ServiceLocator
import com.example.itantra.data.prefs.ContactsStore
import com.example.itantra.data.prefs.TranslationPrefs
import com.example.itantra.transport.BluetoothConnection
import com.example.itantra.transport.BluetoothServer
import com.example.itantra.transport.LocalTcpTransport
import com.example.itantra.transport.TcpServer
import com.example.itantra.translation.TranslationOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class MessageService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tcpServer: TcpServer? = null
    private var btServer: BluetoothServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(AppConstants.TAG, "MessageService onCreate")
        createChannels()
        startForeground(NOTIF_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(AppConstants.TAG, "MessageService started")

        // Initialize TTS so we can speak incoming messages
        ServiceLocator.ttsManager.init(applicationContext)
        ServiceLocator.voskSttManager // ensure locator initialized

        // Start TCP server
        if (tcpServer == null) {
            tcpServer = TcpServer().also { it.start(scope) }
            scope.launch {
                tcpServer!!.incoming.collect { msg ->
                    handleIncoming(msg.from, msg.senderName, msg.payload, msg.lang, msg.priority)
                }
            }
        }

        // Start Bluetooth server
        if (btServer == null) {
            btServer = BluetoothServer()
            scope.launch {
                btServer!!.incoming.collect { msg ->
                    handleIncoming(msg.from, msg.senderName, msg.payload, msg.lang, msg.priority)
                }
            }
        }
        btServer?.start(applicationContext, scope)

        return START_STICKY  // Restart service if killed by system
    }

    private fun handleIncoming(
        from: String,
        senderName: String,
        payload: String,
        lang: String,
        priority: String
    ) {
        Log.i(AppConstants.TAG, "Service incoming: $payload priority=$priority")

        val resolvedName = if (senderName.isNotBlank()) senderName
        else ContactsStore.load(applicationContext)
            .firstOrNull { it.deviceId == from }?.name ?: from

        val sourceLanguage = Language.fromTag(lang)
        val targetLanguage = TranslationPrefs.getTargetLanguage(applicationContext)

        if (priority == "SOS") {
            val sosSessionId = beginSosSession()
            showSosAlert(resolvedName, from, payload, sourceLanguage.display, sourceLanguage.name, sosSessionId)
            scope.launch {
                if (!isSosSessionActive(sosSessionId)) return@launch
                when (val outcome = ServiceLocator.translationEngine.translate(
                    text = payload,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                )) {
                    is TranslationOutcome.Success -> {
                        if (isSosSessionActive(sosSessionId)) {
                            ServiceLocator.ttsManager.speak(outcome.translatedText, targetLanguage)
                        }
                    }

                    is TranslationOutcome.Failure -> {
                        if (!isSosSessionActive(sosSessionId)) return@launch
                        Log.w(
                            AppConstants.TAG,
                            "Translation failed ${sourceLanguage.name} -> ${targetLanguage.name}: ${outcome.reason}"
                        )
                        if (isSosSessionActive(sosSessionId)) {
                            ServiceLocator.ttsManager.speak(payload, sourceLanguage)
                        }
                    }
                }
            }
            return
        }

        scope.launch {
            val outcome = ServiceLocator.translationEngine.translate(
                text = payload,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )

            val spokenText: String
            val spokenLanguage: Language
            val displayedText: String
            val displayedLanguage: String

            when (outcome) {
                is TranslationOutcome.Success -> {
                    spokenText = outcome.translatedText
                    spokenLanguage = targetLanguage
                    displayedText = outcome.translatedText
                    displayedLanguage = if (sourceLanguage == targetLanguage) {
                        sourceLanguage.display
                    } else {
                        "${sourceLanguage.display} → ${targetLanguage.display}"
                    }
                }

                is TranslationOutcome.Failure -> {
                    Log.w(
                        AppConstants.TAG,
                        "Translation failed ${sourceLanguage.name} -> ${targetLanguage.name}: ${outcome.reason}"
                    )
                    spokenText = payload
                    spokenLanguage = sourceLanguage
                    displayedText = payload
                    displayedLanguage = "${sourceLanguage.display} → ${targetLanguage.display} (translation unavailable)"
                }
            }

            showNormalNotification(resolvedName, displayedText, displayedLanguage)
            ServiceLocator.ttsManager.speak(spokenText, spokenLanguage)
        }
    }

    private fun showSosAlert(
        senderName: String,
        from: String,
        payload: String,
        lang: String,
        speechLang: String,
        sosSessionId: Long,
    ) {
        val fullScreenIntent = Intent(this, SosAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("sender_name", senderName)
            putExtra("sender_id", from)
            putExtra("message", payload)
            putExtra("lang", lang)
            putExtra("speech_lang", speechLang)
            putExtra("sos_session_id", sosSessionId)
        }

        val pi = PendingIntent.getActivity(
            this,
            9001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SOS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 SOS from $senderName")
            .setContentText(payload)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)  // ← shows over other apps
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(SOS_NOTIF_ID, notification)

        // Also try to launch the activity directly (works when screen is on)
        try {
            startActivity(fullScreenIntent)
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "Direct start failed, using notification", e)
        }
    }

    private fun showNormalNotification(senderName: String, payload: String, lang: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            9002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Message from $senderName")
            .setContentText(payload)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NORMAL_NOTIF_ID, notification)
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            9003,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("iTantra is active")
            .setContentText("Listening for messages")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "iTantra Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps iTantra listening in the background"
            }
            nm.createNotificationChannel(serviceChannel)

            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming messages"
                enableVibration(true)
            }
            nm.createNotificationChannel(messagesChannel)

            val sosChannel = NotificationChannel(
                CHANNEL_SOS,
                "SOS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical SOS alerts"
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(sosChannel)
        }
    }

    override fun onDestroy() {
        Log.i(AppConstants.TAG, "MessageService onDestroy")
        try { tcpServer?.stop() } catch (_: Exception) {}
        try { btServer?.stop() } catch (_: Exception) {}
        dismissCurrentSosSession()
        try { ServiceLocator.translationEngine.close() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_SERVICE = "itantra_service"
        const val CHANNEL_MESSAGES = "itantra_messages"
        const val CHANNEL_SOS = "itantra_sos"
        const val NOTIF_ID = 1001
        const val NORMAL_NOTIF_ID = 1002
        const val SOS_NOTIF_ID = 1003

        fun start(context: Context) {
            val intent = Intent(context, MessageService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MessageService::class.java))
        }

        private val sosSessionCounter = AtomicLong(0L)
        private val activeSosSessionId = AtomicLong(0L)
        private val dismissedSosSessionId = AtomicLong(0L)

        fun beginSosSession(): Long {
            val sessionId = sosSessionCounter.incrementAndGet()
            activeSosSessionId.set(sessionId)
            dismissedSosSessionId.set(0L)
            return sessionId
        }

        fun dismissSosSession(sessionId: Long) {
            if (activeSosSessionId.get() == sessionId) {
                dismissedSosSessionId.set(sessionId)
            }
        }

        fun isSosSessionActive(sessionId: Long): Boolean {
            return activeSosSessionId.get() == sessionId && dismissedSosSessionId.get() != sessionId
        }

        fun dismissCurrentSosSession() {
            val sessionId = activeSosSessionId.get()
            if (sessionId != 0L) {
                dismissedSosSessionId.set(sessionId)
            }
            activeSosSessionId.set(0L)
        }
    }
}