package com.red.alert.logic.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.util.Log

import com.red.alert.R
import com.red.alert.activities.Main
import com.red.alert.config.Logging
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.metadata.LocationData

object AppPreferences {
    @JvmStatic
    fun getNotificationsEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.enabledPref), true)
    }

    @JvmStatic
    fun getLocationAlertsEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.locationAlertsPref), false)
    }

    @JvmStatic
    fun getSecondaryNotificationsEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.secondaryEnabledPref), false)
    }

    @JvmStatic
    fun getEarlyWarningNotificationsEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.earlyWarningsEnabledPref), true)
    }

    @JvmStatic
    fun getLeaveShelterNotificationsEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.leaveShelterAlertsEnabledPref), true)
    }

    @JvmStatic
    fun getSmartLeaveShelterAlertsEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.smartLeaveShelterAlertsEnabledPref), false)
    }

    @JvmStatic
    fun getTutorialDisplayed(context: Context): Boolean {
        // No regions/cities selected?
        if (AppPreferences.getNotificationsEnabled(context) && AppPreferences.getSubscriptions(context).size == 0) {
            return false
        }

        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.tutorialPref), false)
    }

    @JvmStatic
    fun getPopupEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.alertPopupPref), false)
    }

    @JvmStatic
    fun getSecondaryPopupEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.secondaryAlertPopupPref), false)
    }

    @JvmStatic
    fun getForegroundServiceEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.foregroundServicePref), false)
    }

    @JvmStatic
    fun getWakeScreenEnabled(context: Context): Boolean {
        // Get saved preference
        return Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.wakeScreenPref), true)
    }

    @JvmStatic
    fun setTutorialDisplayed(context: Context) {
        // Update stored value
        Singleton.getSharedPreferences(context).edit().putBoolean(context.getString(R.string.tutorialPref), true).commit()
    }

    @JvmStatic
    fun getLastSubscribedTimestamp(context: Context): Long {
        // Fetch last subscribed timestamp
        return Singleton.getSharedPreferences(context).getLong(context.getString(R.string.lastSubscribed), 0)
    }

    @JvmStatic
    fun updateLastSubscribedTimestamp(timestamp: Long, context: Context) {
        // Update last subscribed timestamp
        Singleton.getSharedPreferences(context).edit().putLong(context.getString(R.string.lastSubscribed), timestamp).commit()
    }

    @JvmStatic
    fun getRecentAlertsCutoffTimestamp(context: Context): Long {
        // Fetch recent alerts cutoff timestamp
        return Singleton.getSharedPreferences(context).getLong(context.getString(R.string.recentAlertsCutoff), 0)
    }

    @JvmStatic
    fun updateRecentAlertsCutoffTimestamp(timestamp: Long, context: Context) {
        // Update recent alerts cutoff timestamp
        Singleton.getSharedPreferences(context).edit().putLong(context.getString(R.string.recentAlertsCutoff), timestamp).commit()
    }

    @JvmStatic
    fun setCityLastAlertTime(city: String, timestamp: Long, context: Context) {
        try {
            // Update last alert timestamp for this city
            Singleton.getSharedPreferences(context).edit().putLong(city, timestamp).apply()
        }
        catch (exc: OutOfMemoryError) {
            // Log for debug
            Log.e(Logging.TAG, "Failed to set city last alert time", exc)
        }
    }

    @JvmStatic
    fun getCityLastAlert(city: String, context: Context): Long {
        // Get last alert timestamp for this city
        return Singleton.getSharedPreferences(context).getLong(city, 0)
    }

    @JvmStatic
    fun getPrimaryAlertVolume(context: Context, overrideValue: Float): Float {
        // Override?
        if (overrideValue != -1f) {
            return overrideValue
        }

        // Get stored value
        return Singleton.getSharedPreferences(context).getFloat(context.getString(R.string.volumePref), 1.0f)
    }

    @JvmStatic
    fun getSecondaryAlertVolume(context: Context, overrideValue: Float): Float {
        // Override?
        if (overrideValue != -1f) {
            return overrideValue
        }

        // Get stored value
        return Singleton.getSharedPreferences(context).getFloat(context.getString(R.string.secondaryVolumePref), 1.0f)
    }

    @JvmStatic
    fun getSubscriptions(context: Context): List<String> {
        // Merged list of cities and regions
        var subscriptions: MutableList<String> = ArrayList()

        // Check if notifications disabled
        if (!getNotificationsEnabled(context)) {
            return subscriptions
        }

        // Location alerts enabled?
        if (AppPreferences.getLocationAlertsEnabled(context)) {
            // Subscribe to all and check proximity client-side
            subscriptions.add("all")
        }

        // Get user's selected primary zones
        val selectedZones = Singleton.getSharedPreferences(context).getString(context.getString(R.string.selectedZonesPref), context.getString(R.string.none))

        // Anything selected?
        if (!StringUtils.stringIsNullOrEmpty(selectedZones)) {
            // Explode selected zones into array and push into primarySubs
            subscriptions.addAll(LocationData.getEnglishZoneTopicNames(LocationData.explodePSV(selectedZones), context))
        } else {
            // Empty value means all regions
            subscriptions.add("all")
        }

        // Get user's selected cities
        val selectedCities = Singleton.getSharedPreferences(context).getString(context.getString(R.string.selectedCitiesPref), context.getString(R.string.none))

        // Anything selected?
        if (!StringUtils.stringIsNullOrEmpty(selectedCities)) {
            // Explode selected cities into array and push into primarySubs
            subscriptions.addAll(LocationData.getEnglishCityTopicNames(LocationData.explodePSV(selectedCities), context))

        } else {
            // Empty value means all cities
            subscriptions.add("all")
        }

        // Check if secondary notifications enabled
        if (getSecondaryNotificationsEnabled(context)) {
            // Get user's secondary cities
            val secondaryCities = Singleton.getSharedPreferences(context).getString(context.getString(R.string.selectedSecondaryCitiesPref), context.getString(R.string.none))

            // Anything selected?
            if (!StringUtils.stringIsNullOrEmpty(secondaryCities)) {
                // Explode selected cities into array and push into primarySubs
                subscriptions.addAll(LocationData.getEnglishCityTopicNames(LocationData.explodePSV(secondaryCities), context))
            } else {
                // Empty value means all cities
                subscriptions.add("all")
            }
        }

        // Remove duplicates
        subscriptions = cleanSubscriptions(subscriptions)

        // All done
        return subscriptions
    }

    private fun cleanSubscriptions(subscriptions: MutableList<String>): MutableList<String> {
        // Traverse items
        for (item in subscriptions) {
            // "None" selected?
            if (item == "none") {
                return ArrayList()
            }
        }

        // Remove duplicate entries
        val set: Set<String> = HashSet(subscriptions)

        // Clear and only add items from set
        subscriptions.clear()
        subscriptions.addAll(set)

        // Return original list
        return subscriptions
    }

    @JvmStatic
    fun deleteOldSharedPreferences(context: Context) {
        try {
            // Get shared preferences
            val prefs = Singleton.getSharedPreferences(context)

            // Get all preferences as a Map
            val all: Map<String, *> = prefs.all

            // Create editor
            val editor = prefs.edit()

            // Get current time
            val nowSeconds = System.currentTimeMillis() / 1000L

            // Calculate 1 day in seconds
            val twentyFourHoursSeconds = 24L * 60 * 60

            // Count deletions
            var deletedCount = 0

            // Traverse all
            for (entry in all.entries) {
                // Get key & value
                val key = entry.key
                val value = entry.value

                // Case 1: Timestamp inside key (after last dash)
                val dashIndex = key.lastIndexOf("-")

                // Found a dash?
                if (dashIndex != -1) {
                    // Get timestamp
                    val timestampPart = key.substring(dashIndex + 1)

                    try {
                        // Parse into long
                        val keyTimestamp = timestampPart.toLong()

                        // Get seconds ago
                        val age = nowSeconds - keyTimestamp

                        // More than 24 hours passed?
                        if (age > twentyFourHoursSeconds) {
                            // Delete old preference
                            editor.remove(key)
                            deletedCount++
                        }
                    } catch (ignored: NumberFormatException) {
                        // Not a timestamp in key → ignore
                    }
                }

                // Case 2: Key contains Hebrew AND value is Unix timestamp
                else if (containsHebrew(key)) {
                    // Get value timestamp
                    val valueTimestamp: Long

                    try {
                        // Should be a Long
                        if (value is Long) {
                            valueTimestamp = value
                        } else {
                            continue
                        }

                        // Get seconds ago
                        val age = nowSeconds - valueTimestamp

                        // 10 digits and more than 24 hours passed?
                        if (valueTimestamp.toString().length == 10 &&
                                age > twentyFourHoursSeconds) {
                            // Delete old preference
                            editor.remove(key)
                            deletedCount++
                        }
                    } catch (ignored: NumberFormatException) {
                        // Not a timestamp in key → ignore
                    }
                }
            }

            // Save changes (async)
            editor.apply()

            // Log for debug
            Log.d(Logging.TAG, "Old SharedPreference keys deleted: $deletedCount")
        }
        catch (exc: Exception) {
            // Log for debug
            Log.e(Logging.TAG, "Failed to delete old SharedPreferences", exc)
        }
    }

    private fun containsHebrew(text: String): Boolean {
        // Traverse string
        for (i in 0 until text.length) {
            // Get current char
            val c = text[i]

            // Hebrew Unicode block
            if (c.code >= 0x0590 && c.code <= 0x05FF) {
                return true
            }
        }

        // No Hebrew found
        return false
    }
}
