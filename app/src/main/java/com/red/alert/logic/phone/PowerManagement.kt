package com.red.alert.logic.phone

import android.content.Context
import android.os.PowerManager

import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.settings.AppPreferences

object PowerManagement {
    @JvmStatic
    fun wakeUpScreen(alertType: String, context: Context) {
        // User disabled this feature?
        if (!AppPreferences.getWakeScreenEnabled(context)) {
            // Stop execution
            return
        }

        // Alert popup enabled?
        if (AppPreferences.getPopupEnabled(context)) {
            // Stop execution, popup window already wakes up the screen
            return
        }

        // Type must be a primary "alert"
        if (alertType != AlertTypes.PRIMARY) {
            // Stop execution
            return
        }

        // Turn screen on
        wakeUpScreen(context)
    }

    @JvmStatic
    fun wakeUpScreen(context: Context) {
        // Get power manager instance
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // Screen off?
        if (!powerManager.isScreenOn) {
            // Turn on
            val wl = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "redalert:alert")

            try {
                // Release wakelock automatically after 3 seconds
                wl.acquire(3000)
            }
            catch (exc: Exception) {
                // Ignore exceptions
            }
        }
    }
}
