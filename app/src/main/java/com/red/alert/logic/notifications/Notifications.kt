package com.red.alert.logic.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Build
import android.text.TextUtils
import android.util.Log

import androidx.core.app.NotificationCompat

import com.red.alert.R
import com.red.alert.activities.Main
import com.red.alert.activities.Popup
import com.red.alert.config.Alerts
import com.red.alert.config.Logging
import com.red.alert.config.NotificationChannels
import com.red.alert.config.Sound
import com.red.alert.config.ThreatTypes
import com.red.alert.logic.alerts.AlertLogic
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.communication.intents.AlertPopupParameters
import com.red.alert.logic.communication.intents.MainActivityParameters
import com.red.alert.logic.communication.intents.RocketNotificationParameters
import com.red.alert.logic.feedback.VibrationLogic
import com.red.alert.logic.feedback.sound.SoundLogic
import com.red.alert.logic.phone.PowerManagement
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.receivers.NotificationDeletedReceiver
import com.red.alert.utils.communication.Broadcasts
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.localization.DateTime
import com.red.alert.utils.metadata.LocationData

object Notifications {
    @JvmStatic
    fun notify(context: Context, cities: List<String>, alertType: String, threatType: String, overrideSound: String?, instructions: String?) {
        // Parameters that get reassigned below
        var resolvedAlertType = alertType
        var resolvedInstructions = instructions

        // Localize threat type
        val localizedThreatType = LocationData.getLocalizedThreatType(threatType, context)

        // Prepare notification title with threat type and city name
        var notificationTitle = "$localizedThreatType: ${LocationData.getLocalizedCityNamesCSV(cities, context)}"

        // Prepare notification body with zone and countdown
        var notificationContent: String? = LocationData.getLocalizedCityZonesWithCountdownCSV(cities, threatType, context)

        // Missile alert?
        if (threatType.contains(ThreatTypes.MISSILES)) {
            // Add line break if needed
            if (!StringUtils.stringIsNullOrEmpty(notificationContent)) {
                notificationContent += "\n"
            }

            // Add threat instructions to notification body in a new line after zone and countdown
            notificationContent += LocationData.getLocalizedThreatInstructions(threatType, context)
        } else if (!threatType.contains(ThreatTypes.TEST)) {
            // For all other threat types, only display threat instructions in notification body (don't display zone / countdown)
            notificationContent = LocationData.getLocalizedThreatInstructions(threatType, context)
        }

        // HFC update with instructions?
        if ((threatType == ThreatTypes.EARLY_WARNING || threatType == ThreatTypes.LEAVE_SHELTER) && !StringUtils.stringIsNullOrEmpty(resolvedInstructions)) {
            // Try to localize HFC instructions
            resolvedInstructions = LocationData.getLocalizedInstructions(resolvedInstructions, context)

            // Set notification body as instructions
            notificationContent = resolvedInstructions
        }

        // In case there is no content
        if (StringUtils.stringIsNullOrEmpty(notificationContent)) {
            // Move title to content
            notificationContent = notificationTitle

            // Set title as app name
            notificationTitle = context.getString(R.string.appName)
        }

        // Sound picker open?
        if (resolvedAlertType.contains(AlertTypes.TEST_SOUND)) {
            // Set special notification description for sound testing notifications
            notificationContent = context.getString(R.string.testSound)
        }

        // System message?
        if (resolvedAlertType == AlertTypes.SYSTEM) {
            // Set title to app name
            notificationTitle = context.getString(R.string.appName)

            // Set notification body to system message text contained in cities variable
            notificationContent = TextUtils.join(", ", cities)
        }

        // Get notification manager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create a new notification and style it
        val builder = NotificationCompat.Builder(context)
                .setTicker(notificationContent)
                .setAutoCancel(true)
                .setContentTitle(notificationTitle)
                .setContentText(notificationContent)
                .setLights(Color.RED, 1000, 1000)
                .setSmallIcon(R.drawable.ic_notify)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setColor(context.resources.getColor(R.color.colorAccent))
                .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent))

        // Only display large icon in case title is less than X characters long
        // as it causes the title to get truncated prematurely
        if (notificationTitle.length < 55 || !threatType.contains(ThreatTypes.MISSILES)) {
            builder.setLargeIcon(BitmapFactory.decodeResource(context.resources, LocationData.getThreatDrawable(threatType)))
        }

        // Handle notification delete
        builder.setDeleteIntent(getNotificationDeletedReceiverIntent(context))

        // No click event for test notifications
        if (!resolvedAlertType.contains(AlertTypes.TEST)) {
            // Handle notification click
            builder.setContentIntent(getNotificationIntent(cities, threatType, resolvedInstructions, context))
        }

        // Generate a notification ID based on the unique hash-code of the notification title string to avoid duplicates
        val notificationId = notificationTitle.hashCode()

        // Cancel previous notification with same exact notification title string
        notificationManager.cancel(notificationId)

        // Secondary alert?
        if (AlertLogic.isSecondaryAlert(resolvedAlertType, cities, context)) {
            // Override type
            resolvedAlertType = AlertTypes.SECONDARY
        }

        // Configure notification channel (if required)
        setNotificationChannel(resolvedAlertType, threatType, overrideSound, builder, context)

        // Play sound by default
        var silentLeaveShelterAlert = false
        var leaveShelterCityAlertedRecently = false

        // Smart leave shelter alerts: only play sound if user's city was alerted recently
        if (resolvedAlertType == AlertTypes.PRIMARY && threatType == ThreatTypes.LEAVE_SHELTER && AppPreferences.getSmartLeaveShelterAlertsEnabled(context)) {
            // Max time after a go-to-shelter alert that the app should play a sound for Leave Shelter alerts (to avoid waking up sleeping civilians)
            val recentlyAlertedCutoffTimestamp = DateTime.getUnixTimestamp() - Alerts.LEAVE_SHELTER_ALERTS_SOUND_MAX_CUTOFF_TIME

            // Traverse alert cities
            for (city in cities) {
                // Did we have any alert for this city in the past 30 minutes?
                if (AppPreferences.getCityLastAlert(city, context) > recentlyAlertedCutoffTimestamp) {
                    leaveShelterCityAlertedRecently = true
                    break
                }
            }

            // No sound if no city alerted recently
            if (!leaveShelterCityAlertedRecently) {
                silentLeaveShelterAlert = true
            }
        }

        // Should play sound & vibrate? (Samsung devices)
        if (!silentLeaveShelterAlert) {
            // Set ringer mode to normal (Samsung Android 11+ devices)
            SoundLogic.overrideSilentRingerMode(resolvedAlertType, context)
        }

        try {
            // Issue the notification
            notificationManager.notify(notificationId, builder.build())
        }
        catch (exc: Exception) {
            // Log error
            Log.e(Logging.TAG, "Failed to create and display notification", exc)
        }

        // Should play sound & vibrate?
        if (!silentLeaveShelterAlert) {
            // Play alert sound (if applicable)
            SoundLogic.playSound(resolvedAlertType, threatType, overrideSound, context)

            // Vibrate (if applicable)
            VibrationLogic.issueVibration(resolvedAlertType, context)

            // Wake up phone screen (if enabled)
            PowerManagement.wakeUpScreen(resolvedAlertType, context)

            // Show alert popup (if applicable)
            Popup.showAlertPopup(resolvedAlertType, cities, threatType, resolvedInstructions, context)
        }

        // Reload recent alerts (if main activity is open)
        Broadcasts.publish(context, MainActivityParameters.RELOAD_RECENT_ALERTS)
    }

    private fun getNotificationDeletedReceiverIntent(context: Context): PendingIntent {
        // Prepare delete intent
        val deleteIntent = Intent(context, NotificationDeletedReceiver::class.java)

        // Set action
        deleteIntent.action = RocketNotificationParameters.NOTIFICATION_DELETED_ACTION

        // Get broadcast receiver
        return PendingIntent.getBroadcast(context, 0, deleteIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    @JvmStatic
    fun getNotificationIntent(cities: List<String>, threatType: String?, instructions: String?, context: Context): PendingIntent {
        // Prepare notification intent
        val notificationIntent = Intent(context, Main::class.java)

        // Pass on city name, threat type, and alert received timestamp
        notificationIntent.putExtra(AlertPopupParameters.THREAT_TYPE, threatType)
        notificationIntent.putExtra(AlertPopupParameters.INSTRUCTIONS, instructions)
        notificationIntent.putExtra(AlertPopupParameters.CITIES, cities.toTypedArray())
        notificationIntent.putExtra(AlertPopupParameters.TIMESTAMP, DateTime.getUnixTimestamp())

        // Prepare pending intent
        val pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Return it
        return pendingIntent
    }

    @JvmStatic
    fun getNotificationChannelId(alertType: String, threatType: String?, overrideSound: String?, context: Context): String {
        // Initialize notification channel params
        var channelId = NotificationChannels.PRIMARY_ALERT_NOTIFICATION_CHANNEL_ID

        // Secondary alert?
        if (alertType == AlertTypes.SECONDARY || alertType == AlertTypes.TEST_SECONDARY_SOUND) {
            channelId = NotificationChannels.SECONDARY_ALERT_NOTIFICATION_CHANNEL_ID
        }
        else {
            // Threat type defined?
            if (threatType != null) {
                // Early Warning
                if (threatType == ThreatTypes.EARLY_WARNING) {
                    channelId = NotificationChannels.PRIMARY_ALERT_EARLY_WARNING_NOTIFICATION_CHANNEL_ID
                }
                // Leave Shelter Alert
                else if (threatType == ThreatTypes.LEAVE_SHELTER) {
                    channelId = NotificationChannels.PRIMARY_ALERT_LEAVE_SHELTER_NOTIFICATION_CHANNEL_ID
                }
            }
        }

        // Using custom sound?
        if (SoundLogic.getAlertSoundName(alertType, threatType, overrideSound, context) == Sound.CUSTOM_SOUND_NAME) {
            channelId += NotificationChannels.CUSTOM_SOUND_NOTIFICATION_CHANNEL_SUFFIX
        }
        else {
            // No custom sound
            // Add version suffix to work around sound resource bug
            channelId += "_v2"
        }

        // All done
        return channelId
    }

    @JvmStatic
    fun setNotificationChannel(alertType: String, threatType: String?, overrideSound: String?, builder: NotificationCompat.Builder?, context: Context) {
        // Android O and up
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        // Get notification manager instance
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Delete old (bugged) channels because notification channels persist sound resource IDs which change between builds
        manager.deleteNotificationChannel(NotificationChannels.OLD_PRIMARY_ALERT_NOTIFICATION_CHANNEL_ID)
        manager.deleteNotificationChannel(NotificationChannels.OLD_SECONDARY_ALERT_NOTIFICATION_CHANNEL_ID)

        // Determine notification channel ID
        val channelId = getNotificationChannelId(alertType, threatType, overrideSound, context)

        // Get notification channel name
        var channelName = NotificationChannels.PRIMARY_ALERT_NOTIFICATION_CHANNEL_NAME

        // Secondary alert?
        if (alertType == AlertTypes.SECONDARY || alertType == AlertTypes.TEST_SECONDARY_SOUND) {
            channelName = NotificationChannels.SECONDARY_ALERT_NOTIFICATION_CHANNEL_NAME
        }
        else {
            // Threat type defined?
            if (threatType != null) {
                // Early Warning
                if (threatType == ThreatTypes.EARLY_WARNING) {
                    channelName = NotificationChannels.PRIMARY_ALERT_EARLY_WARNING_NOTIFICATION_CHANNEL_NAME
                }
                // Leave Shelter Alert
                else if (threatType == ThreatTypes.LEAVE_SHELTER) {
                    channelName = NotificationChannels.PRIMARY_ALERT_LEAVE_SHELTER_NOTIFICATION_CHANNEL_NAME
                }
            }
        }

        // Initialize channel (set high importance)
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)

        // Set dummy audio attributes (sound will be silent)
        val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

        // Set silent sound (play sound manually using MediaPlayer APIs to override silent mode)
        channel.setSound(SoundLogic.getAppSoundByResourceName("silent", context), audioAttributes)

        // Disable vibration (vibrate manually to override silent mode)
        channel.enableVibration(false)

        // Bypass do-not-disturb mode
        channel.setBypassDnd(true)

        // Enable lights
        channel.enableLights(true)

        // Display flashing red color on supported phones
        channel.lightColor = Color.RED

        // Create channel (does nothing if already exists)
        manager.createNotificationChannel(channel)

        // Configure builder to use channel ID
        if (builder != null) {
            builder.setChannelId(channelId)
        }
    }
}
