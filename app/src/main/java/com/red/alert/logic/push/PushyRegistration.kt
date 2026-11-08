package com.red.alert.logic.push

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log

import com.red.alert.R
import com.red.alert.config.Logging
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.formatting.StringUtils

import me.pushy.sdk.Pushy

object PushyRegistration {
    @JvmStatic
    fun registerForPushNotifications(context: Context): String {
        // Acquire a unique registration ID for this device
        val token = Pushy.register(context)

        // Save token
        saveRegistrationToken(context, token)

        // Log to logcat
        Log.d(Logging.TAG, "Pushy registration success: $token")

        // Return token to be sent to API
        return token
    }

    @JvmStatic
    fun getRegistrationToken(context: Context): String? {
        // Get it from SharedPreferences (may be null)
        return Singleton.getSharedPreferences(context).getString(context.getString(R.string.pushyTokenPref), "")
    }

    @JvmStatic
    fun isRegistered(context: Context): Boolean {
        // Check whether it's null (in which case we never successfully registered)
        return !StringUtils.stringIsNullOrEmpty(getRegistrationToken(context))
    }

    @JvmStatic
    fun saveRegistrationToken(context: Context, registrationToken: String?) {
        // Edit shared preferences
        val editor: SharedPreferences.Editor = Singleton.getSharedPreferences(context).edit()

        // Store boolean value
        editor.putString(context.getString(R.string.pushyTokenPref), registrationToken)

        // Save and flush
        editor.commit()
    }

    @JvmStatic
    fun updateSubscriptions(context: Context) {
        // Make sure registered for notifications
        if (!PushyRegistration.isRegistered(context)) {
            return
        }

        // Unsubscribe from all
        Pushy.unsubscribe("*", context)

        // Get new subscriptions list
        val subscriptions = AppPreferences.getSubscriptions(context)

        // Get total subscription count
        val totalSubscriptions = subscriptions.size

        // Got any?
        if (totalSubscriptions > 0) {
            // More than 100?
            if (totalSubscriptions > 100) {
                // Split into batches
                for (i in 0 until totalSubscriptions step 100) {
                    // Create a sublist of size batchSize or smaller if it's the last batch
                    Pushy.subscribe(subscriptions.subList(i, minOf(i + 100, totalSubscriptions)).toTypedArray(), context)
                }
            }
            else {
                // Subscribe to new topics (<= 100)
                Pushy.subscribe(subscriptions.toTypedArray(), context)
            }

            // Log it
            Log.d(Logging.TAG, "Pushy subscribe success: ${TextUtils.join(", ", subscriptions)}")
        }
        else {
            // Log it
            Log.d(Logging.TAG, "Pushy unsubscribed from all")
        }
    }
}
