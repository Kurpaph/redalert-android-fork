package com.red.alert.config

object NotificationChannels {
    // Primary alerts notification channel config
    const val PRIMARY_ALERT_NOTIFICATION_CHANNEL_ID = "redalert"
    const val PRIMARY_ALERT_NOTIFICATION_CHANNEL_NAME = "Alerts"

    const val PRIMARY_ALERT_EARLY_WARNING_NOTIFICATION_CHANNEL_ID = "redalert_early_warnings"
    const val PRIMARY_ALERT_EARLY_WARNING_NOTIFICATION_CHANNEL_NAME = "Early Warnings"

    const val PRIMARY_ALERT_LEAVE_SHELTER_NOTIFICATION_CHANNEL_ID = "redalert_leave_shelter"
    const val PRIMARY_ALERT_LEAVE_SHELTER_NOTIFICATION_CHANNEL_NAME = "Leave Shelter Alerts"

    // Secondary alerts notification channel config
    const val SECONDARY_ALERT_NOTIFICATION_CHANNEL_ID = "redalert_secondary"
    const val SECONDARY_ALERT_NOTIFICATION_CHANNEL_NAME = "Secondary Alerts"

    // Custom sound notification channel suffix
    const val CUSTOM_SOUND_NOTIFICATION_CHANNEL_SUFFIX = "_custom"

    // Location service foreground notification channel
    const val LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_ID = "redalert_location_v2"
    const val LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_OLD_ID = "redalert_location"
    const val LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_NAME = "Location Service"

    // Old channels that should be deleted due to resource ID bug
    const val OLD_PRIMARY_ALERT_NOTIFICATION_CHANNEL_ID = "redalert"
    const val OLD_SECONDARY_ALERT_NOTIFICATION_CHANNEL_ID = "redalert_secondary"
}
