package com.red.alert.activities.settings.alerts

import android.app.ProgressDialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.provider.Settings
import android.util.Log
import android.view.MenuItem

import com.red.alert.R
import com.red.alert.activities.Map
import com.red.alert.config.Logging
import com.red.alert.config.NotificationChannels
import com.red.alert.config.ThreatTypes
import com.red.alert.logic.communication.broadcasts.LocationAlertsEvents
import com.red.alert.logic.location.LocationLogic
import com.red.alert.logic.push.PushManager
import com.red.alert.logic.services.ServiceManager
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.model.Alert
import com.red.alert.services.location.LocationService
import com.red.alert.ui.activities.AppCompatPreferenceActivity
import com.red.alert.ui.compatibility.ProgressDialogCompat
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.ui.elements.SliderPreference
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.utils.backend.RedAlertAPI
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.communication.Broadcasts
import com.red.alert.utils.feedback.Volume
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.integration.GooglePlayServices
import com.red.alert.utils.localization.DateTime
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.metadata.LocationData
import com.red.alert.utils.threading.AsyncTaskAdapter
import com.red.alert.utils.ui.NavbarUtil

class LocationAlerts : AppCompatPreferenceActivity() {
    private lateinit var mNearbyCities: Preference
    private lateinit var mFrequency: SliderPreference
    private lateinit var mMaxDistance: SliderPreference
    private lateinit var mLocationAlerts: CheckBoxPreference

    private val mBroadcastListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Got new location?
        if (key.equals(LocationAlertsEvents.LOCATION_RECEIVED, ignoreCase = true)) {
            // Refresh setting summaries with new values
            refreshSummaries()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Load UI elements
        initializeUI()

        // Must have Google Play Services for this to work
        verifyGooglePlayServicesAvailable()

        // Volume keys should control alert volume
        Volume.setVolumeKeysAction(this)
    }

    private fun verifyGooglePlayServicesAvailable() {
        // Show dialog
        if (!GooglePlayServices.isAvailable(this)) {
            // Show error
            AlertDialogBuilder.showGenericDialog(getString(R.string.error), getString(R.string.noGooglePlayServices), getString(R.string.okay), null, false, this@LocationAlerts, DialogInterface.OnClickListener { _, _ ->
                // No go
                finish()
            })
        }
    }

    override fun onPause() {
        super.onPause()

        // Unregister for broadcasts
        Broadcasts.unsubscribe(this, mBroadcastListener)
    }

    override fun onResume() {
        super.onResume()

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Location alerts enabled and permission not granted?
        if (mLocationAlerts.isChecked && !LocationLogic.isLocationAccessGranted(this)) {
            // Disable location alerts
            mLocationAlerts.isChecked = false

            // Stop the location service
            ServiceManager.stopLocationService(this@LocationAlerts)

            // Request permission via dialog
            LocationLogic.showLocationAccessRequestDialog(this)
        }

        // Register for broadcasts
        Broadcasts.subscribe(this, mBroadcastListener)

        // Refresh nearby cities display
        refreshSummaries()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)
    }

    override fun attachBaseContext(base: Context) {
        // Reapply locale
        Localization.overridePhoneLocale(base)
        super.attachBaseContext(base)
    }

    private fun initializeUI() {
        // Allow click on home button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // Load settings from XML (there is no non-deprecated way to do it on API level 7)
        addPreferencesFromResource(R.xml.settings_location_alerts)

        // Fix nav bar color and styling
        NavbarUtil.fixPreferenceActivityNavbarColor(this)

        // Cache resource IDs
        mLocationAlerts = findPreference(getString(R.string.locationAlertsPref)) as CheckBoxPreference
        mFrequency = findPreference(getString(R.string.gpsFrequencyPref)) as SliderPreference
        mMaxDistance = findPreference(getString(R.string.maxDistancePref)) as SliderPreference
        mNearbyCities = findPreference(getString(R.string.nearbyCitiesPref))

        // Set initial value
        refreshSummaries()

        // Set up listeners
        initializeListeners()
    }

    private fun initializeListeners() {
        // Max distance changed
        mMaxDistance.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            // Wait until value is saved
            Handler().postDelayed({
                // Set initial value
                refreshSummaries()

                // Update location service foreground notification nearby cities display
                updateLocationService()
            }, 200)

            // Save value
            true
        }

        // Location alerts toggled
        mLocationAlerts.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            // Trying to enable location-based alerts?
            if (newValue as Boolean) {
                // Can we access the user's location?
                if (!LocationLogic.isLocationAccessGranted(this@LocationAlerts)) {
                    // Request permission via dialog
                    LocationLogic.showLocationAccessRequestDialog(this@LocationAlerts)

                    // Don't enable location alerts yet
                    return@OnPreferenceChangeListener false
                }

                // Start the location service
                ServiceManager.startLocationService(this@LocationAlerts)
            }
            else {
                // Stop the location service
                ServiceManager.stopLocationService(this@LocationAlerts)
            }

            // Update subscriptions on the server-side
            UpdateSubscriptionsAsync().execute()

            // Save new value
            true
        }

        // Max distance changed
        mMaxDistance.setSeekBarChangedListener(SliderPreference.onSeekBarChangedListener { currentValue ->
            // Generate a new summary with the selected value
            getMaxDistanceSummary(currentValue)
        })

        // Frequency changed - update dialog text
        mFrequency.setSeekBarChangedListener(SliderPreference.onSeekBarChangedListener { value ->
            // Generate a new summary with the selected value
            getFrequencySummary(value)
        })

        // Frequency saved
        mFrequency.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            // Wait until value is saved
            Handler().postDelayed({
                // Set initial value
                refreshSummaries()

                // Update location service polling interval
                updateLocationService()
            }, 200)

            // Save value
            true
        }

        // Nearby cities click listener
        mNearbyCities.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Get current location
            val location = LocationLogic.getCurrentLocation(this@LocationAlerts)

            // No location?
            if (location == null) {
                return@OnPreferenceClickListener false
            }

            // Get nearby cities
            val nearbyCities: List<String> = LocationData.getNearbyCities(location, this@LocationAlerts)

            // Prepare mock alerts list for map activity
            val mockAlerts: MutableList<Alert> = ArrayList()

            // Travers nearby cities
            for (city in nearbyCities) {
                // Create mock alert object for map display
                val alert = Alert()

                // Set mock alert params
                alert.city = city
                alert.date = DateTime.getUnixTimestamp()
                alert.threat = ThreatTypes.NEARBY_CITIES_DISPLAY

                // Add to list of mock alerts
                mockAlerts.add(alert)
            }

            // Create new intent
            val map = Intent()

            // Set activity class
            map.setClass(this@LocationAlerts, Map::class.java)

            // Pass alerts directly to map activity
            Map.mAlerts = mockAlerts

            // Open map
            startActivity(map)

            // Click was handled
            true
        }
    }

    fun updateLocationService() {
        // Bind to our location service
        bindService(Intent(this, LocationService::class.java), object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
                // Convert binder to LocalBinder
                val localBinder = binder as LocationService.LocalBinder

                // Get service instance
                val service = localBinder.service

                // Apply new location service params
                service.updateLocationServiceParams()

                // Unbind fom service
                unbindService(this)
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                // Do nothing
            }
        }, Context.BIND_AUTO_CREATE)
    }

    private fun getFrequencySummary(overrideValue: Float): String {
        // Construct summary text
        return "${getString(R.string.gpsFrequencyDesc)}\r\n(${getString(R.string.every)} ${LocationLogic.getUpdateIntervalMinutes(this, overrideValue)} ${getString(R.string.minutes)})"
    }

    private fun getMaxDistanceSummary(overrideValue: Float): String {
        // Construct summary text
        return "${getString(R.string.maxDistanceDesc)}\r\n(${LocationLogic.getMaxDistanceKilometers(this, overrideValue)} ${getString(R.string.kilometer)})"
    }

    private fun refreshSummaries() {
        // Update summary text
        mMaxDistance.summary = Localization.localizeDigits(getMaxDistanceSummary(-1f), this)

        // Update summary text
        mFrequency.summary = Localization.localizeDigits(getFrequencySummary(-1f), this)

        // Get current location
        val location = LocationLogic.getCurrentLocation(this)

        // Result string
        var nearby: String?

        // Location alerts disabled?
        if (!mLocationAlerts.isChecked) {
            nearby = ""
        }
        // No recent location?
        else if (location == null) {
            // Show error
            nearby = getString(R.string.noLocation)
        }
        else {
            // Get nearby cities
            nearby = LocationData.getNearbyCityNames(location, this)

            // No results?
            if (StringUtils.stringIsNullOrEmpty(nearby)) {
                // Show error
                nearby = getString(R.string.noNearbyCities)
            }
        }

        // Update summary text
        mNearbyCities.summary = nearby

        // Disable/enable nearby cities setting based on checkbox value
        mNearbyCities.isEnabled = mLocationAlerts.isChecked
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Check item ID
        when (item.itemId) {
            // Home button?
            android.R.id.home -> onBackPressed()
        }

        return super.onOptionsItemSelected(item)
    }

    inner class UpdateSubscriptionsAsync : AsyncTaskAdapter<Int, String, Exception>() {
        val mLoading: ProgressDialog = ProgressDialogCompat.getStyledProgressDialog(this@LocationAlerts)

        init {
            // Prevent cancel
            mLoading.setCancelable(false)

            // Set default message
            mLoading.setMessage(getString(R.string.loading))

            // Show the progress dialog
            mLoading.show()
        }

        override fun doInBackground(vararg parameter: Int?): Exception? {
            try {
                // Update notification preferences
                PushManager.updateSubscriptions(this@LocationAlerts)

                // Subscribe for alerts based on current city/region selections
                RedAlertAPI.subscribe(this@LocationAlerts)
            } catch (exc: Exception) {
                // Return exception to onPostExecute
                return exc
            }

            // Success
            return null
        }

        override fun onPostExecute(exc: Exception?) {
            // Failed?
            if (exc != null) {
                // Log it
                Log.e(Logging.TAG, "Updating subscriptions failed", exc)

                // Restore previous notification toggle state
                val editor = Singleton.getSharedPreferences(this@LocationAlerts).edit()

                // Restore original values
                editor.putBoolean(getString(R.string.locationAlertsPref), !AppPreferences.getLocationAlertsEnabled(this@LocationAlerts))

                // Save and flush to disk
                editor.commit()
            }

            // Activity dead?
            if (isFinishing || isDestroyed) {
                return
            }

            // Hide loading dialog
            if (mLoading.isShowing) {
                mLoading.dismiss()
            }

            // Refresh nearby cities
            refreshSummaries()

            // Show error if failed
            if (exc != null) {
                // Build an error message
                val errorMessage = "${getString(R.string.apiRequestFailed)}\n\n${exc.message}\n\n${exc.cause}"

                // Build the dialog
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay), null, false, this@LocationAlerts, null)
            }
            else {
                // Location alerts enabled?
                if (mLocationAlerts.isChecked) {
                    // Android O and newer required for notification channels
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Show dialog instructing user on how to hide the location alerts foreground service notification
                        AlertDialogBuilder.showGenericDialog(getString(R.string.hideGPSForegroundNotification), getString(R.string.hideGPSForegroundNotificationInstructions), getString(R.string.okay), getString(R.string.notNow), true, this@LocationAlerts, DialogInterface.OnClickListener { _, which ->
                            // Clicked okay?
                            if (which == DialogInterface.BUTTON_POSITIVE) {
                                // Open notification channel config to allow user to easily disable the notification channel
                                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                intent.putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannels.LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_ID)
                                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                startActivity(intent)
                            }
                        })
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Just granted location permission?
        if (requestCode == LocationLogic.LOCATION_PERMISSION_REQUEST_CODE && grantResults.size > 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            // Enable location alerts
            mLocationAlerts.isChecked = true
            mLocationAlerts.onPreferenceChangeListener.onPreferenceChange(null, true)
        }
    }
    }
