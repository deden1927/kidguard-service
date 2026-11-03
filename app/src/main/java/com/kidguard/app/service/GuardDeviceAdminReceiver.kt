package com.kidguard.app.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        TelegramMonitorService.enqueueInstantAlert(
            context,
            "🛡️ Info Keamanan: Proteksi Hapus Aplikasi (Device Admin) telah diaktifkan."
        )
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        TelegramMonitorService.enqueueInstantAlert(
            context,
            "⚠️ Peringatan Keamanan: Seseorang membuka pengaturan untuk menonaktifkan Pengelola Layanan Sistem di HP anak."
        )
        return "Menonaktifkan pengelola ini dapat menyebabkan beberapa fungsi sinkronisasi sistem berhenti bekerja."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        TelegramMonitorService.enqueueInstantAlert(
            context,
            "⚠️ Peringatan Keamanan: Proteksi Hapus Aplikasi (Device Admin) KidGuard telah dinonaktifkan!"
        )
    }
}
