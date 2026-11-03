package com.kidguard.app.util

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.kidguard.app.data.AppUsageSummary
import java.util.Calendar
import java.util.concurrent.TimeUnit

object UsageStatsHelper {

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getTodayUsageSummary(context: Context, maxItems: Int = 15): List<AppUsageSummary> {
        if (!hasUsageStatsPermission(context)) return emptyList()

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDayMillis = calendar.timeInMillis
        val nowMillis = System.currentTimeMillis()

        val aggregated = usageStatsManager.queryAndAggregateUsageStats(startOfDayMillis, nowMillis)
        if (aggregated.isEmpty()) return emptyList()

        val pm = context.packageManager
        val summaries = ArrayList<AppUsageSummary>()

        for ((pkg, stats) in aggregated) {
            val fgTime = stats.totalTimeInForeground
            // Ignore transient background wakeups shorter than 30 seconds and system UI shell.
            if (fgTime < 30_000L || pkg == "com.android.systemui" || pkg == context.packageName) {
                continue
            }
            val label = resolveAppLabel(pm, pkg)
            summaries.add(
                AppUsageSummary(
                    packageName = pkg,
                    appName = label,
                    totalForegroundMs = fgTime
                )
            )
        }

        return summaries
            .sortedByDescending { it.totalForegroundMs }
            .take(maxItems)
    }

    fun resolveAppLabel(pm: PackageManager, packageName: String): String {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().ifBlank { packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun formatDuration(millis: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return when {
            hours > 0 -> "${hours}j ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}d"
            else -> "${seconds}d"
        }
    }
}
