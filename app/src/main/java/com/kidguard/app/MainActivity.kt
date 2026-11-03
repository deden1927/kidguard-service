package com.kidguard.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.kidguard.app.data.AppPrefs
import com.kidguard.app.service.GuardAccessibilityService
import com.kidguard.app.service.GuardDeviceAdminReceiver
import com.kidguard.app.service.TelegramMonitorService
import com.kidguard.app.telegram.TelegramClient
import com.kidguard.app.util.LocationHelper
import com.kidguard.app.util.UsageStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isSessionUnlocked: Boolean = false

    private lateinit var btnToggleTheme: Button
    private lateinit var layoutPinGate: LinearLayout
    private lateinit var etPinGateInput: EditText
    private lateinit var tvPinGateError: TextView
    private lateinit var btnUnlockPinGate: Button

    private lateinit var layoutParentPanel: LinearLayout
    private lateinit var tvServiceRunningBadge: TextView
    private lateinit var etBotToken: EditText
    private lateinit var etParentChatId: EditText
    private lateinit var etParentPin: EditText
    private lateinit var pbConnectionLoading: ProgressBar
    private lateinit var tvConnectionState: TextView
    private lateinit var btnSaveAndStart: Button
    private lateinit var btnTestTelegram: Button
    private lateinit var btnStopService: Button
    private lateinit var btnLockPanelNow: Button

    private lateinit var tvPermAccessibilityStatus: TextView
    private lateinit var btnPermAccessibility: Button
    private lateinit var tvPermUsageStatus: TextView
    private lateinit var btnPermUsage: Button
    private lateinit var tvPermLocationStatus: TextView
    private lateinit var btnPermLocation: Button
    private lateinit var tvPermOverlayStatus: TextView
    private lateinit var btnPermOverlay: Button
    private lateinit var tvPermBatteryStatus: TextView
    private lateinit var btnPermBattery: Button
    private lateinit var tvPermAdminStatus: TextView
    private lateinit var btnPermAdmin: Button
    private lateinit var tvPermNotifStatus: TextView
    private lateinit var btnPermNotif: Button

    private lateinit var tvHistoryContent: TextView
    private lateinit var btnRefreshHistory: Button
    private lateinit var btnClearHistory: Button

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionsChecklist()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionsChecklist()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPrefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.themeMode)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState != null) {
            isSessionUnlocked = savedInstanceState.getBoolean(STATE_SESSION_UNLOCKED, false)
        } else if (!prefs.isPinConfigured()) {
            isSessionUnlocked = true
        }

        bindViews()
        populateSavedCredentials()
        setupListeners()
        updateThemeButtonText()
        applyPinGateVisibility()
        refreshPermissionsChecklist()
        loadActivityHistoryPreview()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceBadge()
        refreshPermissionsChecklist()
        if (isSessionUnlocked) {
            loadActivityHistoryPreview()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SESSION_UNLOCKED, isSessionUnlocked)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        btnToggleTheme = findViewById(R.id.btnToggleTheme)
        layoutPinGate = findViewById(R.id.layoutPinGate)
        etPinGateInput = findViewById(R.id.etPinGateInput)
        tvPinGateError = findViewById(R.id.tvPinGateError)
        btnUnlockPinGate = findViewById(R.id.btnUnlockPinGate)

        layoutParentPanel = findViewById(R.id.layoutParentPanel)
        tvServiceRunningBadge = findViewById(R.id.tvServiceRunningBadge)
        etBotToken = findViewById(R.id.etBotToken)
        etParentChatId = findViewById(R.id.etParentChatId)
        etParentPin = findViewById(R.id.etParentPin)
        pbConnectionLoading = findViewById(R.id.pbConnectionLoading)
        tvConnectionState = findViewById(R.id.tvConnectionState)
        btnSaveAndStart = findViewById(R.id.btnSaveAndStart)
        btnTestTelegram = findViewById(R.id.btnTestTelegram)
        btnStopService = findViewById(R.id.btnStopService)
        btnLockPanelNow = findViewById(R.id.btnLockPanelNow)

        tvPermAccessibilityStatus = findViewById(R.id.tvPermAccessibilityStatus)
        btnPermAccessibility = findViewById(R.id.btnPermAccessibility)
        tvPermUsageStatus = findViewById(R.id.tvPermUsageStatus)
        btnPermUsage = findViewById(R.id.btnPermUsage)
        tvPermLocationStatus = findViewById(R.id.tvPermLocationStatus)
        btnPermLocation = findViewById(R.id.btnPermLocation)
        tvPermOverlayStatus = findViewById(R.id.tvPermOverlayStatus)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        tvPermBatteryStatus = findViewById(R.id.tvPermBatteryStatus)
        btnPermBattery = findViewById(R.id.btnPermBattery)
        tvPermAdminStatus = findViewById(R.id.tvPermAdminStatus)
        btnPermAdmin = findViewById(R.id.btnPermAdmin)
        tvPermNotifStatus = findViewById(R.id.tvPermNotifStatus)
        btnPermNotif = findViewById(R.id.btnPermNotif)

        tvHistoryContent = findViewById(R.id.tvHistoryContent)
        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)
    }

    private fun populateSavedCredentials() {
        etBotToken.setText(prefs.botToken)
        etParentChatId.setText(prefs.parentChatId)
        etParentPin.setText(prefs.parentPin)
        refreshServiceBadge()
    }

    private fun setupListeners() {
        btnToggleTheme.setOnClickListener {
            val nextMode = if (prefs.themeMode == AppCompatDelegate.MODE_NIGHT_YES) {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }
            prefs.themeMode = nextMode
            AppCompatDelegate.setDefaultNightMode(nextMode)
            updateThemeButtonText()
        }

        btnUnlockPinGate.setOnClickListener {
            val candidate = etPinGateInput.text?.toString()?.trim().orEmpty()
            if (candidate.isEmpty()) {
                tvPinGateError.text = getString(R.string.pin_gate_error_empty)
                tvPinGateError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            if (prefs.verifyPin(candidate)) {
                isSessionUnlocked = true
                etPinGateInput.setText("")
                tvPinGateError.visibility = View.GONE
                applyPinGateVisibility()
                loadActivityHistoryPreview()
            } else {
                tvPinGateError.text = getString(R.string.pin_gate_error_wrong)
                tvPinGateError.visibility = View.VISIBLE
            }
        }

        btnSaveAndStart.setOnClickListener {
            saveConfigurationAndStartService()
        }

        btnTestTelegram.setOnClickListener {
            testTelegramConnectionOnly()
        }

        btnStopService.setOnClickListener {
            TelegramMonitorService.stopService(this)
            refreshServiceBadge()
            showConnectionErrorOrNotice(
                "Layanan pemantauan dihentikan oleh Orang Tua. Ketuk Simpan Konfigurasi & Jalankan Layanan untuk mengaktifkan kembali."
            )
        }

        btnLockPanelNow.setOnClickListener {
            if (!prefs.isPinConfigured()) {
                showConnectionErrorOrNotice(
                    "Belum dapat mengunci panel: isi PIN Proteksi Aplikasi (4 sampai 8 digit) lalu ketuk Simpan Konfigurasi terlebih dahulu."
                )
                return@setOnClickListener
            }
            isSessionUnlocked = false
            applyPinGateVisibility()
        }

        btnPermAccessibility.setOnClickListener {
            startActivitySafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnPermUsage.setOnClickListener {
            startActivitySafely(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnPermLocation.setOnClickListener {
            requestLocationPermissionsFlow()
        }

        btnPermOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivitySafely(intent)
        }

        btnPermBattery.setOnClickListener {
            val directIntent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            if (!startActivitySafely(directIntent)) {
                startActivitySafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        btnPermAdmin.setOnClickListener {
            val adminComponent = ComponentName(this, GuardDeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_description)
                )
            }
            startActivitySafely(intent)
        }

        btnPermNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivitySafely(intent)
            }
        }

        btnRefreshHistory.setOnClickListener {
            loadActivityHistoryPreview()
        }

        btnClearHistory.setOnClickListener {
            prefs.clearActivityHistory()
            tvHistoryContent.text = getString(R.string.history_state_empty)
        }
    }

    private fun updateThemeButtonText() {
        val isDark = prefs.themeMode == AppCompatDelegate.MODE_NIGHT_YES
        btnToggleTheme.text = if (isDark) {
            getString(R.string.btn_toggle_theme_dark)
        } else {
            getString(R.string.btn_toggle_theme_light)
        }
    }

    private fun applyPinGateVisibility() {
        if (prefs.isPinConfigured() && !isSessionUnlocked) {
            layoutPinGate.visibility = View.VISIBLE
            layoutParentPanel.visibility = View.GONE
        } else {
            layoutPinGate.visibility = View.GONE
            layoutParentPanel.visibility = View.VISIBLE
        }
    }

    private fun saveConfigurationAndStartService() {
        val token = etBotToken.text?.toString()?.trim().orEmpty()
        val chatId = etParentChatId.text?.toString()?.trim().orEmpty()
        val pin = etParentPin.text?.toString()?.trim().orEmpty()

        if (token.isBlank() || !token.contains(":")) {
            showConnectionErrorOrNotice(
                "Format Token Bot belum valid. Salin token utuh dari @BotFather (contoh: 7123456789:AAH...)."
            )
            return
        }
        if (chatId.isBlank() || chatId.toLongOrNull() == null) {
            showConnectionErrorOrNotice(
                "Chat ID Orang Tua harus berupa angka (contoh: 987654321). Kirim pesan ke @userinfobot di Telegram untuk melihat angka Chat ID Anda."
            )
            return
        }
        if (pin.isNotEmpty() && pin.length !in 4..8) {
            showConnectionErrorOrNotice(
                "PIN Proteksi Aplikasi harus terdiri dari 4 sampai 8 angka."
            )
            return
        }

        prefs.botToken = token
        prefs.parentChatId = chatId
        prefs.parentPin = pin

        TelegramMonitorService.startService(this)
        refreshServiceBadge()
        verifyAndSendWelcomeToTelegram(token, chatId, isSavingConfig = true)
    }

    private fun testTelegramConnectionOnly() {
        val token = etBotToken.text?.toString()?.trim().orEmpty()
        val chatId = etParentChatId.text?.toString()?.trim().orEmpty()

        if (token.isBlank() || chatId.isBlank()) {
            showConnectionErrorOrNotice(
                "Isi Token Bot dan Chat ID terlebih dahulu sebelum mengirim pesan tes."
            )
            return
        }

        verifyAndSendWelcomeToTelegram(token, chatId, isSavingConfig = false)
    }

    private fun verifyAndSendWelcomeToTelegram(
        token: String,
        chatId: String,
        isSavingConfig: Boolean
    ) {
        setConnectionLoadingState(true)
        uiScope.launch {
            val meResult = TelegramClient.getMe(token)
            if (!meResult.ok) {
                setConnectionLoadingState(false)
                showConnectionErrorOrNotice(
                    "Gagal memverifikasi Token Bot: ${meResult.description}. Periksa kembali token dari @BotFather dan koneksi internet."
                )
                return@launch
            }

            val testMessage = if (isSavingConfig) {
                "KidGuard berhasil dikonfigurasi dan layanan pemantauan telah aktif. Ketuk tombol menu di bawah atau ketik /status untuk memantau perangkat."
            } else {
                "Tes koneksi KidGuard berhasil. Bot @${meResult.botUsername} siap menerima perintah dari Chat ID ini."
            }

            val sendResult = TelegramClient.sendMessage(
                botToken = token,
                chatId = chatId,
                text = testMessage,
                includeReplyKeyboard = true
            )
            setConnectionLoadingState(false)

            if (sendResult.ok) {
                tvConnectionState.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_ok_text))
                tvConnectionState.text =
                    "Terhubung ke @${meResult.botUsername}. Pesan uji dan papan tombol cepat berhasil dikirim ke Chat ID $chatId."
            } else {
                showConnectionErrorOrNotice(
                    "Token bot valid (@${meResult.botUsername}), namun gagal mengirim pesan ke Chat ID $chatId: ${sendResult.description}. Pastikan Anda sudah menekan tombol START pada bot Anda di aplikasi Telegram."
                )
            }
        }
    }

    private fun setConnectionLoadingState(loading: Boolean) {
        pbConnectionLoading.visibility = if (loading) View.VISIBLE else View.GONE
        btnSaveAndStart.isEnabled = !loading
        btnTestTelegram.isEnabled = !loading
        if (loading) {
            tvConnectionState.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvConnectionState.text = getString(R.string.conn_state_loading)
        }
    }

    private fun showConnectionErrorOrNotice(message: String) {
        pbConnectionLoading.visibility = View.GONE
        tvConnectionState.setTextColor(ContextCompat.getColor(this, R.color.status_danger_text))
        tvConnectionState.text = message
    }

    private fun refreshServiceBadge() {
        val active = TelegramMonitorService.isRunning && prefs.isTelegramConfigured()
        if (active) {
            tvServiceRunningBadge.setBackgroundResource(R.drawable.bg_status_ok)
            tvServiceRunningBadge.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
            tvServiceRunningBadge.text = "[LAYANAN PEMANTAUAN AKTIF]"
        } else {
            tvServiceRunningBadge.setBackgroundResource(R.drawable.bg_status_danger)
            tvServiceRunningBadge.setTextColor(ContextCompat.getColor(this, R.color.status_danger_text))
            tvServiceRunningBadge.text = "[LAYANAN BELUM BERJALAN]"
        }
    }

    private fun refreshPermissionsChecklist() {
        val a11yGranted = isAccessibilityServiceEnabled()
        updatePermissionTitleStatus(
            tvPermAccessibilityStatus,
            getString(R.string.perm_accessibility_title),
            a11yGranted
        )

        val usageGranted = UsageStatsHelper.hasUsageStatsPermission(this)
        updatePermissionTitleStatus(
            tvPermUsageStatus,
            getString(R.string.perm_usage_title),
            usageGranted
        )

        val locationGranted = LocationHelper.hasLocationPermission(this)
        updatePermissionTitleStatus(
            tvPermLocationStatus,
            getString(R.string.perm_location_title),
            locationGranted
        )

        val overlayGranted = Settings.canDrawOverlays(this)
        updatePermissionTitleStatus(
            tvPermOverlayStatus,
            getString(R.string.perm_overlay_title),
            overlayGranted
        )

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        updatePermissionTitleStatus(
            tvPermBatteryStatus,
            getString(R.string.perm_battery_title),
            batteryIgnored
        )

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(this, GuardDeviceAdminReceiver::class.java)
        val adminActive = dpm?.isAdminActive(adminComponent) == true
        updatePermissionTitleStatus(
            tvPermAdminStatus,
            getString(R.string.perm_admin_title),
            adminActive
        )

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        updatePermissionTitleStatus(
            tvPermNotifStatus,
            getString(R.string.perm_notif_title),
            notifGranted
        )
    }

    private fun updatePermissionTitleStatus(
        textView: TextView,
        baseTitle: String,
        isGranted: Boolean
    ) {
        val statusTag = if (isGranted) "[AKTIF]" else "[BELUM AKTIF]"
        textView.text = "$statusTag $baseTitle"
        val colorRes = if (isGranted) R.color.status_ok_text else R.color.status_danger_text
        textView.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (GuardAccessibilityService.instance != null) return true
        val expectedComponent = ComponentName(this, GuardAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentStr = splitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentStr)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }

    private fun requestLocationPermissionsFlow() {
        if (!LocationHelper.hasLocationPermission(this)) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                )
                return
            }
        }
        val appSettingsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivitySafely(appSettingsIntent)
    }

    private fun loadActivityHistoryPreview() {
        tvHistoryContent.text = getString(R.string.history_state_loading)
        uiScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    prefs.getRecentActivities(limit = 15)
                }
                if (entries.isEmpty()) {
                    tvHistoryContent.text = getString(R.string.history_state_empty)
                } else {
                    tvHistoryContent.text = prefs.formatActivityEntries(entries)
                }
            } catch (_: Exception) {
                tvHistoryContent.text = getString(R.string.history_state_error)
            }
        }
    }

    private fun startActivitySafely(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            showConnectionErrorOrNotice(
                "Halaman pengaturan sistem ini tidak dapat dibuka secara langsung di perangkat ini. Silakan buka aplikasi Pengaturan Android secara manual."
            )
            false
        }
    }

    companion object {
        private const val STATE_SESSION_UNLOCKED = "state_session_unlocked"
    }
}
