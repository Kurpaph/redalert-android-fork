package com.red.alert.utils.backend

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

import com.red.alert.R
import com.red.alert.config.API
import com.red.alert.config.Logging
import com.red.alert.logic.push.FCMRegistration
import com.red.alert.logic.push.PushyRegistration
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.model.req.RegistrationRequest
import com.red.alert.model.req.SubscribeRequest
import com.red.alert.model.req.UpdateNotificationsRequest
import com.red.alert.model.req.UpdateTokenRequest
import com.red.alert.model.res.GenericResponse
import com.red.alert.model.res.RegistrationResponse
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.metadata.LocationData
import com.red.alert.utils.networking.HTTP

object RedAlertAPI {
    @JvmStatic
    fun register(fcmToken: String?, pushyToken: String?, context: Context) {
        // Prepare an object to store and send the FCM device token to the API
        val register = RegistrationRequest(fcmToken, pushyToken, API.PLATFORM_IDENTIFIER)

        // Send the request to our API
        val response: String

        try {
            // Execute request
            response = HTTP.post("/register", Singleton.getJackson().writeValueAsString(register))
        }
        catch (exc: Exception) {
            // Log failure
            throw Exception("Registration failed", exc)
        }

        // Convert response into JSON object
        val user: RegistrationResponse

        try {
            // Convert JSON to object
            user = Singleton.getJackson().readValue(response, RegistrationResponse::class.java)
        }
        catch (exc: Exception) {
            // Log error
            throw Exception("Parsing registration response failed", exc)
        }

        // Bad response?
        if (user.id == 0L || StringUtils.stringIsNullOrEmpty(user.hash)) {
            // Log error
            throw Exception("Parsing registration response failed: $response")
        }

        // Persist FCM token locally
        FCMRegistration.saveRegistrationToken(context, fcmToken)

        // Persist Pushy token locally
        PushyRegistration.saveRegistrationToken(context, pushyToken)

        // Persist user ID and hash to SharedPreferences
        val editor: SharedPreferences.Editor = Singleton.getSharedPreferences(context).edit()

        // Store user info
        editor.putLong(context.getString(R.string.userIdPref), user.id)
        editor.putString(context.getString(R.string.userHashPref), user.hash)

        // Save and flush to disk
        editor.commit()

        // Log success
        Log.d(Logging.TAG, "RedAlert user registration success: ${user.id}")
    }

    @JvmStatic
    fun isRegistered(context: Context): Boolean {
        // Check for positive user ID
        return getUserId(context) > 0
    }

    @JvmStatic
    fun isSubscribed(context: Context): Boolean {
        // Check for subscribed boolean flag
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.isSubscribedPref), false)
    }

    @JvmStatic
    fun getUserId(context: Context): Long {
        // Query shared preferences
        return Singleton.getSharedPreferences(context).getLong(context.getString(R.string.userIdPref), 0)
    }

    @JvmStatic
    fun getUserHash(context: Context): String? {
        // Query shared preferences
        return Singleton.getSharedPreferences(context).getString(context.getString(R.string.userHashPref), "")
    }

    @JvmStatic
    fun subscribe(context: Context) {
        val primarySubscriptions: MutableList<String> = ArrayList()
        val secondarySubscriptions: MutableList<String> = ArrayList()

        // Location alerts enabled?
        if (AppPreferences.getLocationAlertsEnabled(context)) {
            // Subscribe to all and check proximity client-side
            primarySubscriptions.add("all")
        }

        // Get user's selected primary zones
        val selectedZones = Singleton.getSharedPreferences(context).getString(context.getString(R.string.selectedZonesPref), context.getString(R.string.none))

        // Anything selected?
        if (!StringUtils.stringIsNullOrEmpty(selectedZones)) {
            // Explode selected zones into array and push into primarySubs
            primarySubscriptions.addAll(LocationData.explodePSV(selectedZones))
        }
        else {
            // Empty value means all regions
            primarySubscriptions.add("all")
        }

        // Get user's selected cities
        val selectedCities = Singleton.getSharedPreferences(context).getString(context.getString(R.string.selectedCitiesPref), context.getString(R.string.none))

        // Anything selected?
        if (!StringUtils.stringIsNullOrEmpty(selectedCities)) {
            // Explode selected cities into array and push into primarySubs
            primarySubscriptions.addAll(LocationData.explodePSV(selectedCities))
        }
        else {
            // Empty value means all cities
            primarySubscriptions.add("all")
        }

        // Get user's secondary cities
        val secondaryCities = Singleton.getSharedPreferences(context).getString(context.getString(R.string.selectedSecondaryCitiesPref), context.getString(R.string.none))

        // Anything selected?
        if (!StringUtils.stringIsNullOrEmpty(secondaryCities)) {
            // Explode selected cities into array and push into secondarySubs
            secondarySubscriptions.addAll(LocationData.explodePSV(secondaryCities))
        }
        else {
            // Empty value means all cities
            secondarySubscriptions.add("all")
        }

        // Prepare an object to send a subscription request to the API
        val subscribe = SubscribeRequest(getUserId(context), getUserHash(context), cleanSubscriptions(primarySubscriptions), cleanSubscriptions(secondarySubscriptions))

        // Send the request to our API
        val responseJson: String

        try {
            // Execute request
            responseJson = HTTP.post("/subscribe", Singleton.getJackson().writeValueAsString(subscribe))
        }
        catch (exc: Exception) {
            // Log failure
            throw Exception("Subscribe request failed", exc)
        }

        // Convert response into JSON object
        val response: GenericResponse

        try {
            // Convert JSON to object
            response = Singleton.getJackson().readValue(responseJson, GenericResponse::class.java)
        }
        catch (exc: Exception) {
            // Log error
            throw Exception("Parsing subscribe response failed", exc)
        }

        // Failed?
        if (!response.success) {
            // Log error
            throw Exception("Failed to subscribe to alerts: ${response.error}")
        }

        // Persist subscription flag to SharedPreferences
        val editor: SharedPreferences.Editor = Singleton.getSharedPreferences(context).edit()

        // Set is_subscribed to true
        editor.putBoolean(context.getString(R.string.isSubscribedPref), true)

        // Save and flush to disk
        editor.commit()
    }

    @JvmStatic
    fun updateNotificationPreferences(context: Context) {
        // Prepare an object to send the notification update request to the API
        val updateNotifications = UpdateNotificationsRequest(getUserId(context), getUserHash(context), AppPreferences.getNotificationsEnabled(context), AppPreferences.getSecondaryNotificationsEnabled(context))

        // Send the request to our API
        val responseJson: String

        try {
            // Execute request
            responseJson = HTTP.post("/update/notifications", Singleton.getJackson().writeValueAsString(updateNotifications))
        }
        catch (exc: Exception) {
            // Log failure
            throw Exception("Notification setting update failed", exc)
        }

        // Convert response into JSON object
        val response: GenericResponse

        try {
            // Convert JSON to object
            response = Singleton.getJackson().readValue(responseJson, GenericResponse::class.java)
        }
        catch (exc: Exception) {
            // Log error
            throw Exception("Parsing notification preferences response failed", exc)
        }

        // Failed?
        if (!response.success) {
            // Log error
            throw Exception("Failed to update notification preferences: ${response.error}")
        }
    }

    @JvmStatic
    fun updatePushTokens(fcmToken: String?, pushyToken: String?, context: Context) {
        // Debug log
        Log.d(Logging.TAG, "Updating FCM & Pushy tokens...")

        // Prepare an object to send the new token to the API
        val updateRequest = UpdateTokenRequest(getUserId(context), getUserHash(context), fcmToken, pushyToken)

        // Send the request to our API
        val responseJson: String

        try {
            // Execute request
            responseJson = HTTP.post("/update/token", Singleton.getJackson().writeValueAsString(updateRequest))
        }
        catch (exc: Exception) {
            // Log failure
            throw Exception("Push token update request failed", exc)
        }

        // Convert response into JSON object
        val response: GenericResponse

        try {
            // Convert JSON to object
            response = Singleton.getJackson().readValue(responseJson, GenericResponse::class.java)
        }
        catch (exc: Exception) {
            // Log error
            throw Exception("Parsing push token request failed", exc)
        }

        // Failed?
        if (!response.success) {
            // Log error
            throw Exception("Failed to update push tokens: ${response.error}")
        }

        // Persist FCM token locally
        FCMRegistration.saveRegistrationToken(context, fcmToken)

        // Persist Pushy token locally
        PushyRegistration.saveRegistrationToken(context, pushyToken)

        // Log success
        Log.d(Logging.TAG, "FCM & Pushy token update success")
    }

    private fun cleanSubscriptions(subscriptions: List<String>): List<String> {
        // Traverse items
        for (item in subscriptions) {
            // All selected at least once?
            if (item == "all" || item == "") {
                return listOf("all")
            }

            // "None" selected?
            if (item == "none") {
                return ArrayList()
            }
        }

        // Return original list
        return subscriptions
    }
}
