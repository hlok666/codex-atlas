package com.codexatlas.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the bridge service after reboot or an in-place APK update. */
class AtlasBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (BridgePreferences.token(context).isNotBlank()) AtlasSyncService.start(context)
        }
    }
}
