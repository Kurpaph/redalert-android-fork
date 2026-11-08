package com.red.alert.logic.feedback.sound

import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log

import com.red.alert.R
import com.red.alert.config.Logging
import com.red.alert.config.Sound
import com.red.alert.config.ThreatTypes
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.feedback.VibrationLogic
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.formatting.StringUtils

import java.util.Collections

object SoundLogic {
    // Use Collections.synchronizedList to avoid ConcurrentModificationException
    private val mPlayers: MutableList<Player> = Collections.synchronizedList(ArrayList())

    private var mPreviousRingerMode = 0
    private var mRingerModeChanged = false

    @JvmStatic
    fun shouldPlayAlertSound(alertType: String?, context: Context): Boolean {
        // No type?
        if (StringUtils.stringIsNullOrEmpty(alertType)) {
            return false
        }

        // No sound for system message
        if (alertType == AlertTypes.SYSTEM) {
            return false
        }

        // Get the audio manager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Phone is on silent?
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            // Override silent mode not allowed?
            if (!shouldOverrideSilentMode(alertType!!, context)) {
                return false
            }
        }

        // Play sound
        return true
    }

    @JvmStatic
    fun getSoundStreamType(context: Context): Int {
        // Get the audio manager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // In call?
        if (audioManager.mode == AudioManager.MODE_IN_CALL) {
            // Play in tiny speaker
            // I think we disabled this because it's played in that speaker already by default
            // return AudioManager.STREAM_VOICE_CALL;
        }

        // Return default stream type
        return Sound.STREAM_TYPE
    }

    @JvmStatic
    fun getAlertSoundName(alertType: String, threatType: String?, overrideSound: String?, context: Context): String? {
        // Override sound selection?
        if (overrideSound != null) {
            return overrideSound
        }

        // Get sound preference name (based on alert type being primary/secondary)
        val soundPreference = getSoundPreference(alertType, threatType, context)

        // Return selected sound file name
        return Singleton.getSharedPreferences(context).getString(soundPreference, getDefaultSound(alertType, threatType, context))
    }

    private fun getAlertSoundURI(uri: String?, context: Context): Uri? {
        // No URI? Nothing to do here
        if (StringUtils.stringIsNullOrEmpty(uri)) {
            return null
        }

        var soundUri = uri!!

        // Support for old alarms (without the scheme identifier)
        if (!soundUri.contains(Sound.SCHEME_URI_IDENTIFIER)) {
            return getAppSoundByResourceName(soundUri, context)
        }

        // Scheme without an actual file?
        if (soundUri == Sound.APP_SOUND_PREFIX) {
            return null
        }

        // com.red.alert:// scheme support
        val soundPrefix = Sound.APP_SOUND_PREFIX

        // Does it start with this prefix?
        if (soundUri.startsWith(soundPrefix)) {
            // Extract sound resource name without scheme
            soundUri = soundUri.substring(soundUri.indexOf(soundPrefix) + soundPrefix.length)

            // Convert to resource URI
            return getAppSoundByResourceName(soundUri, context)
        }

        // Try to parse the URI
        return Uri.parse(soundUri)
    }

    @JvmStatic
    fun getAppSoundByResourceName(resourceName: String, context: Context): Uri {
        // Get path to sound file
        return Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/$resourceName")
    }

    private fun getDefaultSound(alertType: String, threatType: String?, context: Context): String {
        // By default, regular sound
        var soundDefault = context.getString(R.string.defaultSound)

        // Secondary alert?
        if (alertType == AlertTypes.SECONDARY) {
            // Set new value
            soundDefault = context.getString(R.string.defaultSecondarySound)
        }

        // Early warning alert?
        if (alertType == AlertTypes.PRIMARY && threatType != null && threatType == ThreatTypes.EARLY_WARNING) {
            // Use early warning default sound
            soundDefault = context.getString(R.string.defaultEarlyWarningSound)
        }

        // Leave shelter alert?
        if (alertType == AlertTypes.PRIMARY && threatType != null && threatType == ThreatTypes.LEAVE_SHELTER) {
            // Use leave shelter default sound
            soundDefault = context.getString(R.string.defaultLeaveShelterSound)
        }

        // Return default sound
        return soundDefault
    }

    private fun getSoundPreference(alertType: String, threatType: String?, context: Context): String {
        // By default, primary preference
        var soundPreference = context.getString(R.string.soundPref)

        // Secondary alert?
        if (alertType == AlertTypes.SECONDARY) {
            // Set new pref
            soundPreference = context.getString(R.string.secondarySoundPref)
        }

        // Early warning alert?
        if (alertType == AlertTypes.PRIMARY && threatType != null && threatType == ThreatTypes.EARLY_WARNING) {
            // Use early warning sound pref
            soundPreference = context.getString(R.string.earlyWarningsSoundPref)
        }

        // Leave shelter alert?
        if (alertType == AlertTypes.PRIMARY && threatType != null && threatType == ThreatTypes.LEAVE_SHELTER) {
            // Use leave shelter alerts sound pref
            soundPreference = context.getString(R.string.leaveShelterAlertsSoundPref)
        }

        // Return default pref
        return soundPreference
    }

    @JvmStatic
    fun shouldOverrideSilentMode(alertType: String, context: Context): Boolean {
        // Testing?
        if (alertType == AlertTypes.TEST_SOUND || alertType == AlertTypes.TEST_SECONDARY_SOUND) {
            return true
        }

        // Get enabled / disabled setting
        var overrideSilentMode = Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.silentOverridePref), true)

        // Secondary alert?
        if (alertType == AlertTypes.SECONDARY
                || alertType == AlertTypes.TEST_SECONDARY_SOUND) {
            overrideSilentMode = Singleton.getSharedPreferences(context).getBoolean(context.getString(R.string.secondarySilentOverridePref), true)
        }

        // Return setting value
        return overrideSilentMode
    }

    private fun isSoundTypeCurrentlyPlaying(soundType: String): Boolean {
        // Avoid ConcurrentModificationException
        synchronized(mPlayers) {
            // Traverse players
            for (player in mPlayers) {
                try {
                    // Still playing?
                    if (player.isPlaying) {
                        // Check sound type for match
                        if (player.soundType == soundType) {
                            return true
                        }
                    }
                } catch (exc: Exception) {
                    // Ignore exceptions and return false
                }
            }
        }

        // Sound type not currently playing
        return false
    }

    @JvmStatic
    fun playSound(alertType: String, threatType: String?, overrideSound: String?, context: Context) {
        // Should we play it?
        if (!shouldPlayAlertSound(alertType, context)) {
            return
        }

        // Avoid playing same sound type currently
        if (isSoundTypeCurrentlyPlaying(alertType)) {
            return
        }

        // Override volume (also to set the user's chosen volume)
        VolumeLogic.setStreamVolume(alertType, context)

        // Get alert sound name
        val alertSoundName = getAlertSoundName(alertType, threatType, overrideSound, context)

        // Invalid sound name (or custom sound selected)?
        if (StringUtils.stringIsNullOrEmpty(alertSoundName) || alertSoundName == Sound.CUSTOM_SOUND_NAME) {
            return
        }

        // Convert to resource URI
        val alertSoundURI = getAlertSoundURI(alertSoundName, context)

        // Use background thread to avoid ANR in MediaPlayer.setDataSource()
        Thread {
            // Play sound
            playSoundURI(alertSoundURI, alertType, context)
        }.start()
    }

    @JvmStatic
    fun stopSound(context: Context) {
        // Avoid ConcurrentModificationException
        synchronized(mPlayers) {
            // Traverse players
            for (player in mPlayers) {
                try {
                    // Still playing?
                    if (player.isPlaying) {
                        // Stop playing
                        player.stop()

                        // Reset media player
                        player.reset()
                    }

                    // Release resources associated
                    player.release()
                }
                catch (exc: Exception) {
                    // Do nothing
                }
            }

            // Clear list
            mPlayers.clear()
        }

        // Stop vibration
        VibrationLogic.stopVibration(context)

        // Restore ringer mode if necessary
        SoundLogic.restoreRingerModeIfOverridden(context)
    }

    private fun playSoundURI(alarmSoundUi: Uri?, soundType: String, context: Context) {
        // No URI?
        if (alarmSoundUi == null) {
            return
        }

        // Create new MediaPlayer
        val player = Player()

        // Set sound type
        player.soundType = soundType

        // Wake up processor
        player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)

        // Set stream type
        player.setAudioStreamType(getSoundStreamType(context))

        try {
            // Set URI data source
            player.setDataSource(context, alarmSoundUi)

            // Prepare media player
            player.prepare()

            // Actually start playing
            player.start()
        }
        catch (exc: Exception) {
            // Log it
            Log.e(Logging.TAG, "Media player preparation failed", exc)

            // Stop execution
            return
        }

        // Avoid ConcurrentModificationException
        synchronized(mPlayers) {
            // Add player to list of players
            mPlayers.add(player)
        }
    }

    @JvmStatic
    fun overrideSilentRingerMode(alertType: String, context: Context) {
        // Check if sound needs to be played for this alert type
        if (!shouldPlayAlertSound(alertType, context)) {
            return
        }

        // Shouldn't override silent mode?
        if (!SoundLogic.shouldOverrideSilentMode(alertType, context)) {
            return
        }

        // Check if device is Samsung and Android R or above
        if (!isSamsungDeviceRequiringSilentModeOverride()) {
            return
        }

        // Android R (11) and up only
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        // Get instance of NotificationManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Checks if notification policy access permission has not been granted (can't modify ringer mode otherwise)
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            return
        }

        // Get audio manager instance
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Ringer mode is already set to normal?
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            // Nothing to do here
            return
        }

        // Save current ringer mode (silent/vibrate) for later
        mPreviousRingerMode = audioManager.ringerMode

        // Set ringer mode to normal
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        // Restore it later
        mRingerModeChanged = true
    }

    @JvmStatic
    fun restoreRingerModeIfOverridden(context: Context) {
        // Check if ringer mode was changed
        if (!mRingerModeChanged) {
            return
        }

        // Get instance of audio manager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            // Restore previous ringer mode
            audioManager.ringerMode = mPreviousRingerMode
        }
        catch (exc: SecurityException) {
            // Log it
            Log.e(Logging.TAG, "Restoring ringer mode failed", exc)
        }

        // Reset flag
        mRingerModeChanged = false
    }

    @JvmStatic
    fun isSamsungDeviceRequiringSilentModeOverride(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    /* Declare custom MediaPlayer class to store alert sound type */
    internal class Player : MediaPlayer() {
        var soundType: String? = null
    }
}
