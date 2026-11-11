package com.red.alert.services.fcm

import android.util.Log

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.red.alert.config.Logging
import com.red.alert.logic.alerts.AlertLogic
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.communication.broadcasts.SelfTestEvents
import com.red.alert.logic.communication.push.PushParameters
import com.red.alert.logic.push.FCMRegistration
import com.red.alert.logic.push.PushyRegistration
import com.red.alert.utils.backend.RedAlertAPI
import com.red.alert.utils.communication.Broadcasts

class FirebaseService : FirebaseMessagingService() {
    override fun onNewToken(fcmToken: String) {
        // Debug log
        Log.d(Logging.TAG, "FCM onNewToken() called")

        // Already registered with API?
        if (RedAlertAPI.isRegistered(this)) {
            // Check whether FCM token has changed
            if (FCMRegistration.isRegistered(this) && FCMRegistration.getRegistrationToken(this) != fcmToken) {
                try {
                    // Get most up-to-date Pushy device token
                    val pushyToken = PushyRegistration.registerForPushNotifications(this@FirebaseService)

                    // Update tokens server-side
                    RedAlertAPI.updatePushTokens(fcmToken, pushyToken, this@FirebaseService)
                }
                catch (exc: Exception) {
                    // Log failure
                    Log.e(Logging.TAG, "Updating device token failed", exc)
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Get payload map
        val data: Map<String, String> = remoteMessage.data

        // Grab push data from extras
        val alertId = data[PushParameters.ALERT_ID]
        val alertType = data[PushParameters.ALERT_TYPE]
        val threatType = data[PushParameters.THREAT_TYPE]
        val alertCities = data[PushParameters.ALERT_CITIES]
        val instructions = data[PushParameters.ALERT_INSTRUCTIONS]
        val leaveShelter = data[PushParameters.ALERT_LEAVE_SHELTER]

        // Bad push?
        if (alertType == null || alertCities == null) {
            // Log it
            Log.e(Logging.TAG, "Malformed push received via FCM")

            // Stop execution to prevent crash
            return
        }

        // Test alert?
        if (alertType == AlertTypes.TEST) {
            // Log success
            Log.d(Logging.TAG, "FCM test passed")

            // Mark test as complete via broadcast
            Broadcasts.publish(this, SelfTestEvents.FCM_TEST_PASSED)

            // Stop execution (we don't want any visible notification)
            return
        }

        // Log it
        Log.d(Logging.TAG, "Received push via FCM gateway")

        // Receive the alert
        AlertLogic.processIncomingAlert(threatType, alertCities, alertType, alertId, instructions, leaveShelter, this)
    }
}
