package com.red.alert.activities.settings

import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.provider.Settings
import android.view.MenuItem

import com.red.alert.R
import com.red.alert.activities.settings.alerts.EarlyWarnings
import com.red.alert.activities.settings.alerts.LeaveShelterAlerts
import com.red.alert.activities.settings.alerts.LocationAlerts
import com.red.alert.activities.settings.alerts.SecondaryAlerts
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.ui.activities.AppCompatPreferenceActivity
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.ui.elements.SliderPreference
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.utils.feedback.Volume
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.ui.NavbarUtil

import me.pushy.sdk.Pushy
import me.pushy.sdk.config.PushyForegroundService
import me.pushy.sdk.util.PushyServiceManager

class Advanced : AppCompatPreferenceActivity() {
    private lateinit var mEarlyWarnings: Preference
    private lateinit var mLeaveShelterAlerts: Preference
    private lateinit var mLocationAlerts: Preference
    private lateinit var mSecondaryAlerts: Preference
    private lateinit var mVolumeSelection: SliderPreference

    private lateinit var mAlertPopup: CheckBoxPreference
    private lateinit var mForegroundService: CheckBoxPreference

    companion object {
        private const val ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE = 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Load UI elements
        initializeUI()

        // Volume keys should control alert volume
        Volume.setVolumeKeysAction(this)
    }

    override fun attachBaseContext(base: Context) {
        // Reapply locale
        Localization.overridePhoneLocale(base)
        super.attachBaseContext(base)
    }

    override fun onResume() {
        super.onResume()

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)
    }

    private fun initializeUI() {
        // Allow click on home button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // Load settings from XML (there is no non-deprecated way to do it on API level 7)
        addPreferencesFromResource(R.xml.settings_advanced)

        // Fix nav bar color and styling
        NavbarUtil.fixPreferenceActivityNavbarColor(this)

        // Cache resource IDs
        mLocationAlerts = findPreference(getString(R.string.locationPref))
        mEarlyWarnings = findPreference(getString(R.string.earlyWarningsPref))
        mLeaveShelterAlerts = findPreference(getString(R.string.leaveShelterAlertsPref))
        mSecondaryAlerts = findPreference(getString(R.string.secondaryPref))
        mVolumeSelection = findPreference(getString(R.string.volumePref)) as SliderPreference
        mAlertPopup = findPreference(getString(R.string.alertPopupPref)) as CheckBoxPreference
        mForegroundService = findPreference(getString(R.string.foregroundServicePref)) as CheckBoxPreference

        // Set up listeners
        initializeListeners()
    }

    private fun initializeListeners() {
        // Volume selection
        mVolumeSelection.setSeekBarChangedListener(SliderPreference.onSeekBarChangedListener { value ->
            // Get slider percent
            val percent = (AppPreferences.getPrimaryAlertVolume(this@Advanced, value) * 100).toInt()

            // Generate summary
            "${getString(R.string.volumeDesc)}\r\n($percent%)"
        })

        // Set up secondary alerts click listener
        mSecondaryAlerts.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Prepare new intent
            val secondaryAlerts = Intent()

            // Set class
            secondaryAlerts.setClass(this@Advanced, SecondaryAlerts::class.java)

            // Take user to Secondary Alerts settings page
            startActivity(secondaryAlerts)

            // Consume event
            true
        }

        // Set up early warnings click listener
        mEarlyWarnings.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Prepare new intent
            val earlyWarnings = Intent()

            // Set class
            earlyWarnings.setClass(this@Advanced, EarlyWarnings::class.java)

            // Take user to Early Warning settings page
            startActivity(earlyWarnings)

            // Consume event
            true
        }

        // Set up leave shelter alerts click listener
        mLeaveShelterAlerts.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Prepare new intent
            val leaveShelter = Intent()

            // Set class
            leaveShelter.setClass(this@Advanced, LeaveShelterAlerts::class.java)

            // Take user to Leave Shelter Alerts settings page
            startActivity(leaveShelter)

            // Consume event
            true
        }

        // Set up location alerts click listener
        mLocationAlerts.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Prepare new intent
            val locationAlerts = Intent()

            // Set class
            locationAlerts.setClass(this@Advanced, LocationAlerts::class.java)

            // Take user to Location Alerts settings page
            startActivity(locationAlerts)

            // Consume event
            true
        }

        // Alert popup checkbox listener
        mAlertPopup.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            // Android M+: Check if we have permission to draw over other apps
            if (newValue as Boolean && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@Advanced)) {
                // Show permission request dialog
                AlertDialogBuilder.showGenericDialog(getString(R.string.grantOverlayPermission), getString(R.string.grantOverlayPermissionInstructions), getString(R.string.okay), getString(R.string.notNow), true, this@Advanced, DialogInterface.OnClickListener { _, which ->
                    // Clicked okay?
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        // Bring user to relevant settings activity to grant the app overlay permission
                        startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE)
                    }
                })

                // Tell Android *not* to persist new checkbox value
                return@OnPreferenceChangeListener false
            }

            // Tell Android to persist new checkbox value
            true
        }

        // Foreground service checkbox listener
        mForegroundService.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            // Cached Apps Freezer compatibility
            // Force foreground service for Google Pixel devices (Android 15+)
            if (Build.MANUFACTURER.lowercase().contains("google") && Build.MODEL.lowercase().contains("pixel") && Build.VERSION.SDK_INT >= 35) {
                // Show foreground service dialog
                showForegroundServiceDialog()

                // Toggling off not allowed
                return@OnPreferenceChangeListener false
            }

            // Stop existing service
            PushyServiceManager.stop(this@Advanced)

            // Toggle foreground service
            Pushy.toggleForegroundService(value as Boolean, this@Advanced)

            // Wait 2 seconds
            Handler().postDelayed({
                // Start service again
                PushyServiceManager.start(this@Advanced)
            }, 2000)

            // Enabled?
            if (value) {
                // Show foreground service dialog
                showForegroundServiceDialog()
            }

            // Tell Android to persist new checkbox value
            true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Check item ID
        when (item.itemId) {
            // Home button?
            android.R.id.home -> onBackPressed()
        }

        return super.onOptionsItemSelected(item)
    }

    private fun showForegroundServiceDialog() {
        // Android O and newer required for notification channels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Show dialog instructing user on how to hide the Pushy foreground service notification
            AlertDialogBuilder.showGenericDialog(getString(R.string.hidePushyForegroundNotification), getString(R.string.hidePushyForegroundNotificationInstructions), getString(R.string.okay), getString(R.string.notNow), true, this@Advanced, DialogInterface.OnClickListener { _, which ->
                // Clicked okay?
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Background thread
                    Thread {
                        // Get notification manager
                        val notificationManager = getSystemService(NotificationManager::class.java)

                        // Wait some time for notification channel to be created
                        while (notificationManager.getNotificationChannel(PushyForegroundService.FOREGROUND_NOTIFICATION_CHANNEL) == null) {
                            try {
                                Thread.sleep(200)
                            }
                            catch (exc: Exception) {
                                // Ignore exceptions
                            }
                        }

                        // Activity destroyed?
                        if (isDestroyed || isFinishing) {
                            return@Thread
                        }

                        // Open notification channel config to allow user to easily disable the notification channel
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        intent.putExtra(Settings.EXTRA_CHANNEL_ID, PushyForegroundService.FOREGROUND_NOTIFICATION_CHANNEL)
                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        startActivity(intent)
                    }.start()
                }
            })
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // User returned from manage overlay permission activity?
        if (requestCode == ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE) {
            // Android M+: Check if we have permission to draw over other apps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this@Advanced)) {
                mAlertPopup.isChecked = true
            }
        }
    }
    }
