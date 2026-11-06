package com.red.alert.activities.settings.alerts

import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.provider.Settings
import android.util.Log
import android.view.MenuItem

import com.red.alert.R
import com.red.alert.config.Logging
import com.red.alert.logic.communication.broadcasts.LocationSelectionEvents
import com.red.alert.logic.push.PushManager
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.ui.activities.AppCompatPreferenceActivity
import com.red.alert.ui.compatibility.ProgressDialogCompat
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.ui.elements.SearchableMultiSelectPreference
import com.red.alert.ui.elements.SliderPreference
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.utils.backend.RedAlertAPI
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.communication.Broadcasts
import com.red.alert.utils.feedback.Volume
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.metadata.LocationData
import com.red.alert.utils.threading.AsyncTaskAdapter
import com.red.alert.utils.ui.NavbarUtil

class SecondaryAlerts : AppCompatPreferenceActivity() {
    private var mPreviousSecondaryCities: String? = null
    private lateinit var mSecondaryVolume: SliderPreference
    private lateinit var mSecondaryAlertPopup: CheckBoxPreference
    private lateinit var mSecondaryNotificationsEnabled: CheckBoxPreference
    private lateinit var mSecondaryCitySelection: SearchableMultiSelectPreference

    companion object {
        private const val ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE = 1001
    }

    private val mBroadcastListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Asked for reload?
        if (key.equals(LocationSelectionEvents.LOCATIONS_UPDATED, ignoreCase = true)) {
            // Refresh city descriptions
            refreshAreaValues()

            // Update subscriptions on the server-side
            UpdateSubscriptionsAsync().execute()
        }
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

    override fun onPause() {
        super.onPause()

        // Unregister for broadcasts
        Broadcasts.unsubscribe(this, mBroadcastListener)
    }

    override fun onResume() {
        super.onResume()

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Register for broadcasts
        Broadcasts.subscribe(this, mBroadcastListener)
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
        addPreferencesFromResource(R.xml.settings_secondary_alerts)

        // Fix nav bar color and styling
        NavbarUtil.fixPreferenceActivityNavbarColor(this)

        // Cache resource IDs
        mSecondaryVolume = findPreference(getString(R.string.secondaryVolumePref)) as SliderPreference
        mSecondaryAlertPopup = findPreference(getString(R.string.secondaryAlertPopupPref)) as CheckBoxPreference
        mSecondaryCitySelection = findPreference(getString(R.string.selectedSecondaryCitiesPref)) as SearchableMultiSelectPreference
        mSecondaryNotificationsEnabled = findPreference(getString(R.string.secondaryEnabledPref)) as CheckBoxPreference

        // Populate setting values
        initializeSettings()

        // Set up listeners
        initializeListeners()
    }

    private fun initializeSettings() {
        // Set entries & values
        mSecondaryCitySelection.entries = LocationData.getAllCityNames(this)
        mSecondaryCitySelection.entryValues = LocationData.getAllCityValues(this)

        // Not registered yet?
        if (!RedAlertAPI.isRegistered(this)) {
            mSecondaryNotificationsEnabled.isEnabled = false
        }

        // Refresh area values
        refreshAreaValues()
    }

    private fun refreshAreaValues() {
        // Get secondary cities
        val secondaryCities = Singleton.getSharedPreferences(this).getString(getString(R.string.selectedSecondaryCitiesPref), getString(R.string.none))

        // Update summary text
        mSecondaryCitySelection.summary = "${getString(R.string.selectedSecondaryCitiesDesc)}\r\n(${LocationData.getSelectedCityNamesByValues(this, secondaryCities, mSecondaryCitySelection.entries, mSecondaryCitySelection.entryValues)})"

        // Save in case the update subscriptions request fails
        if (mPreviousSecondaryCities == null) {
            mPreviousSecondaryCities = secondaryCities
        }
    }

    private fun initializeListeners() {
        // Volume selection
        mSecondaryVolume.setSeekBarChangedListener(SliderPreference.onSeekBarChangedListener { value ->
            // Get slider percent
            val percent = (AppPreferences.getSecondaryAlertVolume(this@SecondaryAlerts, value) * 100).toInt()

            // Generate summary
            "${getString(R.string.secondaryVolumeDesc)}\r\n($percent%)"
        })

        // Secondary notifications toggle listener
        mSecondaryNotificationsEnabled.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            // Update notification preferences on the server-side
            UpdateNotificationsAsync().execute()

            // Tell Android to persist new checkbox value
            true
        }

        // Secondary alert popup checkbox listener
        mSecondaryAlertPopup.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            // Android M+: Check if we have permission to draw over other apps
            if (newValue as Boolean && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@SecondaryAlerts)) {
                // Show permission request dialog
                AlertDialogBuilder.showGenericDialog(getString(R.string.grantOverlayPermission), getString(R.string.grantOverlayPermissionInstructions), getString(R.string.okay), getString(R.string.notNow), true, this@SecondaryAlerts, DialogInterface.OnClickListener { _, which ->
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
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // User returned from manage overlay permission activity?
        if (requestCode == ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE) {
            // Android M+: Check if we have permission to draw over other apps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this@SecondaryAlerts)) {
                mSecondaryAlertPopup.isChecked = true
            }
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

    inner class UpdateSubscriptionsAsync : AsyncTaskAdapter<Int, String, Exception>() {
        val mLoading: ProgressDialog = ProgressDialogCompat.getStyledProgressDialog(this@SecondaryAlerts)

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
                // Update Pub/Sub subscriptions
                PushManager.updateSubscriptions(this@SecondaryAlerts)

                // Update alert subscriptions
                RedAlertAPI.subscribe(this@SecondaryAlerts)
            }
            catch (exc: Exception) {
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

                // Restore previous city selection
                val editor = Singleton.getSharedPreferences(this@SecondaryAlerts).edit()

                // Restore original value
                editor.putString(getString(R.string.selectedSecondaryCitiesPref), mPreviousSecondaryCities)

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

            // Show error if failed
            if (exc != null) {
                // Build an error message
                val errorMessage = "${getString(R.string.apiRequestFailed)}\n\n${exc.message}${if (exc.cause != null) "\n\n${exc.cause}" else ""}"

                // Build the dialog
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay), null, false, this@SecondaryAlerts, null)
            }
            else {
                // Clear previously cached values
                mPreviousSecondaryCities = null
            }

            // Refresh city setting value
            refreshAreaValues()
        }
    }

    inner class UpdateNotificationsAsync : AsyncTaskAdapter<Int, String, Exception>() {
        val mLoading: ProgressDialog = ProgressDialogCompat.getStyledProgressDialog(this@SecondaryAlerts)

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
                PushManager.updateSubscriptions(this@SecondaryAlerts)

                // Update notification preferences
                RedAlertAPI.updateNotificationPreferences(this@SecondaryAlerts)
            }
            catch (exc: Exception) {
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
                Log.e(Logging.TAG, "Updating notification preferences failed", exc)

                // Restore previous notification toggle state
                val editor = Singleton.getSharedPreferences(this@SecondaryAlerts).edit()

                // Restore original values
                editor.putBoolean(getString(R.string.secondaryEnabledPref), !AppPreferences.getSecondaryNotificationsEnabled(this@SecondaryAlerts))

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

            // Show error if failed
            if (exc != null) {
                // Build an error message
                val errorMessage = "${getString(R.string.apiRequestFailed)}\n\n${exc.message}${if (exc.cause != null) "\n\n${exc.cause}" else ""}"

                // Build the dialog
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay), null, false, this@SecondaryAlerts, null)
            }

            // Refresh checkbox with new value
            mSecondaryNotificationsEnabled.isChecked = AppPreferences.getSecondaryNotificationsEnabled(this@SecondaryAlerts)
        }
    }
}
