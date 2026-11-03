package com.kidguard.app.service

import android.app.Notification
import android.content.ComponentName
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kidguard.app.data.ActivityEntry
import com.kidguard.app.data.ActivityType
import com.kidguard.app.data.AppPrefs
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Memantau notifikasi masuk dari WhatsApp dan meneruskan ringkasan
 * (nama pengirim atau grup + cuplikan pesan) ke Telegram orang tua.
 *
 * Catatan jangkauan: hanya konten yang tampil pada notifikasi WhatsApp
 * (pengirim dan cuplikan pesan) yang dapat dibaca tanpa akses root.
 */
class WhatsAppMonitorService : NotificationListenerService() {

    private lateinit var prefs: AppPrefs
    private val lastAlertTimes = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (prefs.isTelegramConfigured()) {
            TelegramMonitorService.enqueueInstantAlert(
                this,
                "Info Sistem: Pemantauan notifikasi WhatsApp telah aktif. Pesan WhatsApp baru akan diteruskan ke chat ini."
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (!prefs.whatsappMonitorEnabled) return
        if (!isWhatsAppPackage(posted.packageName)) return
        if (posted.isOngoing) return

        val notification = posted.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val category = notification.category
        if (category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_PROGRESS ||
            category == Notification.CATEGORY_SERVICE ||
            category == Notification.CATEGORY_SYSTEM
        ) {
            return
        }

        val extras = notification.extras ?: return
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        val messageText = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim().orEmpty()
        if (conversationTitle.isEmpty() || messageText.isEmpty()) return
        if (isSystemNoise(conversationTitle, messageText)) return

        // Pada pesan grup, tickerText biasanya memuat "Nama Pengirim: isi pesan".
        val ticker = notification.tickerText?.toString()?.trim().orEmpty()
        val sender = if (ticker.contains(":")) {
            ticker.substringBefore(":").trim()
        } else {
            conversationTitle
        }
        val fromLabel = if (sender.isNotEmpty() && sender != conversationTitle) {
            "$sender (grup: $conversationTitle)"
        } else {
            conversationTitle
        }

        val now = System.currentTimeMillis()
        val cooldownKey = "$conversationTitle|$sender"
        val lastAlert = lastAlertTimes[cooldownKey] ?: 0L
        if (now - lastAlert < CONVERSATION_COOLDOWN_MS) return
        lastAlertTimes[cooldownKey] = now

        prefs.appendActivity(
            ActivityEntry(
                timestampMillis = now,
                packageName = posted.packageName,
                appName = "WhatsApp",
                detail = "$fromLabel: $messageText",
                type = ActivityType.WHATSAPP_MESSAGE
            )
        )

        TelegramMonitorService.enqueueInstantAlert(
            this,
            "Pesan WhatsApp Baru\nDari: $fromLabel\nPesan: \"$messageText\""
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Notifikasi yang dihapus tidak diteruskan; tidak ada aksi yang diperlukan.
    }

    override fun onDestroy() {
        lastAlertTimes.clear()
        super.onDestroy()
    }

    private fun isWhatsAppPackage(pkg: String?): Boolean {
        return pkg == WHATSAPP_PACKAGE || pkg == WHATSAPP_BUSINESS_PACKAGE
    }

    private fun isSystemNoise(title: String, text: String): Boolean {
        val combined = "$title $text".lowercase(Locale.ROOT)
        return NOISE_MARKERS.any { combined.contains(it) }
    }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val CONVERSATION_COOLDOWN_MS = 20_000L

        private val NOISE_MARKERS = listOf(
            "memeriksa pesan baru",
            "checking for new messages",
            "memulihkan pesan",
            "restoring media",
            "menyiapkan cadangan",
            "preparing backup"
        )

        fun isNotificationAccessEnabled(context: android.content.Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val component = ComponentName(context, WhatsAppMonitorService::class.java)
            return enabledListeners.contains(component.flattenToString()) ||
                enabledListeners.contains(component.flattenToShortString())
        }
    }
}
