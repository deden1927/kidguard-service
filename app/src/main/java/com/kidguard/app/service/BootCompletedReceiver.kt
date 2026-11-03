package com.kidguard.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kidguard.app.data.AppPrefs

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = AppPrefs(context)
            if (prefs.isTelegramConfigured()) {
                TelegramMonitorService.startService(context)
                TelegramMonitorService.enqueueInstantAlert(
                    context,
                    "Info Sistem: Perangkat anak baru saja dinyalakan ulang dan layanan pemantauan KidGuard telah aktif kembali."
                )
            }
        }
    }
}
