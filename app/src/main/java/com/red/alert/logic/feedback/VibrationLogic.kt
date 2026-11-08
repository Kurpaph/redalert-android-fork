package com.red.alert.logic.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

import com.red.alert.R
import com.red.alert.config.Vibration
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.feedback.sound.SoundLogic
import com.red.alert.utils.caching.Singleton

object VibrationLogic {
    @JvmStatic
    fun shouldVibrate(alertType: String, context: Context): Boolean {
        // Testing sounds (or system alert)?
        if (alertType == AlertTypes.TEST_SOUND || alertType == AlertTypes.TEST_SECONDARY_SOUND || alertType == AlertTypes.SYSTEM) {
            return false
        }

        // Get the audio manager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Phone is on silent?
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            // Can't override silent mode?
            if (!SoundLogic.shouldOverrideSilentMode(alertType, context)) {
                return false
            }
        }

        // All good
        return true
    }

    @JvmStatic
    fun isVibrationEnabled(alertType: String, context: Context): Boolean {
        // Get enabled / disabled setting
        var enabled = Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.vibratePref), true)

        // Secondary alert?
        if (alertType == AlertTypes.SECONDARY) {
            enabled = Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.secondaryVibratePref), true)
        }

        // Return value
        return enabled
    }

    @JvmStatic
    fun stopVibration(context: Context) {
        // Get vibration service
        val vibratorService = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Cancel any current vibrations
        vibratorService.cancel()
    }

    @JvmStatic
    fun issueVibration(alertType: String, context: Context) {
        // Enable vibration?
        if (!isVibrationEnabled(alertType, context)) {
            return
        }

        // Should we vibrate?
        if (!shouldVibrate(alertType, context)) {
            return
        }

        // Get vibration service
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Check if vibration is supported by the device
        if (vibrator.hasVibrator()) {
            // Special code for Android O and up to vibrate with the app in the background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Set alarm  audio attributes
                val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()

                // Create vibration effect
                val effect = VibrationEffect.createWaveform(Vibration.VIBRATION_PATTERN, -1)

                // Vibrate the device
                vibrator.vibrate(effect, audioAttributes)
            } else {
                // Before Android O, things were easier
                vibrator.vibrate(Vibration.VIBRATION_PATTERN, -1)
            }
        }
    }
}
