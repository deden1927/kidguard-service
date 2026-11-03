package com.kidguard.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kidguard.app.R
import com.kidguard.app.data.ActivityEntry
import com.kidguard.app.data.ActivityType
import com.kidguard.app.data.AppPrefs
import com.kidguard.app.util.OverlayManager
import com.kidguard.app.util.UsageStatsHelper
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class GuardAccessibilityService : AccessibilityService() {

    private lateinit var prefs: AppPrefs
    private val lastAppAlertTime = ConcurrentHashMap<String, Long>()
    private val lastBlockedAlertTime = ConcurrentHashMap<String, Long>()
    private var lastDetectedYouTubeTitle: String = ""
    private var cachedLauncherPackages: Set<String> = emptySet()
    private var lastLauncherCacheTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshLauncherPackages()
        if (prefs.isDeviceLocked) {
            OverlayManager.showLockScreen(
                context = this,
                title = getString(R.string.overlay_lock_default_title),
                reason = prefs.lockMessage,
                isBlockedAppMode = false
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty() || isIgnoredSystemPackage(pkg)) return

        if (isEmergencyOrDialerPackage(pkg)) {
            return
        }

        val appLabel = UsageStatsHelper.resolveAppLabel(packageManager, pkg)

        if (prefs.isDeviceLocked && pkg != packageName) {
            OverlayManager.showLockScreen(
                context = this,
                title = getString(R.string.overlay_lock_default_title),
                reason = prefs.lockMessage,
                isBlockedAppMode = false
            )
            return
        }

        if (pkg != packageName) {
            val matchedRule = prefs.matchesBlockedKeyword(pkg, appLabel)
            if (matchedRule != null) {
                enforceAppBlock(pkg, appLabel, matchedRule)
                return
            } else if (!prefs.isDeviceLocked) {
                OverlayManager.hideLockScreen()
            }
        }

        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) {
            prefs.currentForegroundPackage = pkg
            prefs.currentForegroundApp = appLabel
            recordAndNotifyAppOpenIfNeeded(pkg, appLabel)
        }

        if (isYouTubePackage(pkg)) {
            extractAndRecordYouTubeTitle(pkg, appLabel)
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun performHomeAction() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun recheckCurrentForegroundApp() {
        val pkg = prefs.currentForegroundPackage
        val appLabel = prefs.currentForegroundApp
        if (pkg.isBlank() || pkg == packageName) return
        val matched = prefs.matchesBlockedKeyword(pkg, appLabel)
        if (matched != null) {
            enforceAppBlock(pkg, appLabel, matched)
        }
    }

    fun captureScreenToPng(onResult: (File?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(
                null,
                "Fitur tangkapan layar otomatis membutuhkan Android 11 (API 30) atau lebih baru."
            )
            return
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (hwBitmap == null) {
                                onResult(null, "Gagal membaca buffer gambar dari layar.")
                                return
                            }
                            val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()

                            val outFile = File(cacheDir, "kidguard_ss_${System.currentTimeMillis()}.png")
                            FileOutputStream(outFile).use { out ->
                                softBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                                out.flush()
                            }
                            softBitmap.recycle()
                            onResult(outFile, null)
                        } catch (e: Exception) {
                            onResult(null, e.localizedMessage ?: "Gagal menyimpan tangkapan layar.")
                        } finally {
                            hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        onResult(
                            null,
                            "Sistem Android menolak tangkapan layar (kode: $errorCode). Jika layar sedang menampilkan konten terproteksi DRM atau jeda terlalu rapat, coba beberapa detik lagi."
                        )
                    }
                }
            )
        } catch (e: Exception) {
            onResult(null, e.localizedMessage ?: "Gagal memanggil fungsi tangkapan layar.")
        }
    }

    private fun enforceAppBlock(pkg: String, appLabel: String, matchedRule: String) {
        OverlayManager.showLockScreen(
            context = this,
            title = "Aplikasi Diblokir Orang Tua",
            reason = "Aplikasi $appLabel ($matchedRule) masuk dalam daftar blokir Orang Tua dan tidak dapat dibuka saat ini.",
            isBlockedAppMode = true
        )

        val now = System.currentTimeMillis()
        val lastAlert = lastBlockedAlertTime[pkg] ?: 0L
        if (now - lastAlert >= BLOCK_ALERT_COOLDOWN_MS) {
            lastBlockedAlertTime[pkg] = now
            prefs.appendActivity(
                ActivityEntry(
                    timestampMillis = now,
                    packageName = pkg,
                    appName = appLabel,
                    detail = "Mencoba membuka aplikasi yang diblokir ($matchedRule)",
                    type = ActivityType.BLOCKED_ATTEMPT
                )
            )
            TelegramMonitorService.enqueueInstantAlert(
                this,
                "🚫 Peringatan Blokir: Anak mencoba membuka aplikasi \"$appLabel\" yang diblokir (kata kunci: $matchedRule). Layar aplikasi telah ditutup otomatis."
            )
        }
    }

    private fun recordAndNotifyAppOpenIfNeeded(pkg: String, appLabel: String) {
        if (isLauncherPackage(pkg)) return
        val now = System.currentTimeMillis()
        val lastTime = lastAppAlertTime[pkg] ?: 0L
        // Cooldown prevents duplicate notifications when an app switches internal activities.
        if (now - lastTime < APP_OPEN_COOLDOWN_MS) return

        lastAppAlertTime[pkg] = now
        prefs.appendActivity(
            ActivityEntry(
                timestampMillis = now,
                packageName = pkg,
                appName = appLabel,
                detail = "Membuka aplikasi",
                type = ActivityType.APP_OPEN
            )
        )
        TelegramMonitorService.enqueueInstantAlert(
            this,
            "🎮 Aktivitas Baru: Anak membuka aplikasi \"$appLabel\"."
        )
    }

    private fun extractAndRecordYouTubeTitle(pkg: String, appLabel: String) {
        val rootNode = rootInActiveWindow ?: return
        val candidateTitle = findYouTubeTitleInTree(rootNode)
        if (candidateTitle.isNullOrBlank()) return

        val cleaned = candidateTitle.trim()
        if (cleaned.length < 4 || cleaned == lastDetectedYouTubeTitle) return

        lastDetectedYouTubeTitle = cleaned
        prefs.currentYouTubeTitle = cleaned
        val now = System.currentTimeMillis()
        prefs.appendActivity(
            ActivityEntry(
                timestampMillis = now,
                packageName = pkg,
                appName = appLabel,
                detail = cleaned,
                type = ActivityType.YOUTUBE_VIDEO
            )
        )
        TelegramMonitorService.enqueueInstantAlert(
            this,
            "🎬 Tontonan YouTube Baru ($appLabel):\n\"$cleaned\""
        )
    }

    private fun findYouTubeTitleInTree(root: AccessibilityNodeInfo): String? {
        val knownTitleViewIds = listOf(
            "com.google.android.youtube:id/player_video_title_view",
            "com.google.android.youtube:id/title",
            "com.google.android.youtube:id/reel_player_title",
            "com.google.android.apps.youtube.kids:id/video_title",
            "com.google.android.apps.youtube.kids:id/title"
        )

        for (viewId in knownTitleViewIds) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            }
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val text = node.text?.toString()?.trim().orEmpty()
                    if (isValidVideoTitleCandidate(text)) {
                        return text
                    }
                }
            }
        }

        return scanNodeFallbackForTitle(root, depth = 0)
    }

    private fun scanNodeFallbackForTitle(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 12) return null
        val viewId = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
        val text = node.text?.toString()?.trim().orEmpty()

        if ((viewId.contains("title") || viewId.contains(" video_title")) && isValidVideoTitleCandidate(text)) {
            return text
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val found = scanNodeFallbackForTitle(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun isValidVideoTitleCandidate(text: String): Boolean {
        if (text.length !in 5..180) return false
        val lower = text.lowercase(Locale.ROOT)
        val ignoredExact = setOf(
            "youtube",
            "youtube kids",
            "beranda",
            "home",
            "shorts",
            "subscription",
            "subscribes",
            "subscribe",
            "koleksi",
            "komentar",
            "bagikan",
            "download",
            "remix",
            "suka",
            "tidak suka",
            "berikutnya"
        )
        if (lower in ignoredExact) return false
        if (lower.matches(Regex("^[0-9:\\s/.-]+$"))) return false
        if (lower.endsWith("rb x ditonton") || lower.endsWith("jt x ditonton") || lower.endsWith("views")) {
            return false
        }
        return true
    }

    private fun isYouTubePackage(pkg: String): Boolean {
        return pkg == "com.google.android.youtube" ||
            pkg == "com.google.android.apps.youtube.kids"
    }

    private fun isIgnoredSystemPackage(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg == "android"
    }

    private fun isEmergencyOrDialerPackage(pkg: String): Boolean {
        return pkg.contains("dialer") ||
            pkg.contains("incallui") ||
            pkg == "com.android.phone" ||
            pkg == "com.android.server.telecom"
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (cachedLauncherPackages.isEmpty() || now - lastLauncherCacheTime > 60_000L) {
            refreshLauncherPackages()
        }
        return pkg in cachedLauncherPackages
    }

    private fun refreshLauncherPackages() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolves = try {
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            emptyList()
        }
        cachedLauncherPackages = resolves.mapNotNull { it.activityInfo?.packageName }.toSet()
        lastLauncherCacheTime = System.currentTimeMillis()
    }

    companion object {
        private const val APP_OPEN_COOLDOWN_MS = 60_000L
        private const val BLOCK_ALERT_COOLDOWN_MS = 30_000L

        @Volatile
        var instance: GuardAccessibilityService? = null
            private set
    }
}
