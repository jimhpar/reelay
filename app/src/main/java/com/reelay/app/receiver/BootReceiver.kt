package com.reelay.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reelay.app.stealth.StealthManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val isHidden = StealthManager.isAppHidden(context)
            StealthManager.setAppHidden(context, isHidden)
        }
    }
}
