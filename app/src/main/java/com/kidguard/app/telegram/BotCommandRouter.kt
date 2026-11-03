package com.kidguard.app.telegram

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.kidguard.app.R
import com.kidguard.app.data.AppPrefs
import com.kidguard.app.data.TelegramUpdate
import com.kidguard.app.service.GuardAccessibilityService
import com.kidguard.app.service.WhatsAppMonitorService
import com.kidguard.app.util.LocationHelper
import com.kidguard.app.util.OverlayManager
import com.kidguard.app.util.UsageStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BotCommandRouter(
    private val context: Context,
    private val prefs: AppPrefs,
    private val serviceScope: CoroutineScope
) {

    suspend fun handleUpdate(update: TelegramUpdate) {
        val authorizedChatId = prefs.parentChatId
        // Whitelist check: ignore and notify unauthorized chat IDs without executing any command.
        if (authorizedChatId.isBlank() || update.chatId != authorizedChatId) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = update.chatId,
                text = "Akses ditolak. Chat ID (${update.chatId}) tidak terdaftar sebagai Orang Tua pada perangkat ini."
            )
            return
        }

        val rawText = update.text.trim()
        val parts = rawText.split(Regex("\\s+"), limit = 2)
        val commandToken = parts[0].lowercase(Locale.ROOT).substringBefore("@")
        val argument = if (parts.size > 1) parts[1].trim() else ""

        when (commandToken) {
            "/start", "/menu" -> sendMainMenu()
            "/status" -> handleStatusCommand()
            "/yt", "/riwayat" -> handleHistoryCommand()
            "/screentime" -> handleScreenTimeCommand()
            "/ss" -> handleScreenshotCommand()
            "/lokasi" -> handleLocationCommand()
            "/pesan" -> handleParentMessageCommand(argument)
            "/lock" -> handleLockCommand(argument)
            "/unlock" -> handleUnlockCommand()
            "/block" -> handleBlockCommand(argument)
            "/unblock" -> handleUnblockCommand(argument)
            "/wa", "/whatsapp" -> handleWhatsAppToggleCommand(argument)
            else -> {
                TelegramClient.sendMessage(
                    botToken = prefs.botToken,
                    chatId = authorizedChatId,
                    text = "Perintah tidak dikenali: $commandToken\nKetik /menu untuk menampilkan daftar perintah.",
                    includeReplyKeyboard = true
                )
            }
        }
    }

    private suspend fun sendMainMenu() {
        val menuText = buildString {
            appendLine("KidGuard: Menu Kontrol Orang Tua")
            appendLine()
            appendLine("Perintah Cepat (ketuk tombol di bawah atau ketik langsung):")
            appendLine("/status : Aplikasi aktif, judul YouTube, baterai, dan jaringan")
            appendLine("/yt atau /riwayat : 15 riwayat video YouTube & aplikasi terakhir")
            appendLine("/screentime : Rekap total durasi pemakaian aplikasi hari ini")
            appendLine("/ss : Ambil tangkapan layar perangkat saat ini")
            appendLine("/lokasi : Kirim titik GPS dan tautan Google Maps")
            appendLine("/pesan <teks> : Munculkan kotak peringatan di layar anak")
            appendLine("/lock : Kunci layar perangkat (Waktunya Istirahat / Belajar)")
            appendLine("/unlock : Buka kembali kunci layar perangkat")
            appendLine("/block <nama> : Blokir aplikasi atau game (contoh: /block roblox)")
            appendLine("/unblock <nama> : Hapus blokir aplikasi atau game")
            appendLine("/wa on atau /wa off : Aktifkan atau matikan pemantauan pesan WhatsApp")
        }
        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = menuText.trim(),
            includeReplyKeyboard = true
        )
    }

    private suspend fun handleStatusCommand() {
        val (batteryPct, isCharging) = readBatteryState()
        val networkLabel = readNetworkLabel()
        val currentApp = prefs.currentForegroundApp.ifBlank { "Layar Utama / Tidak Diketahui" }
        val currentPkg = prefs.currentForegroundPackage
        val ytTitle = prefs.currentYouTubeTitle
        val blockedSet = prefs.getBlockedKeywords()
        val accessibilityActive = GuardAccessibilityService.instance != null

        val statusReport = buildString {
            appendLine("Laporan Status Perangkat Saat Ini")
            appendLine("Waktu: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine()
            appendLine("Aplikasi Aktif: $currentApp")
            if (currentPkg.isNotBlank()) {
                appendLine("Paket: $currentPkg")
            }
            if (ytTitle.isNotBlank()) {
                appendLine("Judul YouTube Terakhir/Aktif: $ytTitle")
            }
            appendLine("Baterai: $batteryPct% (${if (isCharging) "Sedang Dicas" else "Tidak Dicas"})")
            appendLine("Jaringan: $networkLabel")
            appendLine("Status Kunci Layar: ${if (prefs.isDeviceLocked) "TERKUNCI (/lock aktif)" else "Terbuka"}")
            appendLine("Layanan Aksesibilitas: ${if (accessibilityActive) "Aktif" else "BELUM AKTIF (aktifkan di HP anak)"}")
            appendLine(
                "Daftar Blokir (${blockedSet.size}): ${
                    if (blockedSet.isEmpty()) "Tidak ada" else blockedSet.joinToString(", ")
                }"
            )
            appendLine(
                "Pemantauan WhatsApp: ${
                    if (prefs.whatsappMonitorEnabled) "AKTIF" else "NONAKTIF"
                }"
            )
        }

        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = statusReport.trim()
        )
    }

    private suspend fun handleHistoryCommand() {
        val entries = prefs.getRecentActivities(limit = 15)
        val formatted = prefs.formatActivityEntries(entries)
        val message = "15 Riwayat Aplikasi & Video YouTube Terakhir:\n\n$formatted"
        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = message
        )
    }

    private suspend fun handleScreenTimeCommand() {
        if (!UsageStatsHelper.hasUsageStatsPermission(context)) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Izin Data Penggunaan (Usage Stats) belum diaktifkan di perangkat anak. Buka aplikasi KidGuard lalu aktifkan Izin Data Penggunaan."
            )
            return
        }

        val summaries = UsageStatsHelper.getTodayUsageSummary(context, maxItems = 15)
        if (summaries.isEmpty()) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Belum ada data durasi layar yang tercatat sejak pukul 00:00 hari ini."
            )
            return
        }

        val totalMs = summaries.sumOf { it.totalForegroundMs }
        val report = buildString {
            appendLine("Rekap Durasi Layar Hari Ini (00:00 sampai sekarang)")
            appendLine("Total Terhitung: ${UsageStatsHelper.formatDuration(totalMs)}")
            appendLine()
            summaries.forEachIndexed { idx, item ->
                appendLine("${idx + 1}. ${item.appName} : ${UsageStatsHelper.formatDuration(item.totalForegroundMs)}")
            }
        }

        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = report.trim()
        )
    }

    private suspend fun handleScreenshotCommand() {
        val accessibilityService = GuardAccessibilityService.instance
        if (accessibilityService == null) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Gagal mengambil tangkapan layar: Layanan Aksesibilitas KidGuard belum aktif di perangkat anak."
            )
            return
        }

        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = "Mengambil tangkapan layar perangkat..."
        )

        accessibilityService.captureScreenToPng { file, errorReason ->
            serviceScope.launch {
                if (file != null && file.exists()) {
                    val caption =
                        "Tangkapan layar pada ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} (${prefs.currentForegroundApp})"
                    val result = TelegramClient.sendPhoto(
                        botToken = prefs.botToken,
                        chatId = prefs.parentChatId,
                        photoFile = file,
                        caption = caption
                    )
                    file.delete()
                    if (!result.ok) {
                        TelegramClient.sendMessage(
                            botToken = prefs.botToken,
                            chatId = prefs.parentChatId,
                            text = "Gagal mengunggah foto tangkapan layar: ${result.description}"
                        )
                    }
                } else {
                    TelegramClient.sendMessage(
                        botToken = prefs.botToken,
                        chatId = prefs.parentChatId,
                        text = "Gagal mengambil tangkapan layar: ${errorReason ?: "Kesalahan tidak diketahui."}"
                    )
                }
            }
        }
    }

    private suspend fun handleLocationCommand() {
        if (!LocationHelper.hasLocationPermission(context)) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Izin Lokasi GPS belum diberikan pada perangkat anak. Buka aplikasi KidGuard lalu ketuk Minta Izin Lokasi GPS."
            )
            return
        }

        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = "Sedang membaca titik koordinat GPS perangkat..."
        )

        val location = LocationHelper.getCurrentLocation(context, timeoutMs = 10_000L)
        if (location == null) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Gagal memperoleh titik lokasi. Pastikan GPS/Lokasi perangkat anak dalam keadaan menyala."
            )
            return
        }

        TelegramClient.sendLocation(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            latitude = location.latitude,
            longitude = location.longitude
        )

        val mapsUrl = LocationHelper.buildGoogleMapsUrl(location.latitude, location.longitude)
        val ageSec = ((System.currentTimeMillis() - location.time).coerceAtLeast(0L)) / 1000L
        val detail = buildString {
            appendLine("Lokasi Perangkat Ditemukan")
            appendLine("Koordinat: ${location.latitude}, ${location.longitude}")
            appendLine("Akurasi: +/- ${location.accuracy.toInt()} meter")
            appendLine("Pembaruan Data: ${ageSec} detik lalu")
            appendLine("Google Maps: $mapsUrl")
        }

        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = detail.trim()
        )
    }

    private suspend fun handleParentMessageCommand(messageBody: String) {
        if (messageBody.isBlank()) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Format perintah: /pesan <teks peringatan>\nContoh: /pesan Waktunya makan malam, letakkan HP sekarang."
            )
            return
        }

        if (!OverlayManager.canShowOverlay(context)) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Tidak dapat menampilkan pop-up pesan karena Layanan Aksesibilitas dan Izin Overlay belum aktif di HP anak."
            )
            return
        }

        OverlayManager.showParentMessage(context, messageBody)
        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = "Pesan peringatan telah ditampilkan di tengah layar perangkat anak disertai bunyi alarm singkat:\n\"$messageBody\""
        )
    }

    private suspend fun handleLockCommand(customReason: String) {
        if (!OverlayManager.canShowOverlay(context)) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Gagal mengunci layar: aktifkan Layanan Aksesibilitas atau Izin Tampil di Atas Aplikasi Lain di HP anak terlebih dahulu."
            )
            return
        }

        val reasonText = customReason.ifBlank {
            context.getString(R.string.overlay_lock_default_message)
        }
        prefs.isDeviceLocked = true
        prefs.lockMessage = reasonText

        OverlayManager.showLockScreen(
            context = context,
            title = context.getString(R.string.overlay_lock_default_title),
            reason = reasonText,
            isBlockedAppMode = false
        )

        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = "Layar perangkat anak berhasil dikunci dengan tampilan: \"Waktunya Istirahat / Belajar\". Ketik /unlock untuk membuka kembali."
        )
    }

    private suspend fun handleUnlockCommand() {
        prefs.isDeviceLocked = false
        OverlayManager.hideLockScreen()
        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = "Kunci layar perangkat anak telah dibuka kembali."
        )
    }

    private suspend fun handleBlockCommand(targetKeyword: String) {
        if (targetKeyword.isBlank()) {
            val currentList = prefs.getBlockedKeywords()
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Format perintah: /block <nama aplikasi>\nContoh: /block roblox\nDaftar blokir saat ini: ${
                    if (currentList.isEmpty()) "Kosong" else currentList.joinToString(", ")
                }"
            )
            return
        }

        val updated = prefs.addBlockedKeyword(targetKeyword)
        GuardAccessibilityService.instance?.recheckCurrentForegroundApp()

        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = "Kata kunci aplikasi \"${targetKeyword.lowercase(Locale.ROOT)}\" ditambahkan ke daftar blokir.\nDaftar blokir aktif: ${
                updated.joinToString(", ")
            }"
        )
    }

    private suspend fun handleUnblockCommand(targetKeyword: String) {
        if (targetKeyword.isBlank()) {
            TelegramClient.sendMessage(
                botToken = prefs.botToken,
                chatId = prefs.parentChatId,
                text = "Format perintah: /unblock <nama aplikasi>\nContoh: /unblock roblox"
            )
            return
        }

        val updated = prefs.removeBlockedKeyword(targetKeyword)
        if (!prefs.isDeviceLocked) {
            OverlayManager.hideLockScreen()
        }

        TelegramClient.sendMessage(
            botToken = prefs.botToken,
            chatId = prefs.parentChatId,
            text = "Blokir untuk \"${targetKeyword.lowercase(Locale.ROOT)}\" telah dihapus.\nDaftar blokir tersisa: ${
                if (updated.isEmpty()) "Kosong" else updated.joinToString(", ")
            }"
        )
    }

    private suspend fun handleWhatsAppToggleCommand(argument: String) {
        val mode = argument.trim().lowercase(Locale.ROOT)
        when (mode) {
            "on", "aktif", "nyala" -> {
                prefs.whatsappMonitorEnabled = true
                val accessState = if (WhatsAppMonitorService.isNotificationAccessEnabled(context)) {
                    "sudah aktif"
                } else {
                    "belum aktif, buka aplikasi KidGuard lalu aktifkan Akses Notifikasi"
                }
                TelegramClient.sendMessage(
                    botToken = prefs.botToken,
                    chatId = prefs.parentChatId,
                    text = "Pemantauan pesan WhatsApp dinyalakan. Status akses notifikasi: $accessState."
                )
            }

            "off", "mati", "nonaktif" -> {
                prefs.whatsappMonitorEnabled = false
                TelegramClient.sendMessage(
                    botToken = prefs.botToken,
                    chatId = prefs.parentChatId,
                    text = "Pemantauan pesan WhatsApp dimatikan. Ketik /wa on untuk menyalakannya kembali."
                )
            }

            else -> {
                val monitorState = if (prefs.whatsappMonitorEnabled) "AKTIF" else "NONAKTIF"
                val accessState = if (WhatsAppMonitorService.isNotificationAccessEnabled(context)) {
                    "AKTIF"
                } else {
                    "BELUM AKTIF"
                }
                TelegramClient.sendMessage(
                    botToken = prefs.botToken,
                    chatId = prefs.parentChatId,
                    text = "Status Pemantauan WhatsApp\nPemantauan: $monitorState\nAkses Notifikasi: $accessState\nGunakan /wa on atau /wa off untuk mengubah."
                )
            }
        }
    }

    private fun readBatteryState(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        return Pair(pct, isCharging)
    }

    private fun readNetworkLabel(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "Tidak Diketahui"
        val network = cm.activeNetwork ?: return "Tidak Terhubung"
        val caps = cm.getNetworkCapabilities(network) ?: return "Tidak Terhubung"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data Seluler"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Terhubung"
        }
    }
}
