package com.reelay.app.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.reelay.app.data.PreferencesManager

object StealthManager {
    private const val LAUNCHER_ALIAS_CLASS = "com.reelay.app.LauncherAlias"

    fun setAppHidden(context: Context, hide: Boolean) {
        val packageManager = context.packageManager
        val componentName = ComponentName(context.packageName, LAUNCHER_ALIAS_CLASS)

        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }

        packageManager.setComponentEnabledSetting(
            componentName,
            newState,
            PackageManager.DONT_KILL_APP
        )

        PreferencesManager(context).isStealthMode = hide
    }

    fun isAppHidden(context: Context): Boolean {
        val packageManager = context.packageManager
        val componentName = ComponentName(context.packageName, LAUNCHER_ALIAS_CLASS)
        val state = packageManager.getComponentEnabledSetting(componentName)
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}
