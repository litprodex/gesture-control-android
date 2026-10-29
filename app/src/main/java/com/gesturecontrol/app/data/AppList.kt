package com.gesturecontrol.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Aplikacja widoczna w launcherze telefonu. */
data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable)

object AppList {

    /** Wszystkie aplikacje z ikoną w launcherze, posortowane alfabetycznie. Wołać poza wątkiem UI. */
    fun load(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { LaunchableApp(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Nazwa aplikacji do wyświetlenia albo null, jeśli została odinstalowana. */
    fun label(context: Context, packageName: String?): String? {
        if (packageName == null) return null
        return try {
            val pm = context.packageManager
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun launchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
}
