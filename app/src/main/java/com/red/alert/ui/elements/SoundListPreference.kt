package com.red.alert.ui.elements

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.preference.ListPreference
import android.provider.Settings
import android.util.AttributeSet

import com.red.alert.R
import com.red.alert.activities.settings.alerts.SecondaryAlerts
import com.red.alert.config.Sound
import com.red.alert.config.ThreatTypes
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.feedback.sound.SoundLogic
import com.red.alert.logic.notifications.Notifications
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.ui.notifications.AppNotifications
import com.red.alert.utils.caching.Singleton

class SoundListPreference(context: Context, set: AttributeSet?) : ListPreference(context, set) {
    companion object {
        private lateinit var mContext: Context
    }

    private var mSelectedItem = 0
    private lateinit var mEntries: Array<CharSequence>
    private lateinit var mEntryValues: Array<CharSequence>

    init {
        // Store activity context
        mContext = context
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        // Stop playing sounds
        AppNotifications.clearAll(mContext)
    }

    override fun showDialog(state: Bundle?) {
        super.showDialog(state)

        // Support for RTL languages
        RTLSupport.mirrorDialog(dialog, mContext)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        // Get sound names & resource values
        val entries = getEntries()
        val entryValues = getEntryValues()

        // Mis-configured?
        if (entries == null || entryValues == null) {
            throw IllegalStateException("ListPreference requires an entries array and an entryValues array.")
        }

        mEntries = entries
        mEntryValues = entryValues

        // Get currently selected item
        mSelectedItem = getSelectedSoundPosition()

        // Populate dialog with items and set preselected item
        builder.setSingleChoiceItems(entries, mSelectedItem,
                DialogInterface.OnClickListener { _, which ->
                    // Get notification manager
                    val notificationManager = mContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    // Get path to selected sound
                    val path = mEntryValues[which].toString()

                    // Determine channel ID (primary/secondary)
                    val alertSoundType = if (key == mContext.getString(R.string.secondarySoundPref)) AlertTypes.SECONDARY else AlertTypes.PRIMARY

                    // Determine threat type (primary/seconcdary)
                    var soundThreatType = if (mContext.javaClass.name == SecondaryAlerts::class.java.name) AlertTypes.TEST_SECONDARY_SOUND else AlertTypes.TEST_SOUND

                    // Early Warning sound selection?
                    if (key == mContext.getString(R.string.earlyWarningsSoundPref)) {
                        soundThreatType = ThreatTypes.EARLY_WARNING
                    }

                    // Leave Shelter sound selection?
                    if (key == mContext.getString(R.string.leaveShelterAlertsSoundPref)) {
                        soundThreatType = ThreatTypes.LEAVE_SHELTER
                    }

                    // Convert to final
                    val alertThreatType = soundThreatType

                    // Custom sound option selected?
                    if (path == Sound.CUSTOM_SOUND_NAME) {
                        // Delete (hide) old notification channel
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            notificationManager.deleteNotificationChannel(Notifications.getNotificationChannelId(alertSoundType, alertThreatType, "alarm1", mContext))
                        }

                        // Instruct user how to configure custom sound
                        AlertDialogBuilder.showGenericDialog(mContext.getString(R.string.selectCustomSound), mContext.getString(R.string.selectCustomSoundDesc), mContext.getString(R.string.okay), mContext.getString(R.string.notNow), true, dialog.context, DialogInterface.OnClickListener { _, buttonId ->
                            // Clicked okay?
                            if (buttonId == DialogInterface.BUTTON_POSITIVE) {
                                // Get channel ID by alert type
                                val channelId = Notifications.getNotificationChannelId(alertSoundType, alertThreatType, Sound.CUSTOM_SOUND_NAME, mContext)

                                // Ensure notification channel created
                                Notifications.setNotificationChannel(alertSoundType, alertThreatType, path, null, mContext)

                                // Open notification channel config to allow user to select custom sound
                                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                intent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                                intent.putExtra(Settings.EXTRA_APP_PACKAGE, mContext.packageName)

                                try {
                                    mContext.startActivity(intent)
                                }
                                catch (err: ActivityNotFoundException) {
                                    // On Android 7 and below, there is no option to set custom sound currently
                                    // As there is no notification channel support
                                    // Show error dialog
                                    AlertDialogBuilder.showGenericDialog(mContext.getString(R.string.error), err.message, mContext.getString(R.string.okay), null, false, mContext, null)
                                }

                                // Save custom sound selection
                                Singleton.getSharedPreferences(mContext).edit().putString(key, path).commit()

                                // Dismiss dialog if not null
                                if (dialog != null) {
                                    dialog.dismiss()
                                }
                            }
                            else {
                                // Clicked not now, stop playing custom sound
                                AppNotifications.clearAll(mContext)
                            }
                        })
                    }
                    else {
                        // Selected built-in sound
                        // Delete (hide) custom notification channel
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            notificationManager.deleteNotificationChannel(Notifications.getNotificationChannelId(alertSoundType, alertThreatType, Sound.CUSTOM_SOUND_NAME, mContext))
                        }
                    }

                    // Update selected item
                    mSelectedItem = which

                    // Get test type
                    var testAlertType = AlertTypes.TEST_SOUND

                    // Secondary notification?
                    if (mContext.javaClass.name == SecondaryAlerts::class.java.name) {
                        // Override type
                        testAlertType = AlertTypes.TEST_SECONDARY_SOUND
                    }

                    // Stop any previously-selected sound
                    SoundLogic.stopSound(mContext)

                    // Dispatch test notification
                    Notifications.notify(mContext, listOf(mContext.getString(R.string.appName)), testAlertType, alertThreatType, path, null)
                })

        // OK button
        builder.setPositiveButton(R.string.okay, DialogInterface.OnClickListener { _, _ ->
            // Get path to selected sound
            val selectedSoundResource = mEntryValues[mSelectedItem].toString()

            // Save selected sound
            Singleton.getSharedPreferences(mContext).edit().putString(key, selectedSoundResource).commit()
        })

        // Cancel button
        builder.setNegativeButton(android.R.string.cancel, this)
    }

    private fun getSelectedSoundPosition(): Int {
        // Get selected sound
        var soundFilePath: String? = Singleton.getSharedPreferences(mContext).getString(key, null)

        // Got one?
        if (soundFilePath != null) {
            // Remove prefix
            soundFilePath = soundFilePath.replace(Sound.APP_SOUND_PREFIX, "")

            // Get selected sound
            for (position in mEntryValues.indices) {
                // Do we have a match?
                if (mEntryValues[position] == soundFilePath) {
                    // Return item position
                    return position
                }
            }
        }

        // No item selected
        return -1
    }
}
