package com.kidguard.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var botToken: String
        get() = prefs.getString(KEY_BOT_TOKEN, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_BOT_TOKEN, value.trim()).apply()

    var parentChatId: String
        get() = prefs.getString(KEY_PARENT_CHAT_ID, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_PARENT_CHAT_ID, value.trim()).apply()

    var parentPin: String
        get() = prefs.getString(KEY_PARENT_PIN, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_PARENT_PIN, value.trim()).apply()

    var isDeviceLocked: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVICE_LOCKED, value).apply()

    var lockMessage: String
        get() = prefs.getString(
            KEY_LOCK_MESSAGE,
            "Layar perangkat ini sedang dikunci oleh Orang Tua. Waktunya istirahat atau belajar."
        ).orEmpty()
        set(value) = prefs.edit().putString(KEY_LOCK_MESSAGE, value).apply()

    var lastUpdateOffset: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_OFFSET, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_OFFSET, value).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_NO)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    var currentForegroundApp: String
        get() = prefs.getString(KEY_CURRENT_APP_NAME, "Layar Utama / Idle").orEmpty()
        set(value) = prefs.edit().putString(KEY_CURRENT_APP_NAME, value).apply()

    var currentForegroundPackage: String
        get() = prefs.getString(KEY_CURRENT_PACKAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CURRENT_PACKAGE, value).apply()

    var currentYouTubeTitle: String
        get() = prefs.getString(KEY_CURRENT_YT_TITLE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CURRENT_YT_TITLE, value).apply()

    var lastLowBatteryAlertCycle: Boolean
        get() = prefs.getBoolean(KEY_LOW_BATTERY_ALERT_SENT, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_BATTERY_ALERT_SENT, value).apply()

    var whatsappMonitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_WHATSAPP_MONITOR_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WHATSAPP_MONITOR_ENABLED, value).apply()

    fun isTelegramConfigured(): Boolean {
        return botToken.isNotBlank() && parentChatId.isNotBlank()
    }

    fun isPinConfigured(): Boolean {
        return parentPin.length in 4..8
    }

    fun verifyPin(candidate: String): Boolean {
        val saved = parentPin
        return saved.isNotBlank() && saved == candidate.trim()
    }

    fun getBlockedKeywords(): Set<String> {
        val raw = prefs.getStringSet(KEY_BLOCKED_KEYWORDS, emptySet()).orEmpty()
        return raw.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSortedSet()
    }

    fun addBlockedKeyword(keyword: String): Set<String> {
        val normalized = keyword.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return getBlockedKeywords()
        val updated = getBlockedKeywords().toMutableSet()
        updated.add(normalized)
        prefs.edit().putStringSet(KEY_BLOCKED_KEYWORDS, updated).apply()
        return updated
    }

    fun removeBlockedKeyword(keyword: String): Set<String> {
        val normalized = keyword.trim().lowercase(Locale.ROOT)
        val updated = getBlockedKeywords().toMutableSet()
        updated.removeAll { it == normalized || it.contains(normalized) }
        prefs.edit().putStringSet(KEY_BLOCKED_KEYWORDS, updated).apply()
        return updated
    }

    fun matchesBlockedKeyword(packageName: String, appName: String): String? {
        val pkgLower = packageName.lowercase(Locale.ROOT)
        val labelLower = appName.lowercase(Locale.ROOT)
        for (rule in getBlockedKeywords()) {
            if (pkgLower.contains(rule) || labelLower.contains(rule)) {
                return rule
            }
        }
        return null
    }

    // Ring buffer capped at MAX_LOG_ENTRIES so SharedPreferences never grows unbounded.
    @Synchronized
    fun appendActivity(entry: ActivityEntry) {
        val currentList = getRecentActivities(MAX_LOG_ENTRIES).toMutableList()
        currentList.add(0, entry)
        val trimmed = currentList.take(MAX_LOG_ENTRIES)
        val array = JSONArray()
        for (item in trimmed) {
            val obj = JSONObject()
                .put("ts", item.timestampMillis)
                .put("pkg", item.packageName)
                .put("app", item.appName)
                .put("detail", item.detail)
                .put("type", item.type.name)
            array.put(obj)
        }
        prefs.edit().putString(KEY_ACTIVITY_LOG_JSON, array.toString()).apply()
    }

    @Synchronized
    fun getRecentActivities(limit: Int = 15): List<ActivityEntry> {
        val raw = prefs.getString(KEY_ACTIVITY_LOG_JSON, null) ?: return emptyList()
        val array = JSONArray(raw)
        val list = ArrayList<ActivityEntry>( minOf(array.length(), limit) )
        val count = minOf(array.length(), limit)
        for (i in 0 until count) {
            val obj = array.optJSONObject(i) ?: continue
            val typeStr = obj.optString("type", ActivityType.APP_OPEN.name)
            val activityType = try {
                ActivityType.valueOf(typeStr)
            } catch (_: IllegalArgumentException) {
                ActivityType.APP_OPEN
            }
            list.add(
                ActivityEntry(
                    timestampMillis = obj.optLong("ts", 0L),
                    packageName = obj.optString("pkg", ""),
                    appName = obj.optString("app", ""),
                    detail = obj.optString("detail", ""),
                    type = activityType
                )
            )
        }
        return list
    }

    @Synchronized
    fun clearActivityHistory() {
        prefs.edit().remove(KEY_ACTIVITY_LOG_JSON).apply()
    }

    fun formatActivityEntries(entries: List<ActivityEntry>): String {
        if (entries.isEmpty()) {
            return "Belum ada riwayat aktivitas aplikasi atau YouTube yang tercatat hari ini."
        }
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        entries.forEachIndexed { index, item ->
            val timeStr = timeFormatter.format(Date(item.timestampMillis))
            val tag = when (item.type) {
                ActivityType.YOUTUBE_VIDEO -> "[YouTube]"
                ActivityType.BLOCKED_ATTEMPT -> "[Diblokir]"
                ActivityType.WHATSAPP_MESSAGE -> "[WhatsApp]"
                ActivityType.SYSTEM_EVENT -> "[Sistem]"
                ActivityType.APP_OPEN -> "[Aplikasi]"
            }
            sb.append("${index + 1}. [$timeStr] $tag ${item.appName}")
            if (item.detail.isNotBlank()) {
                sb.append(" : ${item.detail}")
            }
            if (index < entries.lastIndex) {
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    companion object {
        private const val PREFS_NAME = "kidguard_secure_prefs"
        private const val MAX_LOG_ENTRIES = 100

        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_PARENT_CHAT_ID = "parent_chat_id"
        private const val KEY_PARENT_PIN = "parent_pin"
        private const val KEY_DEVICE_LOCKED = "device_locked"
        private const val KEY_LOCK_MESSAGE = "lock_message"
        private const val KEY_LAST_UPDATE_OFFSET = "last_update_offset"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_CURRENT_APP_NAME = "current_app_name"
        private const val KEY_CURRENT_PACKAGE = "current_package"
        private const val KEY_CURRENT_YT_TITLE = "current_yt_title"
        private const val KEY_LOW_BATTERY_ALERT_SENT = "low_battery_alert_sent"
        private const val KEY_WHATSAPP_MONITOR_ENABLED = "whatsapp_monitor_enabled"
        private const val KEY_BLOCKED_KEYWORDS = "blocked_keywords"
        private const val KEY_ACTIVITY_LOG_JSON = "activity_log_json"
    }
}
