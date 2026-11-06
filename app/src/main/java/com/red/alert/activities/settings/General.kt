package com.red.alert.activities.settings

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceScreen
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast

import androidx.core.view.MenuItemCompat

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.red.alert.R
import com.red.alert.activities.Main
import com.red.alert.config.API
import com.red.alert.config.Donations
import com.red.alert.config.Logging
import com.red.alert.config.Support
import com.red.alert.config.Testing
import com.red.alert.config.ThreatTypes
import com.red.alert.logic.alerts.AlertLogic
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.communication.broadcasts.LocationSelectionEvents
import com.red.alert.logic.communication.broadcasts.SelfTestEvents
import com.red.alert.logic.communication.broadcasts.SettingsEvents
import com.red.alert.logic.communication.intents.MainActivityParameters
import com.red.alert.logic.feedback.sound.SoundLogic
import com.red.alert.logic.location.LocationLogic
import com.red.alert.logic.push.FCMRegistration
import com.red.alert.logic.push.PushManager
import com.red.alert.logic.push.PushyRegistration
import com.red.alert.logic.services.ServiceManager
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.model.req.SelfTestRequest
import com.red.alert.ui.activities.AppCompatPreferenceActivity
import com.red.alert.ui.compatibility.ProgressDialogCompat
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.ui.elements.SearchableMultiSelectPreference
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.ui.notifications.AppNotifications
import com.red.alert.utils.backend.RedAlertAPI
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.communication.Broadcasts
import com.red.alert.utils.feedback.Volume
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.marketing.GooglePlay
import com.red.alert.utils.metadata.AppVersion
import com.red.alert.utils.metadata.LocationData
import com.red.alert.utils.networking.HTTP
import com.red.alert.utils.os.AndroidSettings
import com.red.alert.utils.threading.AsyncTaskAdapter
import com.red.alert.utils.ui.NavbarUtil

import java.util.Collections

import me.pushy.sdk.Pushy

class General : AppCompatPreferenceActivity() {
    private var mIsTesting = false
    private var mFcmTestPassed = false
    private var mPushyTestPassed = false

    private var mPreviousZones: String? = null
    private var mPreviousCities: String? = null

    private lateinit var mRate: Preference
    private lateinit var mDonate: Preference
    private lateinit var mWebsite: Preference
    private lateinit var mContact: Preference
    private lateinit var mAdvanced: Preference
    private lateinit var mTestAlert: Preference
    private lateinit var mBatteryOptimization: Preference
    private lateinit var mNotificationsEnabled: CheckBoxPreference

    private lateinit var mMainCategory: PreferenceCategory
    private lateinit var mBatteryOptimizationCategory: PreferenceCategory

    private lateinit var mThemeSelection: ListPreference
    private lateinit var mLanguageSelection: ListPreference

    private var mBillingClient: BillingClient? = null

    private lateinit var mCitySelection: SearchableMultiSelectPreference
    private lateinit var mZoneSelection: SearchableMultiSelectPreference

    private val mBroadcastListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Asked for reload?
        if (key.equals(LocationSelectionEvents.LOCATIONS_UPDATED, ignoreCase = true)) {
            // Refresh city/region descriptions
            refreshAreaValues()

            // Update subscriptions on the server-side
            UpdateSubscriptionsAsync().execute()
        }

        // FCM test passed?
        if (key.equals(SelfTestEvents.FCM_TEST_PASSED, ignoreCase = true)) {
            mFcmTestPassed = true
        }

        // Pushy test passed?
        if (key.equals(SelfTestEvents.PUSHY_TEST_PASSED, ignoreCase = true)) {
            mPushyTestPassed = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Load UI elements
        initializeUI()

        // Handle extra parameters
        handleIntentExtras()

        // Volume keys should control alert volume
        Volume.setVolumeKeysAction(this)
    }

    override fun attachBaseContext(base: Context) {
        // Reapply locale
        Localization.overridePhoneLocale(base)
        super.attachBaseContext(base)
    }

    private fun handleIntentExtras() {
        // Get extras
        val extras = intent.extras

        // None sent?
        if (extras == null) {
            return
        }

        // Get city selection boolean
        val showCitySelection = extras.getBoolean(SettingsEvents.SHOW_CITY_SELECTION)

        // Show selection?
        if (showCitySelection) {
            mCitySelection.showDialog()
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

        // Clear notifications and stop any playing sounds
        AppNotifications.clearAll(this)

        // Register for broadcasts
        Broadcasts.subscribe(this, mBroadcastListener)

        // Get power manager instance
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Android M (6) and up only
        // Check if app is already whitelisted from battery optimizations
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // Remove reminder message about whitelisting the app
            val preferenceScreen = findPreference(resources.getString(R.string.mainSettingsScreen)) as PreferenceScreen
            preferenceScreen.removePreference(mBatteryOptimizationCategory)
        }
    }

    private fun selfTestCompleted(result: Int) {
        // Use builder to create dialog
        val builder = AlertDialog.Builder(this)

        // Use builder to create dialog
        builder.setTitle(getString(R.string.test)).setMessage(getString(result)).setPositiveButton(R.string.okay, DialogInterface.OnClickListener { dialogInterface, _ ->
            // Stop siren (and clear notifications)
            AppNotifications.clearAll(this@General)

            // Close dialog
            dialogInterface.dismiss()
        })

        // Avoid cancellation
        builder.setOnCancelListener(DialogInterface.OnCancelListener {
            // Stop siren (and clear notifications)
            AppNotifications.clearAll(this@General)
        })

        try {
            // Build it
            val dialog: Dialog = builder.create()

            // Show it
            dialog.show()

            // Support for RTL languages
            RTLSupport.mirrorDialog(dialog, this)
        }
        catch (exc: Exception) {
            // Show toast instead
            Toast.makeText(this, getString(result), Toast.LENGTH_LONG).show()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)
    }

    private fun initializeUI() {
        // Allow click on home button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // Load settings from XML (there is no non-deprecated way to do it on API level 7)
        addPreferencesFromResource(R.xml.settings)

        // Fix nav bar color and styling
        NavbarUtil.fixPreferenceActivityNavbarColor(this)

        // Cache resource IDs
        mRate = findPreference(getString(R.string.ratePref))
        mDonate = findPreference(getString(R.string.donatePref))
        mWebsite = findPreference(getString(R.string.websitePref))
        mContact = findPreference(getString(R.string.contactPref))
        mAdvanced = findPreference(getString(R.string.advancedPref))
        mTestAlert = findPreference(getString(R.string.selfTestPref))
        mBatteryOptimization = findPreference(getString(R.string.batteryOptimizationPref))
        mMainCategory = findPreference(getString(R.string.mainCategoryPref)) as PreferenceCategory
        mBatteryOptimizationCategory = findPreference(getString(R.string.batteryOptimizationCategory)) as PreferenceCategory
        mCitySelection = findPreference(getString(R.string.selectedCitiesPref)) as SearchableMultiSelectPreference
        mZoneSelection = findPreference(getString(R.string.selectedZonesPref)) as SearchableMultiSelectPreference
        mThemeSelection = findPreference(getString(R.string.themePref)) as ListPreference
        mLanguageSelection = findPreference(getString(R.string.langPref)) as ListPreference
        mNotificationsEnabled = findPreference(getString(R.string.enabledPref)) as CheckBoxPreference

        // Populate setting values
        initializeSettings()

        // Set up listeners
        initializeListeners()
    }

    private fun initializeSettings() {
        // Set entries & values
        mZoneSelection.entries = resources.getStringArray(R.array.zoneNames)
        mZoneSelection.entryValues = resources.getStringArray(R.array.zoneValues)

        // Set entries & values
        mCitySelection.entries = LocationData.getAllCityNames(this)
        mCitySelection.entryValues = LocationData.getAllCityValues(this)

        // Not registered yet?
        if (!RedAlertAPI.isRegistered(this)) {
            mNotificationsEnabled.isEnabled = false
        }

        // Refresh area values
        refreshAreaValues()
    }

    private fun refreshAreaValues() {
        // Get selected cities
        val selectedCities = Singleton.getSharedPreferences(this).getString(getString(R.string.selectedCitiesPref), getString(R.string.none))

        // Update summary text
        mCitySelection.summary = "${getString(R.string.selectedCitiesDesc)}\r\n(${LocationData.getSelectedCityNamesByValues(this, selectedCities, mCitySelection.entries, mCitySelection.entryValues)})"

        // Get selected zones
        val selectedZones = Singleton.getSharedPreferences(this).getString(getString(R.string.selectedZonesPref), getString(R.string.none))

        // Update summary text
        mZoneSelection.summary = "${getString(R.string.selectedZonesDesc)}\r\n(${LocationData.getSelectedCityNamesByValues(this, selectedZones, mZoneSelection.entries, mZoneSelection.entryValues)})"

        // Save in case the update subscriptions request fails
        if (mPreviousZones == null && mPreviousCities == null) {
            mPreviousZones = selectedZones
            mPreviousCities = selectedCities
        }
    }

    private fun initializeListeners() {
        // Language preference select
        mLanguageSelection.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, o ->
            // Automatic selected?
            if (o == "") {
                // Restore default phone locale
                Localization.restoreDefaultLocale(this@General)
            }

            // Close settings activity
            finish()

            // Notify language changed
            Broadcasts.publish(this@General, SettingsEvents.THEME_OR_LANGUAGE_CHANGED)

            // Update the preference
            true
        }

        // Theme selection
        mThemeSelection.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            // Delay popup by 300ms so that preference change gets applied
            Handler().postDelayed({
                // Close settings activity
                finish()

                // Notify theme changed
                Broadcasts.publish(this@General, SettingsEvents.THEME_OR_LANGUAGE_CHANGED)
            }, 300)

            // Update the preference
            true
        }

        // Test push
        mTestAlert.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Not already testing?
            if (!mIsTesting) {
                // Send a test push
                PerformSelfTestAsync().execute()
            }

            // Consume event
            true
        }

        // Battery optimization whitelist
        mBatteryOptimization.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Show a dialog to the user
            AlertDialogBuilder.showGenericDialog(getString(R.string.disableBatteryOptimizations), AndroidSettings.getBatteryOptimizationWhitelistInstructions(this@General), getString(R.string.okay), getString(R.string.notNow), true, this@General, DialogInterface.OnClickListener { _, which ->
                // Clicked okay?
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Open App Info settings page
                    AndroidSettings.openAppInfoPage(this@General)
                }
            })

            // Consume event
            true
        }

        // Website button
        mWebsite.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Initialize browser intent
            val browser = Intent(Intent.ACTION_VIEW, Uri.parse(Support.WEBSITE_LINK))

            try {
                // Open browser
                startActivity(browser)
            }
            catch (ex: ActivityNotFoundException) {
                // Do nothing
            }

            // Consume event
            true
        }

        // Notification toggle listener
        mNotificationsEnabled.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            // Update notification preferences on the server-side
            UpdateNotificationsAsync().execute()

            // Enable/disable Pushy service according to new value
            Pushy.toggleNotifications(newValue as Boolean, this@General)

            // Enable/disable location service according to new value
            if (AppPreferences.getLocationAlertsEnabled(this@General)) {
                // Enabled alerts?
                if (newValue) {
                    // Start the location service
                    ServiceManager.startLocationService(this@General)
                }
                else {
                    // Stop the location service
                    ServiceManager.stopLocationService(this@General)
                }
            }

            // Tell Android to persist new checkbox value
            true
        }

        // Rate button
        mRate.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Open app page
            GooglePlay.openAppListingPage(this@General)

            // Consume event
            true
        }

        // Donate button
        mDonate.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Show popup dialog with instructions and possible donation amounts
            showDonationDialog()

            // Consume event
            true
        }

        // Set up contact click listener
        mContact.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Show contact us dialog
            contactUs()

            // Consume event
            true
        }

        // Set up advanced settings listener
        mAdvanced.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // Prepare new intent
            val advanced = Intent()

            // Set advanced class
            advanced.setClass(this@General, Advanced::class.java)

            // Show advanced settings
            startActivity(advanced)

            // Consume event
            true
        }
    }

    private fun contactUs() {
        // Create input for user to describe the problem
        val input = EditText(this@General)

        // Add hint text
        input.setHint(R.string.problemDesc)

        // 3 lines
        input.minLines = 3

        // RTL support
        if (Localization.isRTLLocale(this@General)) {
            input.textDirection = View.TEXT_DIRECTION_RTL
            input.layoutDirection = View.LAYOUT_DIRECTION_RTL
            input.gravity = Gravity.RIGHT or Gravity.TOP
        }

        // Calculate 20dp in pixels
        val margin = (25 * resources.displayMetrics.density).toInt()

        // Set margin & gravity
        input.setPadding(margin, margin, margin, margin)
        input.gravity = Gravity.TOP or Gravity.START

        // Set multi line capability
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

        // Prepare dialog
        val dialog = AlertDialog.Builder(this@General)
                .setTitle(R.string.contact)
                .setMessage(R.string.contactDesc)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(R.string.okay, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        // Show dialog
        dialog.show()

        // Set positive button click listener
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { _ ->
            // Get problem description text
            val problemDesc = input.text.toString().trim()

            // Empty?
            if (problemDesc.isEmpty() || problemDesc.length < 10) {
                // Show error
                input.error = getString(R.string.contactUsError)
                return@setOnClickListener
            }

            // Forward to mail app
            sendMessage(problemDesc)

            // Dismiss dialog
            dialog.dismiss()
        }

        // Support for RTL languages
        RTLSupport.mirrorDialog(dialog, this@General)
    }

    private fun sendMessage(text: String) {
        // Add debug flags
        val body = getContactEmailBody(text)

        // Set up contact click listener
        val selectorIntent = Intent(Intent.ACTION_SENDTO)
        selectorIntent.data = Uri.parse("mailto:")

        // Prepare e-mail intent
        val emailIntent = Intent(Intent.ACTION_SEND)

        // Add e-mail, subject & debug body
        emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(Support.CONTACT_EMAIL))
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.appName))
        emailIntent.putExtra(Intent.EXTRA_TEXT, body)

        // Limit to mail apps only
        emailIntent.selector = selectorIntent

        try {
            // Try to open user's e-mail app
            startActivity(Intent.createChooser(emailIntent, getString(R.string.contact)))
        }
        catch (exc: ActivityNotFoundException) {
            // Show a toast instead
            Toast.makeText(this@General, R.string.manualContact, Toast.LENGTH_LONG).show()
        }
    }

    private fun getContactEmailBody(text: String): String {
        // Add default problem description text
        var body = getString(R.string.problemDesc)

        // Break 2 lines
        body += "\r\n\r\n"

        // Add user-inputted text
        body += text

        // Break 2 lines
        body += "\r\n\r\n"

        // Add sent via and app version
        body += "${getString(R.string.sentVia)} ${getString(R.string.appName)} ${AppVersion.getVersionName(this@General)}"

        // Break 2 lines
        body += "\r\n\r\n"

        // Add selected cities
        body += getString(R.string.selectedCities) + ": " + LocationData.getSelectedCityNamesByValues(this, Singleton.getSharedPreferences(this).getString(getString(R.string.selectedCitiesPref), ""), LocationData.getAllCityNames(this), LocationData.getAllCityValues(this))

        // Break 1 line
        body += "\r\n"

        // Add selected zones
        body += getString(R.string.selectedZones) + ": " + LocationData.getSelectedCityNamesByValues(this, Singleton.getSharedPreferences(this).getString(getString(R.string.selectedZonesPref), ""), resources.getStringArray(R.array.zoneNames), resources.getStringArray(R.array.zoneValues))

        // Break 2 lines
        body += "\r\n\r\n"

        // Add selected secondary cities
        body += getString(R.string.selectedSecondaryCities) + ": " + LocationData.getSelectedCityNamesByValues(this, Singleton.getSharedPreferences(this).getString(getString(R.string.selectedSecondaryCitiesPref), ""), LocationData.getAllCityNames(this), LocationData.getAllCityValues(this))

        // Break 2 lines
        body += "\r\n\r\n"

        // Add debug info
        body += "${getString(R.string.debugInfo)}: "

        // Add setting values & push notification device information
        body += "user.id=${RedAlertAPI.getUserId(this)}, "
        body += "user.hash=${RedAlertAPI.getUserHash(this)}, "
        body += "primary.enabled=${AppPreferences.getNotificationsEnabled(this)}, "
        body += "secondary.enabled=${AppPreferences.getSecondaryNotificationsEnabled(this)}, "
        body += "location.enabled=${AppPreferences.getLocationAlertsEnabled(this)}, "

        // Check if location alerts enabled
        if (AppPreferences.getLocationAlertsEnabled(this)) {
            // Get current location
            val location: Location? = LocationLogic.getCurrentLocation(this)

            // Null check
            if (location != null) {
                // Add nearby cities for debugging purposes
                body += "location.nearbyCities=${LocationData.getNearbyCityNames(location, this)}, "
            }

            // Add max distance and update interval
            body += "location.maxDistance=${LocationLogic.getMaxDistanceKilometers(this, -1f)}km, "
            body += "location.updateInterval=every ${LocationLogic.getUpdateIntervalMilliseconds(this) / 1000 / 60} minute(s), "
        }

        // Early warning & leave shelter alerts
        body += "earlyWarnings.enabled=${AppPreferences.getEarlyWarningNotificationsEnabled(this)}, "
        body += "earlyWarnings.sound=${SoundLogic.getAlertSoundName(AlertTypes.PRIMARY, ThreatTypes.EARLY_WARNING, null, this)}, "
        body += "leaveShelterAlerts.enabled=${AppPreferences.getLeaveShelterNotificationsEnabled(this)}, "
        body += "leaveShelterAlerts.sound=${SoundLogic.getAlertSoundName(AlertTypes.PRIMARY, ThreatTypes.LEAVE_SHELTER, null, this)}, "

        // Add other params
        body += "volume.primary=${AppPreferences.getPrimaryAlertVolume(this, -1f)}, "
        body += "volume.secondary=${AppPreferences.getSecondaryAlertVolume(this, -1f)}, "
        body += "sound.primary=${SoundLogic.getAlertSoundName(AlertTypes.PRIMARY, null, null, this)}, "
        body += "sound.secondary=${SoundLogic.getAlertSoundName(AlertTypes.SECONDARY, null, null, this)}, "
        body += "popup.enabled=${AppPreferences.getPopupEnabled(this)}, "
        body += "secondaryPopup.enabled=${AppPreferences.getSecondaryPopupEnabled(this)}, "
        body += "wakeScreen.enabled=${AppPreferences.getWakeScreenEnabled(this)}, "
        body += "fcm=${FCMRegistration.isRegistered(this)}, "
        body += "fcm.token=${FCMRegistration.getRegistrationToken(this)}, "
        body += "pushy=${PushyRegistration.isRegistered(this)}, "
        body += "pushy.token=${PushyRegistration.getRegistrationToken(this)}, "
        body += "pushy.isConnected=${Pushy.isConnected()}, "
        body += "pushy.foregroundServiceEnabled=${AppPreferences.getForegroundServiceEnabled(this)}, "
        body += "android.sdk=${Build.VERSION.SDK_INT}, "
        body += "android.version=${Build.VERSION.RELEASE}, "

        // Add Wi-Fi connectivity status
        val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mWifi: NetworkInfo? = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)

        // Connected?
        if (mWifi != null && mWifi.isConnected) {
            body += "wifi.isConnected=true, "
        }
        else {
            body += "wifi.isConnected=false, "
        }

        // Add battery optimizations whitelist status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            body += "android.isIgnoringBatteryOptimizations=${(getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)}, "
        }

        // Add schedule exact alarms permission status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            body += "android.canScheduleExactAlarms=${(getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()}, "
        }

        body += "phone.manufacturer=${Build.MANUFACTURER}, "
        body += "phone.model=${Build.MODEL}"

        // Return body
        return body
    }

    private fun sendTestPushViaPushy() {
        // Log to logcat
        Log.d(Logging.TAG, "Testing Pushy...")

        // Reset test flag
        mPushyTestPassed = false

        // Not registered?
        if (!PushyRegistration.isRegistered(this)) {
            // No need for localization - only shows up in logcat for now
            throw Exception("The device is not registered for push notifications via Pushy.")
        }

        // Grab registration token
        val token = PushyRegistration.getRegistrationToken(this)

        // Get user's locale
        val locale = resources.configuration.locale.language

        // Create registration request
        val test = SelfTestRequest(token, locale, API.PUSHY_PLATFORM_IDENTIFIER)

        // Send the request to our API
        HTTP.post("/test", Singleton.getJackson().writeValueAsString(test))
    }

    private fun sendTestPushViaFcm() {
        // Log to logcat
        Log.d(Logging.TAG, "Testing FCM...")

        // Reset test flag
        mFcmTestPassed = false

        // Not registered?
        if (!FCMRegistration.isRegistered(this)) {
            // No need for localization - only shows up in logcat for now
            throw Exception("The device is not registered for push notifications via FCM.")
        }

        // Grab registration token
        val token = FCMRegistration.getRegistrationToken(this)

        // Get user's locale
        val locale = resources.configuration.locale.language

        // Create registration request
        val test = SelfTestRequest(token, locale, API.PLATFORM_IDENTIFIER)

        // Send the request to our API
        HTTP.post("/test", Singleton.getJackson().writeValueAsString(test))
    }

    private fun didPassFcmTest(): Boolean {
        // Calculate the max timestamp
        val maxTimestamp = System.currentTimeMillis() + Testing.PUSH_GATEWAY_TIMEOUT_SECONDS * 1000

        // Wait until boolean value changes or enough time passes
        while (!mFcmTestPassed && System.currentTimeMillis() < maxTimestamp) {
            // Sleep to relieve the thread
            try {
                Thread.sleep(100)
            }
            catch (exc: Exception) {
            }
        }

        // Return the outcome of the test
        return mFcmTestPassed
    }

    private fun didPassPushyTest(): Boolean {
        // Calculate the max timestamp
        val maxTimestamp = System.currentTimeMillis() + Testing.PUSH_GATEWAY_TIMEOUT_SECONDS * 1000

        // Wait until boolean value changes or enough time passes
        while (!mPushyTestPassed && System.currentTimeMillis() < maxTimestamp) {
            // Sleep to relieve the thread
            try {
                Thread.sleep(100)
            }
            catch (exc: Exception) {
            }
        }

        // Return the outcome of the test
        return mPushyTestPassed
    }

    override fun onCreateOptionsMenu(optionsMenu: Menu): Boolean {
        // Add settings button
        initializeShareButton(optionsMenu)

        // Show the menu
        return true
    }

    private fun initializeShareButton(optionsMenu: Menu) {
        // Add share button
        val shareItem = optionsMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, getString(R.string.share))

        // Set share icon
        shareItem.setIcon(R.drawable.ic_share)

        // Specify the show flags
        MenuItemCompat.setShowAsAction(shareItem, MenuItem.SHOW_AS_ACTION_ALWAYS)

        // On click, open share
        shareItem.setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener { _ ->
            // Prepare share intent
            val shareIntent = Intent(Intent.ACTION_SEND)

            // Set as text/plain
            shareIntent.type = "text/plain"

            // Add text
            shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.shareMessage))

            // Show chooser
            startActivity(Intent.createChooser(shareIntent, getString(R.string.shareDesc)))

            // Consume event
            true
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Check item ID
        when (item.itemId) {
            // Home button?
            android.R.id.home -> onBackPressed()
        }

        return super.onOptionsItemSelected(item)
    }

    inner class PerformSelfTestAsync : AsyncTaskAdapter<Int, String, Int>() {
        val mLoading: ProgressDialog

        init {
            // Prevent concurrent testing
            mIsTesting = true

            // Fix progress dialog appearance on old devices
            mLoading = ProgressDialogCompat.getStyledProgressDialog(this@General)

            // Prevent cancel
            mLoading.setCancelable(false)

            // Set default message
            mLoading.setMessage(getString(R.string.loading))

            // Show the progress dialog
            mLoading.show()
        }

        override fun onProgressUpdate(vararg value: String?) {
            super.onProgressUpdate(*value)

            // Update progress dialog
            mLoading.setMessage(value[0])
        }

        override fun doInBackground(vararg parameter: Int?): Int? {
            // Test results
            var fcmFailed = false
            var pushyFailed = false

            // IsTesting FCM
            publishProgress(getString(R.string.testingFCM))

            try {
                // Send a test push
                sendTestPushViaFcm()
            }
            catch (exc: Exception) {
                // FCM test is done
                fcmFailed = true

                // Log to console
                Log.e(Logging.TAG, "FCM test failed", exc)
            }

            // Wait for test push to arrive
            if (!fcmFailed) {
                if (!didPassFcmTest()) {
                    fcmFailed = true
                }
            }

            // Done with FCM, testing Pushy
            publishProgress(getString(R.string.testingPushy))

            try {
                // Send a test push
                sendTestPushViaPushy()
            }
            catch (exc: Exception) {
                // We failed
                pushyFailed = true

                // Log to console
                Log.e(Logging.TAG, "Pushy test failed", exc)
            }

            // Wait for test push to arrive
            if (!pushyFailed) {
                if (!didPassPushyTest()) {
                    pushyFailed = true
                }
            }

            // At least one passed?
            if (!fcmFailed || !pushyFailed) {
                // Display "successful test" message
                AlertLogic.processIncomingAlert(AlertTypes.TEST, getString(R.string.testSuccessful), AlertTypes.TEST, null, null, null, this@General)
            }

            // Both succeeded?
            if (!fcmFailed && !pushyFailed) {
                return R.string.testSuccessfulLong
            }

            // Both failed?
            else if (fcmFailed && pushyFailed) {
                return R.string.testFailed
            }

            // Only FCM failed?
            else if (fcmFailed) {
                return R.string.fcmTestFailed
            }

            // Only Pushy failed?
            else if (pushyFailed) {
                return R.string.pushyTestFailed
            }

            return 0
        }

        override fun onPostExecute(result: Int?) {
            // No longer testing
            mIsTesting = false

            // Activity dead?
            if (isFinishing || isDestroyed) {
                return
            }

            // Hide progress dialog
            if (mLoading.isShowing) {
                mLoading.dismiss()
            }

            // Show result dialog
            selfTestCompleted(result!!)
        }
    }

    inner class UpdateSubscriptionsAsync : AsyncTaskAdapter<Int, String, Exception>() {
        val mLoading: ProgressDialog = ProgressDialogCompat.getStyledProgressDialog(this@General)

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
                PushManager.updateSubscriptions(this@General)

                // Update alert subscriptions
                RedAlertAPI.subscribe(this@General)
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

                // Restore previous city/region selections
                val editor = Singleton.getSharedPreferences(this@General).edit()

                // Restore original values
                editor.putString(getString(R.string.selectedZonesPref), mPreviousZones)
                editor.putString(getString(R.string.selectedCitiesPref), mPreviousCities)

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
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay), null, false, this@General, null)
            }
            else {
                // Clear previously cached values
                mPreviousZones = null
                mPreviousCities = null

                // Reload recent alerts (if main activity is open)
                Broadcasts.publish(this@General, MainActivityParameters.RELOAD_RECENT_ALERTS)
            }

            // Refresh city/region setting values
            refreshAreaValues()
        }
    }

    inner class UpdateNotificationsAsync : AsyncTaskAdapter<Int, String, Exception>() {
        val mLoading: ProgressDialog = ProgressDialogCompat.getStyledProgressDialog(this@General)

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
                PushManager.updateSubscriptions(this@General)

                // Update notification preferences
                RedAlertAPI.updateNotificationPreferences(this@General)
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
                Log.e(Logging.TAG, "Updating notification preferences failed", exc)

                // Restore previous notification toggle state
                val editor = Singleton.getSharedPreferences(this@General).edit()

                // Restore original values
                editor.putBoolean(getString(R.string.enabledPref), !AppPreferences.getNotificationsEnabled(this@General))

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
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay), null, false, this@General, null)
            }

            // Refresh checkbox with new value
            mNotificationsEnabled.isChecked = AppPreferences.getNotificationsEnabled(this@General)

            // Reload recent alerts (if main activity is open)
            Broadcasts.publish(this@General, MainActivityParameters.RELOAD_RECENT_ALERTS)
        }
    }

    private fun showDonationDialog() {
        // Only initialize one billing client
        if (mBillingClient != null && mBillingClient!!.isReady) {
            showProducts()
            return
        }

        // Wait for existing client to finish initialization
        else if (mBillingClient != null) {
            return
        }

        // Enable one time product display
        val params = PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()

        // Create billing client
        mBillingClient = BillingClient.newBuilder(this)
                .enablePendingPurchases(params)
                .setListener(PurchasesUpdatedListener { billingResult, list ->
                    // Successful purchase?
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && list != null) {
                        // Traverse all successful purchases
                        for (purchase in list) {
                            // Item state is purchased?
                            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                                // Consume the purchase (so same item can be repurchased in the future) to acknowledge it
                                consumePurchase(purchase)
                            }
                        }

                        // Show thanks dialog
                        AlertDialogBuilder.showGenericDialog(getString(R.string.donateSuccess), getString(R.string.donateSuccessDesc), getString(R.string.okay), null, false, this@General, null)
                    }
                }).build()

        // Start the connection after initializing the billing client
        establishConnection()
    }

    private fun consumePurchase(purchase: Purchase) {
        // Params to consume the purchased consumable item by passing in the purchase token
        val consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

        // Consume item
        mBillingClient!!.consumeAsync(consumeParams, ConsumeResponseListener { billingResult, _ ->
            // Success?
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                // The consumable was successfully consumed, so the user can now repurchase it
                Log.d(Logging.TAG, "Donation consumed successfully!")
            } else {
                // Log any errors when consuming the product
                Log.d(Logging.TAG, "Error consuming donation: ${billingResult.debugMessage}")
            }
        })
    }

    private fun establishConnection() {
        // Connect billing client
        mBillingClient!!.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                // Connection success
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // Query for available products
                    showProducts()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Try to restart the connection on the next request to Google Play
                establishConnection()
            }
        })
    }

    private fun showProducts() {
        // Prepare ArrayList of products to query for
        val productList: MutableList<QueryProductDetailsParams.Product> = ArrayList()

        // Traverse product IDs
        for (productId in Donations.DONATION_PRODUCT_IDS) {
            // Create product objects with ID populated
            productList.add(
                    QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
            )
        }

        // Prepare product query params with product list
        val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

        // Query product details asynchronously
        mBillingClient!!.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            // Success?
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                // Extract list of fetched products from result object (PBL 8+ wraps the list in QueryProductDetailsResult),
                // copying into a mutable list since the returned list may be immutable
                val prodDetailsList: MutableList<ProductDetails> = ArrayList(queryProductDetailsResult.productDetailsList)

                // Sort list of products by price ASC
                Collections.sort(prodDetailsList, Comparator<ProductDetails> { p1, p2 ->
                    // Compare prices
                    java.lang.Double.compare(p1.oneTimePurchaseOfferDetails!!.priceAmountMicros.toDouble(), p2.oneTimePurchaseOfferDetails!!.priceAmountMicros.toDouble())
                })

                // Show dialog with sorted products
                runOnUiThread {
                    // Activity still running?
                    if (!isFinishing) {
                        // Show dialog
                        showProductDialog(prodDetailsList)
                    }
                }
            }
        }
    }

    private fun showProductDialog(productList: List<ProductDetails>) {
        // Prepare AlertDialog with items list
        val builder = AlertDialog.Builder(this)

        // Set dialgo title
        builder.setTitle(R.string.donate)

        // Convert ProductDetails to CharSequence Array (with an additional item for the instructions)
        val items = arrayOfNulls<CharSequence>(productList.size + 1)

        // Add dialog instructions (instruct user to select product)
        items[0] = getString(R.string.donateInstructions)

        // Traverse products list
        for (i in productList.indices) {
            // Get product name
            var name = productList[i].name

            // Localize product name based on product ID
            if (productList[i].productId == "donation_10") {
                name = getString(R.string.donation_10)
            }
            else if (productList[i].productId == "donation_20") {
                name = getString(R.string.donation_20)
            }
            else if (productList[i].productId == "donation_50") {
                name = getString(R.string.donation_50)
            }
            else if (productList[i].productId == "donation_100") {
                name = getString(R.string.donation_100)
            }
            else if (productList[i].productId == "donation_200") {
                name = getString(R.string.donation_200)
            }

            // Display local currency amounts in parenthesis
            if (productList[i].oneTimePurchaseOfferDetails!!.priceCurrencyCode != "ILS") {
                name += " (${productList[i].oneTimePurchaseOfferDetails!!.formattedPrice})"
            }

            // Set item value to localized item name
            items[i + 1] = name
        }

        // Set AlertDialog items list
        builder.setItems(items, DialogInterface.OnClickListener { _, which ->
            // Ignore instructions click event
            if (which == 0) {
                return@OnClickListener
            }

            // Get selected product based on index of tapped product
            val selectedProduct = productList[which - 1]

            // Launch Google Play purchase flow
            launchPurchaseFlow(selectedProduct)
        })

        // Allow canceling the dialog
        builder.setNegativeButton(R.string.notNow, null)

        // Build dialog
        val dialog: Dialog = builder.create()

        // Show dialog
        dialog.show()

        // Support for RTL languages
        RTLSupport.mirrorDialog(dialog, this)
    }

    private fun launchPurchaseFlow(product: ProductDetails) {
        // Prepare billing flow params by passing in product object
        val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                        Collections.singletonList(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                        .setProductDetails(product)
                                        .build()))
                .build()

        // Let Google Play handle the billing flow
        mBillingClient!!.launchBillingFlow(this, params)
    }
            }
