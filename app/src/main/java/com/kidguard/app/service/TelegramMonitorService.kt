package com.kidguard.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kidguard.app.MainActivity
import com.kidguard.app.R
import com.kidguard.app.data.AppPrefs
import com.kidguard.app.telegram.BotCommandRouter
import com.kidguard.app.telegram.TelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TelegramMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private lateinit var prefs: AppPrefs
    private lateinit var commandRouter: BotCommandRouter

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        commandRouter = BotCommandRouter(this, prefs, serviceScope)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITOR) {
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()

        val instantAlertText = intent?.getStringExtra(EXTRA_INSTANT_ALERT_TEXT)
        if (!instantAlertText.isNullOrBlank() && prefs.isTelegramConfigured()) {
            serviceScope.launch {
                TelegramClient.sendMessage(
                    botToken = prefs.botToken,
                    chatId = prefs.parentChatId,
                    text = instantAlertText
                )
            }
        }

        ensurePollingLoopRunning()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        pollingJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val channelId = NOTIFICATION_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Layanan Sinkronisasi Sistem",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga proses sinkronisasi sistem tetap berjalan di latar belakang."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Sinkronisasi sistem sedang berjalan")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensurePollingLoopRunning() {
        if (pollingJob?.isActive == true) return
        pollingJob = serviceScope.launch {
            while (isActive) {
                if (!prefs.isTelegramConfigured()) {
                    delay(5_000L)
                    continue
                }

                checkAndNotifyLowBattery()

                val currentOffset = prefs.lastUpdateOffset
                val updates = TelegramClient.getUpdates(
                    botToken = prefs.botToken,
                    offset = currentOffset,
                    timeoutSec = 25
                )

                if (updates.isNotEmpty()) {
                    for (update in updates) {
                        if (update.updateId >= prefs.lastUpdateOffset) {
                            prefs.lastUpdateOffset = update.updateId + 1L
                        }
                        commandRouter.handleUpdate(update)
                    }
                } else {
                    delay(1_500L)
                }
            }
        }
    }

    private suspend fun checkAndNotifyLowBattery() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0) return

        val pct = (level * 100) / scale
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        if (pct < 15 && !isCharging && !prefs.lastLowBatteryAlertCycle) {
            prefs.lastLowBatteryAlertCycle = true
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "🔋 Peringatan Baterai Lemah: Baterai HP anak tersisa $pct% dan sedang tidak dicas."
            )
        } else if (pct >= 20 || isCharging) {
            prefs.lastLowBatteryAlertCycle = false
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "kidguard_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_MONITOR = "com.kidguard.app.ACTION_STOP_MONITOR"
        private const val EXTRA_INSTANT_ALERT_TEXT = "extra_instant_alert_text"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, TelegramMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TelegramMonitorService::class.java).apply {
                action = ACTION_STOP_MONITOR
            }
            context.stopService(intent)
            isRunning = false
        }

        fun enqueueInstantAlert(context: Context, alertText: String) {
            val prefs = AppPrefs(context)
            if (!prefs.isTelegramConfigured()) return
            val intent = Intent(context, TelegramMonitorService::class.java).apply {
                putExtra(EXTRA_INSTANT_ALERT_TEXT, alertText)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }
    }
}
