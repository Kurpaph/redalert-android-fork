package com.red.alert.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import com.red.alert.logic.services.ServiceManager
import com.red.alert.logic.settings.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Got boot completed event?
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Start the Pushy push service
            ServiceManager.startPushyService(context)

            // Location alerts enabled?
            if (AppPreferences.getLocationAlertsEnabled(context)) {
                // Start the location service
                ServiceManager.startLocationService(context)
            }
        }
    }
}
