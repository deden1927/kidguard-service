package com.kidguard.app.data

enum class ActivityType {
    APP_OPEN,
    YOUTUBE_VIDEO,
    BLOCKED_ATTEMPT,
    WHATSAPP_MESSAGE,
    SYSTEM_EVENT
}

data class ActivityEntry(
    val timestampMillis: Long,
    val packageName: String,
    val appName: String,
    val detail: String,
    val type: ActivityType
)

data class AppUsageSummary(
    val packageName: String,
    val appName: String,
    val totalForegroundMs: Long
)

data class DeviceRealtimeStatus(
    val currentAppName: String,
    val currentPackageName: String,
    val currentYouTubeTitle: String,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkLabel: String,
    val isDeviceLocked: Boolean,
    val blockedKeywords: Set<String>
)

data class TelegramUpdate(
    val updateId: Long,
    val chatId: String,
    val senderName: String,
    val text: String
)

data class TelegramApiResult(
    val ok: Boolean,
    val description: String,
    val botUsername: String = ""
)
