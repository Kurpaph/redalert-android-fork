package com.red.alert.logic.push

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log

import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.red.alert.R
import com.red.alert.config.Logging
import com.red.alert.config.push.FCMGateway
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.localization.DateTime
import com.red.alert.utils.networking.HTTP

import me.pushy.sdk.lib.jackson.core.type.TypeReference

object FCMRegistration {
    @JvmStatic
    fun registerForPushNotifications(context: Context): String {
        // Get an FCM registration token
        val token = getFCMTokenSync()

        // Persist FCM token locally
        saveRegistrationToken(context, token)

        // Log to logcat
        Log.d(Logging.TAG, "FCM registration success: $token")

        // Return token for saving and processing
        return token
    }

    @JvmStatic
    fun getFCMTokenSync(): String {
        // Task to query for the most up-to-date FCM device token
        val task: Task<String> = FirebaseMessaging.getInstance().token

        // Wait for task to finish (blocks the thread)
        Tasks.await(task)

        // Task failed?
        if (!task.isSuccessful) {
            throw task.exception!!
        }

        // Return task result
        return task.result
    }

    @JvmStatic
    fun getRegistrationToken(context: Context): String? {
        // Get it from SharedPreferences (may be null)
        return Singleton.getSharedPreferences(context).getString(context.getString(R.string.fcmTokenPref), null)
    }

    @JvmStatic
    fun isRegistered(context: Context): Boolean {
        // Check whether it's null (in which case we never successfully registered)
        return getRegistrationToken(context) != null
    }

    @JvmStatic
    fun saveRegistrationToken(context: Context, registrationToken: String?) {
        // Edit shared preferences
        val editor: SharedPreferences.Editor = Singleton.getSharedPreferences(context).edit()

        // Store boolean value
        editor.putString(context.getString(R.string.fcmTokenPref), registrationToken)

        // Save and flush
        editor.commit()
    }

    @JvmStatic
    fun getCurrentFCMSubscriptions(context: Context): List<String> {
        // Store JSON as string initially
        val subscriptionsJSON: String

        try {
            // Send the request to our API
            subscriptionsJSON = HTTP.post("/subscriptions?token=${getRegistrationToken(context)}", "{}")
        } catch (exc: Exception) {
            // Non-recoverable error
            throw Exception("Get subscriptions request failed", exc)
        }

        // Prepare list of subscriptions
        val subscriptions: List<String>

        try {
            // Convert JSON to object
            subscriptions = Singleton.getJackson().readValue(subscriptionsJSON, object : TypeReference<List<String>>() {
            })
        } catch (exc: Exception) {
            // Non-recoverable error
            throw Exception("Get subscriptions request failed", exc)
        }

        // Return list
        return subscriptions
    }

    @JvmStatic
    fun updateSubscriptions(context: Context) {
        // Make sure registered for notifications
        if (!FCMRegistration.isRegistered(context)) {
            return
        }

        // FCM InstanceID API returns cached subscriptions for up to X seconds
        // Ensure enough time passed before the last time we updated the FCM subscriptions
        while (AppPreferences.getLastSubscribedTimestamp(context) > DateTime.getUnixTimestamp() - FCMGateway.FCM_SUBSCRIPTIONS_CACHE_TIME) {
            Thread.sleep(500)
        }

        // Get list of existing subscriptions
        val existingSubscriptions = FCMRegistration.getCurrentFCMSubscriptions(context)

        // Log for debugging purposes
        Log.d(Logging.TAG, "Currently subscribed to: [${TextUtils.join(", ", existingSubscriptions)}]")

        // Get new subscriptions list
        val subscriptions = AppPreferences.getSubscriptions(context)

        // Subscribe to new topics
        for (topic in subscriptions) {
            // Skip topics we're already subscribed to
            if (existingSubscriptions.contains(topic)) {
                continue
            }

            // Subscribe to topic
            val subscribe = FirebaseMessaging.getInstance().subscribeToTopic(topic)
                    .addOnCompleteListener(OnCompleteListener<Void> { task ->
                        if (!task.isSuccessful) {
                            Log.e(Logging.TAG, "FCM subscribe failed: ", task.exception)
                            return@OnCompleteListener
                        }

                        // Log it
                        Log.d(Logging.TAG, "FCM subscribe success: $topic")
                    })

            // Wait for task to finish
            while (!subscribe.isComplete) {
                Thread.sleep(50)
            }

            // Failed?
            if (!subscribe.isSuccessful) {
                throw Exception("Failed to subscribe to topic: $topic")
            }
        }

        // Unsubscribe from old topics
        for (topic in existingSubscriptions) {
            // Skip topics we're wanting to stay subscribed to
            if (subscriptions.contains(topic)) {
                continue
            }

            // Unsubscribe from old topic
            val unsubscribe = FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
                    .addOnCompleteListener(OnCompleteListener<Void> { task ->
                        if (!task.isSuccessful) {
                            Log.e(Logging.TAG, "FCM unsubscribe failed: ", task.exception)
                            return@OnCompleteListener
                        }

                        // Log it
                        Log.d(Logging.TAG, "FCM unsubscribe success: $topic")
                    })

            // Wait for task to finish
            while (!unsubscribe.isComplete) {
                Thread.sleep(50)
            }

            // Failed?
            if (!unsubscribe.isSuccessful) {
                throw Exception("Failed to unsubscribe from topic: $topic")
            }
        }

        // Update FCM last subscribed timestamp
        AppPreferences.updateLastSubscribedTimestamp(DateTime.getUnixTimestamp(), context)
    }
}
