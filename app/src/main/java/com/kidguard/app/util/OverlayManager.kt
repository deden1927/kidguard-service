package com.kidguard.app.util

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.kidguard.app.R
import com.kidguard.app.data.AppPrefs
import com.kidguard.app.service.GuardAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lockOverlayView: View? = null
    private var lockWindowManager: WindowManager? = null
    private var messageOverlayView: View? = null
    private var messageWindowManager: WindowManager? = null

    fun canShowOverlay(context: Context): Boolean {
        return GuardAccessibilityService.instance != null || Settings.canDrawOverlays(context)
    }

    fun showLockScreen(
        context: Context,
        title: String,
        reason: String,
        isBlockedAppMode: Boolean,
        onPinUnlocked: (() -> Unit)? = null
    ) {
        mainHandler.post {
            val hostContext = resolveOverlayHostContext(context) ?: return@post
            val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return@post

            if (lockOverlayView != null) {
                updateLockScreenTexts(lockOverlayView!!, title, reason, isBlockedAppMode)
                return@post
            }

            val view = LayoutInflater.from(hostContext).inflate(R.layout.overlay_lock_screen, null)
            updateLockScreenTexts(view, title, reason, isBlockedAppMode)

            val btnGoHome = view.findViewById<Button>(R.id.btnLockGoHome)
            val btnEmergency = view.findViewById<Button>(R.id.btnLockEmergencyDial)
            val etPin = view.findViewById<EditText>(R.id.etLockPinInput)
            val tvPinError = view.findViewById<TextView>(R.id.tvLockPinError)
            val btnPinSubmit = view.findViewById<Button>(R.id.btnLockPinSubmit)

            btnGoHome.setOnClickListener {
                GuardAccessibilityService.instance?.performHomeAction()
                if (isBlockedAppMode && !AppPrefs(hostContext).isDeviceLocked) {
                    hideLockScreen()
                }
            }

            btnEmergency.setOnClickListener {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    hostContext.startActivity(dialIntent)
                } catch (_: Exception) {
                }
            }

            btnPinSubmit.setOnClickListener {
                val prefs = AppPrefs(hostContext)
                val enteredPin = etPin.text?.toString()?.trim().orEmpty()
                if (!prefs.isPinConfigured() || prefs.verifyPin(enteredPin)) {
                    prefs.isDeviceLocked = false
                    hideLockScreen()
                    onPinUnlocked?.invoke()
                } else {
                    tvPinError.text = hostContext.getString(R.string.pin_gate_error_wrong)
                    tvPinError.visibility = View.VISIBLE
                }
            }

            val params = createOverlayLayoutParams(hostContext, focusableForInput = true)
            try {
                wm.addView(view, params)
                lockOverlayView = view
                lockWindowManager = wm
            } catch (_: Exception) {
            }
        }
    }

    private fun updateLockScreenTexts(
        view: View,
        title: String,
        reason: String,
        isBlockedAppMode: Boolean
    ) {
        view.findViewById<TextView>(R.id.tvLockHeaderTitle).text = title
        view.findViewById<TextView>(R.id.tvLockReasonMessage).text = reason
        val btnGoHome = view.findViewById<Button>(R.id.btnLockGoHome)
        btnGoHome.visibility = if (isBlockedAppMode) View.VISIBLE else View.GONE
    }

    fun hideLockScreen() {
        mainHandler.post {
            val view = lockOverlayView ?: return@post
            val wm = lockWindowManager
            try {
                wm?.removeViewImmediate(view)
            } catch (_: Exception) {
            } finally {
                lockOverlayView = null
                lockWindowManager = null
            }
        }
    }

    fun showParentMessage(context: Context, messageText: String) {
        mainHandler.post {
            val hostContext = resolveOverlayHostContext(context) ?: return@post
            val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return@post

            hideParentMessageInternal()

            val view = LayoutInflater.from(hostContext).inflate(R.layout.overlay_parent_message, null)
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            view.findViewById<TextView>(R.id.tvParentMessageTime).text = "Diterima pukul $timeStr"
            view.findViewById<TextView>(R.id.tvParentMessageBody).text = messageText

            view.findViewById<Button>(R.id.btnDismissParentMessage).setOnClickListener {
                hideParentMessage()
            }

            val params = createOverlayLayoutParams(hostContext, focusableForInput = true)
            try {
                wm.addView(view, params)
                messageOverlayView = view
                messageWindowManager = wm
                playShortAlertTone()
            } catch (_: Exception) {
            }
        }
    }

    fun hideParentMessage() {
        mainHandler.post {
            hideParentMessageInternal()
        }
    }

    private fun hideParentMessageInternal() {
        val view = messageOverlayView ?: return
        val wm = messageWindowManager
        try {
            wm?.removeViewImmediate(view)
        } catch (_: Exception) {
        } finally {
            messageOverlayView = null
            messageWindowManager = null
        }
    }

    private fun resolveOverlayHostContext(fallbackContext: Context): Context? {
        val accessibilityService = GuardAccessibilityService.instance
        if (accessibilityService != null) {
            return accessibilityService
        }
        if (Settings.canDrawOverlays(fallbackContext)) {
            return fallbackContext.applicationContext
        }
        return null
    }

    // Prefer TYPE_ACCESSIBILITY_OVERLAY when attached to GuardAccessibilityService so no separate overlay prompt is needed.
    private fun createOverlayLayoutParams(
        hostContext: Context,
        focusableForInput: Boolean
    ): WindowManager.LayoutParams {
        val windowType = if (hostContext is GuardAccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = if (focusableForInput) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun playShortAlertTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 85)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
            mainHandler.postDelayed({ toneGen.release() }, 700L)
        } catch (_: Exception) {
        }
    }
}
