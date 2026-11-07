package com.red.alert.config

object Alerts {
    // Date format for recent alerts display
    const val DATE_FORMAT = "HH:mm:ss"

    // Time to wait before calling finish() on AlertPopup after POST_IMPACT_WAIT_MINUTES passed
    const val ALERT_POPUP_DONE_PADDING = 10

    // Time that must pass (in seconds) in between alerts for the same zone (to avoid duplicate alerts)
    const val DUPLICATE_ALERTS_PADDING_TIME = 60 * 2

    // Max time after a go-to-shelter alert that the app should play a sound for Leave Shelter alerts (to avoid waking up sleeping civilians)
    const val LEAVE_SHELTER_ALERTS_SOUND_MAX_CUTOFF_TIME = 60 * 30
}
