package com.red.alert.logic.feedback.sound

import android.content.Context
import android.media.AudioManager
import android.util.Log

import com.red.alert.config.Logging
import com.red.alert.config.Sound
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.settings.AppPreferences

object VolumeLogic {
    @JvmStatic
    fun setStreamVolume(alertType: String, context: Context) {
        // Get the audio manager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Get max possible volume
        val requestedVolume = VolumeLogic.getNotificationVolume(alertType, context)

        try {
            // Set volume to desired level
            audioManager.setStreamVolume(Sound.STREAM_TYPE, requestedVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
        }
        catch (exc: SecurityException) {
            // Log SecurityException: Not allowed to change Do Not Disturb state
            Log.d(Logging.TAG, "Error changing alarm stream volume", exc)
        }
    }

    @JvmStatic
    fun getNotificationVolume(alertType: String, context: Context): Int {
        // Get default volume multiplier
        var volumePercent = AppPreferences.getPrimaryAlertVolume(context, -1f)

        // Secondary alert?
        if (alertType == AlertTypes.SECONDARY || alertType == AlertTypes.TEST_SECONDARY_SOUND) {
            volumePercent = AppPreferences.getSecondaryAlertVolume(context, -1f)
        }

        // Multiply by max volume
        return (getMaxNotificationVolume(context) * volumePercent).toInt()
    }

    @JvmStatic
    fun getMaxNotificationVolume(context: Context): Int {
        // Get the audio manager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Get max possible volume
        return audioManager.getStreamMaxVolume(Sound.STREAM_TYPE)
    }
}
