package com.red.alert.config

object LocationAlerts {
    // Max distance (in km) that can be configured for location-based alerts
    @JvmField
    var MAX_DISTANCE_KM = 50

    // Max polling frequency for location-based alerts (higher value is less reliable but conserves more battery)
    @JvmField
    var MAX_FREQUENCY_MINUTES = 60
}
