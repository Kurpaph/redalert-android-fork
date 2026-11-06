package com.red.alert.activities

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.MenuItemCompat

import com.google.android.gms.tasks.Tasks
import com.google.firebase.installations.FirebaseInstallations
import com.red.alert.R
import com.red.alert.databinding.MainBinding
import com.red.alert.config.Sound
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.feedback.sound.SoundLogic
import com.red.alert.activities.settings.General
import com.red.alert.config.Integrations
import com.red.alert.config.Logging
import com.red.alert.config.RecentAlerts
import com.red.alert.config.Safety
import com.red.alert.config.ThreatTypes
import com.red.alert.logic.alerts.AlertLogic
import com.red.alert.logic.communication.broadcasts.SettingsEvents
import com.red.alert.logic.communication.intents.AlertPopupParameters
import com.red.alert.logic.communication.intents.AlertViewParameters
import com.red.alert.logic.communication.intents.MainActivityParameters
import com.red.alert.logic.location.LocationLogic
import com.red.alert.logic.push.FCMRegistration
import com.red.alert.logic.push.PushManager
import com.red.alert.logic.push.PushyRegistration
import com.red.alert.logic.services.ServiceManager
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.model.Alert
import com.red.alert.model.res.VersionInfo
import com.red.alert.ui.adapters.AlertAdapter
import com.red.alert.ui.compatibility.ProgressDialogCompat
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.ui.dialogs.custom.UpdateDialogs
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.ui.notifications.AppNotifications
import com.red.alert.utils.backend.RedAlertAPI
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.communication.Broadcasts
import com.red.alert.utils.feedback.Volume
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.integration.GooglePlayServices
import com.red.alert.utils.localization.DateTime
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.metadata.AppVersion
import com.red.alert.utils.metadata.LocationData
import com.red.alert.utils.networking.HTTP
import com.red.alert.utils.os.AndroidSettings
import com.red.alert.utils.threading.AsyncTaskAdapter

import java.util.Collections

import me.pushy.sdk.Pushy
import me.pushy.sdk.config.PushyForegroundService
import me.pushy.sdk.lib.jackson.core.JsonFactory
import me.pushy.sdk.lib.jackson.core.JsonParser
import me.pushy.sdk.lib.jackson.core.JsonToken
import me.pushy.sdk.lib.jackson.databind.ObjectMapper
import me.pushy.sdk.util.PushyAuthentication

class Main : AppCompatActivity() {
    // Inflated layout binding for main.xml (replaces findViewById)
    private var binding: MainBinding? = null

    private var mIsResumed = false
    private var mIsReloading = false
    private var mIsRegistering = false
    private var mCheckedForUpdates = false
    private var mPushTokensRefreshed = false
    private var mPermissionDialogDisplayed = false

    private var mNewestAlertId: Long = 0

    private lateinit var mImSafe: Button
    private var mRunnable: Runnable? = null
    private lateinit var mAlertsList: ListView
    private lateinit var mLoading: ProgressBar
    private var mLoadingItem: MenuItem? = null
    private lateinit var mNoAlerts: LinearLayout
    private lateinit var mAlertsAdapter: AlertAdapter
    private var mClearRecentAlertsItem: MenuItem? = null

    private lateinit var mNewAlerts: MutableList<Alert>
    private lateinit var mDisplayAlerts: MutableList<Alert>

    private val mHandler = Handler(Looper.getMainLooper())

    private val mBroadcastListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Asked for reload?
        if (key.equals(MainActivityParameters.RELOAD_RECENT_ALERTS, ignoreCase = true)) {
            // Force full reload (disable caching)
            mNewestAlertId = 0

            // Reload alerts
            reloadRecentAlerts()
        }

        // Theme or language changed?
        if (key.equals(SettingsEvents.THEME_OR_LANGUAGE_CHANGED, ignoreCase = true)) {
            // Apply custom theme selection
            Localization.applyThemeSelection(applicationContext)

            // Reload activity
            finish()
            startActivity(Intent(this@Main, Main::class.java))
        }
    }

    override fun attachBaseContext(base: Context) {
        // Reapply locale
        Localization.overridePhoneLocale(base)
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Call parent method
        super.onCreate(savedInstanceState)

        // Ensure RTL layouts are used if needed
        Localization.overridePhoneLocale(this)

        // Initialize app UI
        initializeUI()

        // Start polling for recent alerts
        pollRecentAlerts()

        // Volume keys should control alert volume
        Volume.setVolumeKeysAction(this)

        // Handle notification click event (show alert popup)
        handleNotificationClick(intent)

        // Cached Apps Freezer compatibility
        // Force foreground service on Google Pixel devices (Android 15+)
        forceForegroundServiceOnPixelDevices()

        // Delay by 5 seconds
        mHandler.postDelayed({
            // Clean up old preferences
            AppPreferences.deleteOldSharedPreferences(this@Main)
        }, 5 * 1000L)
    }

    private fun forceForegroundServiceOnPixelDevices() {
        // Only for Google Pixel devices (Android 15+)
        if (!Build.MANUFACTURER.lowercase().contains("google") || !Build.MODEL.lowercase().contains("pixel") || Build.VERSION.SDK_INT < 35) {
            return
        }

        // Foreground service not enabled yet?
        if (!AppPreferences.getForegroundServiceEnabled(this)) {
            // Force enable the foreground service
            Singleton.getSharedPreferences(this).edit().putBoolean(getString(R.string.foregroundServicePref), true).commit()
        }
    }

    private fun showAppUpdateAvailableDialog() {
        // Haven't displayed tutorial?
        if (!AppPreferences.getTutorialDisplayed(this)) {
            return
        }

        // Not registered?
        if (!RedAlertAPI.isRegistered(this@Main)) {
            return
        }

        // Haven't registered with FCM/Pushy for notifications?
        if (!FCMRegistration.isRegistered(this) || !PushyRegistration.isRegistered(this)) {
            return
        }

        // Already displayed a permission dialog for this activity?
        if (mPermissionDialogDisplayed) {
            return
        }

        // Already checked for updates?
        if (mCheckedForUpdates) {
            return
        }

        // Avoid checking multiple times
        mCheckedForUpdates = true

        // Do it async
        CheckForUpdatesAsync().execute()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Clear app notifications
        AppNotifications.clearAll(this)

        // RTL action bar hack
        RTLSupport.mirrorActionBar(this)

        // Handle notification click event (show alert popup)
        handleNotificationClick(intent)
    }

    private fun initializeUI() {
        // RTL action bar hack
        RTLSupport.mirrorActionBar(this)

        // Set title manually (for override locale to work)
        setTitle(R.string.recentAlerts)

        // Inflate layout with view binding
        binding = MainBinding.inflate(layoutInflater)

        // Attach root to this activity
        setContentView(binding!!.root)

        // Get views from binding
        mImSafe = binding!!.safe
        mAlertsList = binding!!.alerts
        mLoading = binding!!.loading
        mNoAlerts = binding!!.noAlerts

        // Initialize alert lists
        mNewAlerts = ArrayList()
        mDisplayAlerts = ArrayList()

        // Create alert adapter
        mAlertsAdapter = AlertAdapter(this, mDisplayAlerts)

        // Attach it to list
        mAlertsList.adapter = mAlertsAdapter

        // On-click listeners
        initializeListeners()
    }

    private fun initializeListeners() {
        // Alert click listener
        mAlertsList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            // Get alert by click position
            val alert: Alert? = mDisplayAlerts[position]

            // No such alert?
            if (alert == null) {
                return@OnItemClickListener
            }

            // No map view for system alerts
            if (alert.threat == ThreatTypes.SYSTEM) {
                return@OnItemClickListener
            }

            // Create new intent
            val alertView = Intent()

            // Set class
            alertView.setClass(this@Main, Map::class.java)

            // Pass alerts directly to map activity
            Map.mAlerts = alert.groupedAlerts

            // Set expanded alert flag (for determining share message format)
            Map.mIsExpandedAlert = alert.isExpanded

            // Show it
            startActivity(alertView)
        }

        // No alerts image click listener
        mNoAlerts.setOnClickListener {
            // DEBUG ONLY
            //AlertLogic.processIncomingAlert("missiles", "נהריה", "alert", null, null, null, Main.this);
        }

        // "I'm Safe" button click listener
        mImSafe.setOnClickListener {
            // Prepare share intent
            val shareIntent = Intent(Intent.ACTION_SEND)

            // Set as text/plain
            shareIntent.type = "text/plain"

            // Add text
            shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.imSafeMessage))

            // Send via WhatsApp?
            if (Singleton.getSharedPreferences(this@Main).getBoolean(getString(R.string.imSafeWhatsAppPref), false)) {
                // Set WhatsApp package
                shareIntent.setPackage(Integrations.WHATSAPP_PACKAGE)
            }

            // Show chooser
            startActivity(Intent.createChooser(shareIntent, getString(R.string.imSafeDesc)))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)
    }

    override fun onResume() {
        super.onResume()

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Save state
        mIsResumed = true

        // Allow other dialogs to be displayed
        mPermissionDialogDisplayed = false

        // Reload alerts manually
        reloadRecentAlerts()

        // Clear app notifications
        AppNotifications.clearAll(this)

        // Register for broadcasts
        Broadcasts.subscribe(this, mBroadcastListener)

        // Android 13:
        // Ensure notification permission has been granted
        requestNotificationPermission()

        // Ask user to whitelist app from battery optimizations
        showBatteryExemptionDialog()

        // Ask user to enable location permission if necessary
        showLocationPermissionDialog()

        // Android 12+
        // Ask user to allow setting exact alarms if canScheduleExactAlarms() is false (usually only false on Android 13+, but can be disabled manually since Android 12)
        showScheduleExactAlarmsPermissionDialog()

        // Ask user to grant app overlay permission if revoked
        showAlertPopupPermissionDialog()

        // Samsung, Android 11+
        // Ask user to grant notification policy access permission
        // In case custom sound selected
        showNotificationPolicyAccessPermissionDialog()

        // Google Pixel, Android 15+
        // Cached Apps Freezer compatibility
        // Notify user about forced foreground service
        showForcedForegroundServiceDialog()

        // Check for app version updates
        showAppUpdateAvailableDialog()

        // Restart the app services if needed
        ServiceManager.startAppServices(this)

        // Always re-register FCM or Pushy on app start
        if (!mPushTokensRefreshed || !FCMRegistration.isRegistered(this@Main) || !PushyRegistration.isRegistered(this@Main) || !RedAlertAPI.isRegistered(this@Main) || !RedAlertAPI.isSubscribed(this@Main)) {
            // Prevent concurrent registration
            if (!mIsRegistering) {
                RegisterPushAsync().execute()
            }
        }

        // Already loaded recent alerts?
        if (mNewAlerts.size > 0) {
            // Refresh alerts relative time
            invalidateAlertList()
        }
    }

    private fun requestNotificationPermission() {
        // Check if device is already registered, but permission not granted yet
        if (PushyAuthentication.getDeviceCredentials(this) != null && (!Pushy.isPermissionGranted(this) || !NotificationManagerCompat.from(this@Main).areNotificationsEnabled())) {
            // Prevent other dialogs from being displayed
            mPermissionDialogDisplayed = true

            // Show error with an on-click listener that opens the App Info page
            AlertDialogBuilder.showGenericDialog(getString(R.string.error), getString(R.string.grantNotificationPermission), getString(R.string.okay), null, false, this@Main, DialogInterface.OnClickListener { _, _ ->
                // Open App Info settings page
                AndroidSettings.openAppInfoPage(this@Main)
            })
        }
    }

    private fun showLocationPermissionDialog() {
        // Location alerts disabled?
        if (!AppPreferences.getLocationAlertsEnabled(this)) {
            return
        }

        // Already displayed a permission dialog for this activity?
        if (mPermissionDialogDisplayed) {
            return
        }

        // Can we access the user's location?
        if (!LocationLogic.isLocationAccessGranted(this)) {
            // Request permission via dialog
            LocationLogic.showLocationAccessRequestDialog(this)

            // Prevent other dialogs from being displayed
            mPermissionDialogDisplayed = true
        }
    }

    private fun showScheduleExactAlarmsPermissionDialog() {
        // Already displayed a permission dialog for this activity?
        if (mPermissionDialogDisplayed) {
            return
        }

        // Haven't displayed tutorial?
        if (!AppPreferences.getTutorialDisplayed(this)) {
            return
        }

        // Haven't registered for notifications?
        if (!FCMRegistration.isRegistered(this) || !PushyRegistration.isRegistered(this)) {
            return
        }

        // Only Android 12 and up allows revoking schedule exact alarms permission
        if (Build.VERSION.SDK_INT < 31) {
            return
        }

        // Get power manager instance
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // This dialog should only be displayed after user disabled battery optimizations
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }

        // Get alarm manager instance
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        // Check if user has already granted permission
        if (alarmManager.canScheduleExactAlarms()) {
            return
        }

        // Show a dialog to the user
        AlertDialogBuilder.showGenericDialog(getString(R.string.allowSettingExactAlarms), getString(R.string.allowSettingExactAlarmsInstructions), getString(R.string.okay), getString(R.string.notNow), true, this, DialogInterface.OnClickListener { _, which ->
            // Clicked okay?
            if (which == DialogInterface.BUTTON_POSITIVE) {
                // Open alarms and reminders settings screen for this app
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)

                // Set package to current package
                intent.data = Uri.fromParts("package", packageName, null)

                // Start settings activity
                startActivity(intent)
            }
        })

        // Prevent other dialogs from being displayed
        mPermissionDialogDisplayed = true
    }

    override fun onPause() {
        super.onPause()

        // Save state
        mIsResumed = false
    }

    private fun showBatteryExemptionDialog() {
        // Android M (6) and up only
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        // Haven't displayed tutorial?
        if (!AppPreferences.getTutorialDisplayed(this)) {
            return
        }

        // Haven't registered for notifications?
        if (!FCMRegistration.isRegistered(this) || !PushyRegistration.isRegistered(this)) {
            return
        }

        // Already displayed a permission dialog for this activity?
        if (mPermissionDialogDisplayed) {
            return
        }

        // Get power manager instance
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Check if app isn't already whitelisted from battery optimizations
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }

        // Check how many times we asked already
        var count = Singleton.getSharedPreferences(this).getInt(getString(R.string.batteryOptimizationWarningDisplayedCountPref), 0)

        // Warn 3 times
        if (count >= 3) {
            return
        }

        // Show a dialog to the user
        AlertDialogBuilder.showGenericDialog(getString(R.string.disableBatteryOptimizations), AndroidSettings.getBatteryOptimizationWhitelistInstructions(this), getString(R.string.okay), getString(R.string.notNow), true, this, DialogInterface.OnClickListener { _, which ->
            // Clicked okay?
            if (which == DialogInterface.BUTTON_POSITIVE) {
                // Open App Info settings page
                AndroidSettings.openAppInfoPage(this@Main)
            }
        })

        // Prevent other dialogs from being displayed
        mPermissionDialogDisplayed = true

        // Increment counter
        count++

        // Persist dialog display counter to SharedPreferences
        val editor = Singleton.getSharedPreferences(this).edit()

        // Save counter as integer
        editor.putInt(getString(R.string.batteryOptimizationWarningDisplayedCountPref), count)

        // Save and flush to disk
        editor.commit()
    }

    private fun showAlertPopupPermissionDialog() {
        // Android M (6) and up only
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        // Check if alert popup disabled (primary/secondary)
        if (!AppPreferences.getPopupEnabled(this) && !AppPreferences.getSecondaryPopupEnabled(this)) {
            return
        }

        // Check if we already have permission to draw over other apps
        if (Settings.canDrawOverlays(this)) {
            return
        }

        // Haven't displayed tutorial?
        if (!AppPreferences.getTutorialDisplayed(this)) {
            return
        }

        // Haven't registered for notifications?
        if (!FCMRegistration.isRegistered(this) || !PushyRegistration.isRegistered(this)) {
            return
        }

        // Already displayed a permission dialog for this activity?
        if (mPermissionDialogDisplayed) {
            return
        }

        // Prevent other dialogs from being displayed
        mPermissionDialogDisplayed = true

        // Show permission request dialog
        AlertDialogBuilder.showGenericDialog(getString(R.string.grantOverlayPermission), getString(R.string.grantOverlayPermissionInstructions), getString(R.string.okay), getString(R.string.notNow), true, this, DialogInterface.OnClickListener { _, which ->
            // Clicked okay?
            if (which == DialogInterface.BUTTON_POSITIVE) {
                // Bring user to relevant settings activity to grant the app overlay permission
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
    }

    private fun showNotificationPolicyAccessPermissionDialog() {
        // Android R (11) and up only
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        // Check if device is Samsung and Android R or above
        if (!SoundLogic.isSamsungDeviceRequiringSilentModeOverride()) {
            return
        }

        // Check if user wants us to override silent mode for either primary or secondary alerts
        if (!SoundLogic.shouldOverrideSilentMode(AlertTypes.PRIMARY, this) && !SoundLogic.shouldOverrideSilentMode(AlertTypes.SECONDARY, this)) {
            return
        }

        // Check if custom sound configured
        val alertSoundName = SoundLogic.getAlertSoundName(AlertTypes.PRIMARY, ThreatTypes.MISSILES, null, this)
        val earlyWarningSoundName = SoundLogic.getAlertSoundName(AlertTypes.PRIMARY, ThreatTypes.EARLY_WARNING, null, this)
        val leaveShelterSoundName = SoundLogic.getAlertSoundName(AlertTypes.PRIMARY, ThreatTypes.LEAVE_SHELTER, null, this)
        val secondarySoundName = SoundLogic.getAlertSoundName(AlertTypes.SECONDARY, ThreatTypes.MISSILES, null, this)

        // No custom sound selected for any alert sound preference?
        if (alertSoundName != Sound.CUSTOM_SOUND_NAME && earlyWarningSoundName != Sound.CUSTOM_SOUND_NAME && leaveShelterSoundName != Sound.CUSTOM_SOUND_NAME && secondarySoundName != Sound.CUSTOM_SOUND_NAME) {
            return
        }

        // Haven't displayed tutorial?
        if (!AppPreferences.getTutorialDisplayed(this)) {
            return
        }

        // Haven't registered for notifications?
        if (!FCMRegistration.isRegistered(this) || !PushyRegistration.isRegistered(this)) {
            return
        }

        // Get instance of NotificationManager
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Checks if notification policy access permission already granted
        if (notificationManager.isNotificationPolicyAccessGranted) {
            return
        }

        // Already displayed a permission dialog for this activity?
        if (mPermissionDialogDisplayed) {
            return
        }

        // Prevent other dialogs from being displayed
        mPermissionDialogDisplayed = true

        // Show permission request dialog
        AlertDialogBuilder.showGenericDialog(getString(R.string.notificationPolicyAccessPermission), getString(R.string.notificationPolicyAccessPermissionInstructions), getString(R.string.okay), getString(R.string.notNow), true, this, DialogInterface.OnClickListener { _, which ->
            // Clicked okay?
            if (which == DialogInterface.BUTTON_POSITIVE) {
                // Bring user to relevant settings activity to grant the notification policy access permission
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        })
    }

    private fun showForcedForegroundServiceDialog() {
        // Only for Google Pixel devices (Android 15+)
        if (!Build.MANUFACTURER.lowercase().contains("google") || !Build.MODEL.lowercase().contains("pixel") || Build.VERSION.SDK_INT < 35) {
            return
        }

        // Master switch is enabled?
        if (!AppPreferences.getNotificationsEnabled(this)) {
            return
        }

        // Haven't displayed tutorial?
        if (!AppPreferences.getTutorialDisplayed(this)) {
            return
        }

        // Haven't registered for notifications?
        if (!FCMRegistration.isRegistered(this) || !PushyRegistration.isRegistered(this)) {
            return
        }

        // Already displayed this exact dialog?
        if (Singleton.getSharedPreferences(this).getBoolean(getString(R.string.forcedForegroundServiceDialogShown), false)) {
            return
        }

        // Already displayed a permission dialog for this activity?
        if (mPermissionDialogDisplayed) {
            return
        }

        // Prevent other dialogs from being displayed
        mPermissionDialogDisplayed = true

        // Show dialog instructing user on how to hide the Pushy foreground service notification
        AlertDialogBuilder.showGenericDialog(getString(R.string.hidePushyForegroundNotification), getString(R.string.hidePushyForegroundNotificationInstructions), getString(R.string.okay), getString(R.string.notNow), true, this, DialogInterface.OnClickListener { _, which ->
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

        // Dialog shown
        Singleton.getSharedPreferences(this).edit().putBoolean(getString(R.string.forcedForegroundServiceDialogShown), true).commit()
    }

    private fun pollRecentAlerts() {
        // Schedule a new timer
        mRunnable = object : Runnable {
            override fun run() {
                // Activity died?
                if (isFinishing || isDestroyed) {
                    return
                }

                // App is running?
                if (mIsResumed) {
                    // Reload every X seconds
                    reloadRecentAlerts()
                }

                // Schedule for future execution
                mHandler.postDelayed(this, 1000L * RecentAlerts.RECENT_ALERTS_POLLING_INTERVAL_SEC)
            }
        }

        // Schedule for future invocation
        mHandler.postDelayed(mRunnable!!, 1000L * RecentAlerts.RECENT_ALERTS_POLLING_INTERVAL_SEC)
    }

    private fun reloadRecentAlerts() {
        // Not already reloading?
        if (!mIsReloading) {
            // Get recent alerts async
            GetRecentAlertsAsync().execute()
        }
    }

    private fun initializeSettingsButton(optionsMenu: Menu) {
        // Add settings item
        val settingsItem = optionsMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, getString(R.string.settings))

        // Set settings icon
        settingsItem.setIcon(R.drawable.ic_settings)

        // Specify the show flags
        MenuItemCompat.setShowAsAction(settingsItem, MenuItem.SHOW_AS_ACTION_ALWAYS)

        // On click, open Settings activity
        settingsItem.setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener {
            // Start settings activity
            goToSettings(false)

            // Consume event
            true
        })
    }

    private fun initializeLiveMapButton(optionsMenu: Menu) {
        // Add live map button
        val mapItem = optionsMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, getString(R.string.alerts))

        // Set map icon
        mapItem.setIcon(R.drawable.ic_map)

        // Specify the show flags
        MenuItemCompat.setShowAsAction(mapItem, MenuItem.SHOW_AS_ACTION_ALWAYS)

        // On click, open Map activity in live mode
        mapItem.setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener {
            // Start live map activity
            openLiveMap()

            // Consume event
            true
        })
    }

    private fun openLiveMap() {
        // Prepare new intent
        val mapIntent = Intent()

        // Set map activity class
        mapIntent.setClass(this@Main, Map::class.java)

        // Set live mode
        mapIntent.putExtra(AlertViewParameters.LIVE, true)

        // Ungrouped alerts
        val mAllAlerts: MutableList<Alert> = ArrayList()

        // Workaround for ConcurrentModificationException
        if (mIsReloading) {
            return
        }

        // Traverse all alerts
        for (alert in mNewAlerts) {
            // Any grouped alerts?
            if (alert.groupedAlerts.size > 0) {
                // Add grouped alerts
                for (grouped in alert.groupedAlerts) {
                    mAllAlerts.add(grouped)
                }
            }
            else {
                // Not grouped, add single alert to list
                mAllAlerts.add(alert)
            }
        }

        // Pass all currently-displayed alerts to map activity
        Map.mAlerts = mAllAlerts

        // Pass newest alert ID
        Map.mNewestAlertId = mNewestAlertId

        // Start map activity
        startActivity(mapIntent)
    }

    private fun initializeClearRecentAlertsButton(optionsMenu: Menu) {
        // Add clear item
        val clearItem = optionsMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, getString(R.string.clearRecentAlerts))
        mClearRecentAlertsItem = clearItem

        // Set default icon
        clearItem.setIcon(R.drawable.ic_clear)

        // Specify the show flags
        MenuItemCompat.setShowAsAction(clearItem, MenuItem.SHOW_AS_ACTION_ALWAYS)

        // On click, toggle clearing recent alerts
        clearItem.setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener {
            // Clear currently displayed alerts
            if (mDisplayAlerts.size > 0) {
                // Show a dialog to the user
                AlertDialogBuilder.showGenericDialog(getString(R.string.clearRecentAlerts), getString(R.string.clearRecentAlertsDesc), getString(R.string.yes), getString(R.string.no), true, this@Main, DialogInterface.OnClickListener { _, which ->
                    // Clicked okay?
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        // Set recent alerts cutoff timestamp to now
                        AppPreferences.updateRecentAlertsCutoffTimestamp(DateTime.getUnixTimestamp(), this@Main)

                        // Invalidate the list
                        invalidateAlertList()

                        // Clear app notifications
                        AppNotifications.clearAll(this@Main)
                    }
                })
            }
            else {
                // No alerts displayed, so display all of them
                AppPreferences.updateRecentAlertsCutoffTimestamp(0, this@Main)

                // Invalidate the list
                invalidateAlertList()
            }

            // Consume event
            true
        })

        // By default, hide button until alerts are returned by the server
        clearItem.isVisible = false
    }

    private fun goToSettings(showZoneSelection: Boolean) {
        // Prepare new intent
        val settingsIntent = Intent()

        // Set settings class
        settingsIntent.setClass(this@Main, General::class.java)

        // Add area selection boolean
        settingsIntent.putExtra(SettingsEvents.SHOW_CITY_SELECTION, showZoneSelection)

        // Start settings activity
        startActivity(settingsIntent)
    }

    private fun initializeLoadingIndicator(optionsMenu: Menu) {
        // Add refresh in Action Bar
        val loadingItem = optionsMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, getString(R.string.loading))
        mLoadingItem = loadingItem

        // Set up the view
        MenuItemCompat.setActionView(loadingItem, R.layout.loading_small)

        // Specify the show flags
        MenuItemCompat.setShowAsAction(loadingItem, MenuItem.SHOW_AS_ACTION_ALWAYS)

        // Hide by default
        loadingItem.isVisible = false
    }

    override fun onCreateOptionsMenu(optionsMenu: Menu): Boolean {
        // Add loading indicator
        initializeLoadingIndicator(optionsMenu)

        // Add live map button
        initializeLiveMapButton(optionsMenu)

        // Add clear recent alerts button
        initializeClearRecentAlertsButton(optionsMenu)

        // Add settings button
        initializeSettingsButton(optionsMenu)

        // Show the menu
        return true
    }

    private fun getRecentAlerts(): Int {
        // Ensure the right language is displayed
        Localization.overridePhoneLocale(this)

        // Store JSON as string initially
        val alertsJSON: String

        try {
            // Get it from /alerts/all (includes early warnings & leave shelter alerts)
            alertsJSON = HTTP.get("/alerts/all")
        }
        catch (exc: Exception) {
            // Log it
            Log.e(Logging.TAG, "Get recent alerts request failed", exc)

            // Return error code
            return R.string.apiRequestFailed
        }

        // Prepare list of recent alerts
        var recentAlerts: MutableList<Alert> = ArrayList()

        try {
            // Create a JsonParser over your JSON string
            val factory = JsonFactory()
            val parser: JsonParser = factory.createParser(alertsJSON)

            // Make sure it starts with an array
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw IllegalStateException("Expected JSON array")
            }

            // Get Jackson mapper
            val mapper: ObjectMapper = Singleton.getJackson()

            // Keep track of whether the alert being parsed is the first alert in the list
            var firstAlert = true

            // Iterate over each element in the array
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                // Deserialize a single Alert
                val alert = mapper.readValue(parser, Alert::class.java)

                // Is this the first alert? (most recent)
                if (firstAlert) {
                    // Same ID as the currently displayed first alert?
                    if (alert.id == mNewestAlertId) {
                        // Stop parsing (no new alerts)
                        parser.close()

                        // Loop over currently displayed alerts
                        for (displayedAlert in mDisplayAlerts) {
                            // Update relative time ago
                            displayedAlert.dateString = LocationData.getAlertDateTimeString(displayedAlert.date, displayedAlert.firstGroupedAlertTimestamp, this)
                        }

                        // No new alerts, just refresh relative time ago
                        return R.string.noNewAlerts
                    }

                    // Save newest alert ID
                    mNewestAlertId = alert.id
                }

                // No longer the first alert
                firstAlert = false

                // Add to list
                recentAlerts.add(alert)
            }

            // Close parser
            parser.close()
        } catch (exc: Exception) {
            // Log it
            Log.e(Logging.TAG, "Get recent alerts request failed", exc)

            // Return error code
            return R.string.jsonFailed
        }

        // For debugging purposes
        // Alert fake = new Alert();
        // fake.city = "אבו סנאן";
        // fake.date = 1562037706;
        // fake.threat = "missiles";

        // recentAlerts.add(fake);

        // Group alerts within 3-minute range and same threat type
        recentAlerts = groupAlerts(recentAlerts)

        // Localize only grouped alerts
        localizeGroupedAlerts(recentAlerts)

        // Clear global list
        mNewAlerts.clear()

        // Add all the new alerts
        mNewAlerts.addAll(recentAlerts)

        // Success
        return 0
    }

    private fun groupAlerts(alerts: List<Alert>): MutableList<Alert> {
        // Prepare grouped alerts list
        val groupedAlerts: MutableList<Alert> = ArrayList()

        // Keep track of last alert item added to list
        var lastAlert: Alert? = null

        // Alert grouping date cutoff threshold (seconds)
        val dateGroupingThreshold = 3 * 60

        // Traverse elements
        for (i in alerts.indices) {
            // Current element
            val current = alerts[i]

            // Check if can group the current and the last alert
            if (lastAlert != null &&
                    current.date >= lastAlert.date - dateGroupingThreshold && current.date <= lastAlert.date + dateGroupingThreshold &&
                    current.threat == lastAlert.threat) {
                // Lazily allocate lists
                if (lastAlert.groupedAlerts == null) {
                    lastAlert.groupedAlerts = ArrayList<Alert>(4)
                    lastAlert.groupedCities = ArrayList<String>(4)
                }

                // Add current alert object to grouped alerts list
                lastAlert.groupedAlerts.add(current)
                lastAlert.groupedCities.add(current.city)

                // Save first grouped alert timestamp for later use (alert refresh)
                if (current.date != lastAlert.date) {
                    lastAlert.firstGroupedAlertTimestamp = current.date
                }
            } else {
                // New alert (not grouped with previous item)
                current.groupedAlerts = ArrayList<Alert>(4)
                current.groupedCities = ArrayList<String>(4)

                // Add itself as the only "grouped" alert
                current.groupedAlerts.add(current)
                current.groupedCities.add(current.city)

                // Add to final result list
                groupedAlerts.add(current)

                // Set last as current
                lastAlert = current
            }
        }

        // Return grouped alerts
        return groupedAlerts
    }

    private fun localizeGroupedAlerts(alerts: List<Alert>) {
        // Cache locale
        val isArabic = Localization.isArabic(this)

        // Traverse alerts
        for (alert in alerts) {
            // Get relative date string for this alert
            alert.dateString = LocationData.getAlertDateTimeString(alert.date, alert.firstGroupedAlertTimestamp, this)

            // Localize threat once
            alert.localizedThreat = LocationData.getLocalizedThreatType(alert.threat, this)

            // Prepare unique hash maps of cities and descriptions
            val uniqueCities = LinkedHashMap<String, String>()
            val uniqueDescriptions = LinkedHashMap<String, String>()

            // Traverse grouped cities
            for (city in alert.groupedCities) {
                // Localize city name
                var localizedCity = LocationData.getLocalizedCityName(city, this)

                // Localize zone
                val desc = LocationData.getLocalizedZoneByCityName(city, this)

                // Check whether city should be highlighted in bold
                if (AlertLogic.isCitySelectedPrimarily(city, true, this)
                        || AlertLogic.isNearby(city, this)
                        || AlertLogic.isSecondaryCitySelected(city, true, this)) {
                    localizedCity = "<b>$localizedCity</b>"
                }

                // Ensure each city only appears once
                uniqueCities[city] = localizedCity

                // Description exists?
                if (!StringUtils.stringIsNullOrEmpty(desc)) {
                    // Get zone name by city
                    val zoneName = LocationData.getLocalizedZoneByCityName(city, this)

                    // Only add description if this zone name hasn't been added yet
                    if (!uniqueDescriptions.containsKey(zoneName)) {
                        uniqueDescriptions[zoneName] = desc
                    }
                }
            }

            // Convert hash maps to lists
            val localizedCities: MutableList<String> = ArrayList(uniqueCities.values)
            val descriptionsList: MutableList<String> = ArrayList(uniqueDescriptions.values)

            // Sort city & zone names alphabetically
            Collections.sort(localizedCities)
            Collections.sort(descriptionsList)

            // Join lists into CSV strings
            alert.localizedCity = TextUtils.join(", ", localizedCities)
            alert.desc = TextUtils.join(", ", descriptionsList)

            // Get grouped city count
            val groupedCityCount = localizedCities.size

            // Display {threat} • {count} Cities instead of entire list of cities
            alert.localizedTitle = alert.localizedThreat

            // If less than 15 cities, display all city names in large font
            if (groupedCityCount >= 15) {
                // Display {threat} • {count} Cities instead of entire list of cities
                alert.localizedTitle = "${alert.localizedThreat} • $groupedCityCount ${getString(R.string.selectedCities)}"

                // Convert Arabic numerals to digits if needed
                if (isArabic) {
                    alert.localizedTitle = Localization.localizeDigits(alert.localizedTitle, this)
                }
            }
        }

        // Preserve expanded state based on date
        for (displayAlert in mDisplayAlerts) {
            // Expanded?
            if (displayAlert.isExpanded) {
                // Check for this alert in the new list
                for (newAlert in alerts) {
                    // Same date?
                    if (newAlert.date == displayAlert.date) {
                        // Set as expanded
                        newAlert.isExpanded = true

                        // Stop after finding the first match
                        break
                    }
                }
            }
        }
    }

    private fun checkForUpdates(): String? {
        // Grab the update JSON
        val updateJson: String

        try {
            // Get it from /update/android
            updateJson = HTTP.get("/update/android")
        }
        catch (exc: Exception) {
            // Log it
            Log.e(Logging.TAG, "Get update info failed", exc)

            // Return error code
            return null
        }

        // Convert into JSON
        val updateInfo: VersionInfo

        try {
            // Convert JSON to object
            updateInfo = Singleton.getJackson().readValue(updateJson, VersionInfo::class.java)
        }
        catch (exc: Exception) {
            // Log it
            Log.e(Logging.TAG, "Parsing update info failed", exc)

            // Stop execution
            return null
        }

        // Don't show update dialog?
        if (!updateInfo.showDialog) {
            return null
        }

        // Update available? (Higher version code)
        if (updateInfo.versionCode > AppVersion.getVersionCode(this)) {
            // Return newer version string for display
            return updateInfo.version
        }

        // No need to update
        return null
    }

    private fun toggleProgressBarVisibility(visibility: Boolean) {
        // Set loading visibility
        mLoadingItem?.isVisible = visibility
    }

    override fun onDestroy() {
        // Countdown timer running?
        if (mRunnable != null) {
            mHandler.removeCallbacks(mRunnable!!)
        }

        // Unregister for broadcasts
        Broadcasts.unsubscribe(this, mBroadcastListener)

        // Drop view binding reference before activity teardown
        binding = null

        // Destroy activity
        super.onDestroy()
    }

    private fun invalidateAlertList() {
        // Workaround for ConcurrentModificationException
        // Wait for reloading to complete
        if (mIsReloading) {
            return
        }

        // Clear global list
        mDisplayAlerts.clear()

        // Check if user cleared the recent alerts
        val cutoffTimestamp = AppPreferences.getRecentAlertsCutoffTimestamp(this@Main)

        // Remove alerts that occurred before the cutoff
        if (cutoffTimestamp > 0) {
            // Traverse alerts
            for (alert in mNewAlerts) {
                // Check if alert date is after cutoff timestamp
                if (alert.date > cutoffTimestamp) {
                    // Add to display list
                    mDisplayAlerts.add(alert)
                }
            }
        }
        else {
            // No alert date cutoff, add all the new alerts
            mDisplayAlerts.addAll(mNewAlerts)
        }

        // Invalidate the list
        mAlertsAdapter.notifyDataSetChanged()

        // No alerts? Show default
        if (mDisplayAlerts.size == 0) {
            mNoAlerts.visibility = View.VISIBLE
        }
        else {
            mNoAlerts.visibility = View.GONE
        }

        // Update displayed icon according to how many alerts were returned from the server
        updateClearAlertsButton(cutoffTimestamp)
    }

    private fun updateClearAlertsButton(cutoffTimestamp: Long) {
        // Null pointer check
        val clearItem = mClearRecentAlertsItem

        if (clearItem == null) {
            return
        }

        // By default, show clear button
        clearItem.isVisible = true

        // In case no alerts are being displayed but alerts were returned
        if (cutoffTimestamp > 0 && mNewAlerts.size > 0 && mDisplayAlerts.size == 0) {
            // Show restore icon to allow user to restore all recent alerts
            clearItem.setIcon(R.drawable.ic_restore)
        }
        // In case there are no alerts in the past 24 hours
        else if (mNewAlerts.size == 0) {
            // Hide clear button
            clearItem.isVisible = false
        }
        else {
            // There are alerts, show default clear icon
            clearItem.setIcon(R.drawable.ic_clear)
        }
    }

    private fun showRegistrationSuccessDialog() {
        // Already displayed?
        if (AppPreferences.getTutorialDisplayed(this)) {
            return
        }

        // Ask user to select their city
        var desc = getString(R.string.pushRegistrationSuccessDesc)

        // Check if upgrading from previous version
        if (Singleton.getSharedPreferences(this).getBoolean("tutorial_1_0_23", false)) {
            // Ask user to reselect
            desc = getString(R.string.pushRegistrationReselectDesc)
        }

        // Build the dialog
        AlertDialogBuilder.showGenericDialog(getString(R.string.pushRegistrationSuccess), desc, getString(R.string.okay), null, false, this, DialogInterface.OnClickListener { _, _ ->
            // Start settings activity
            goToSettings(true)
        })

        // Tutorial was displayed
        AppPreferences.setTutorialDisplayed(this)
    }

    inner class GetRecentAlertsAsync : AsyncTaskAdapter<Int, String, Int>() {
        init {
            // Prevent concurrent reload
            mIsReloading = true

            // Show loading indicator
            toggleProgressBarVisibility(true)
        }

        override fun doInBackground(vararg parameter: Int?): Int? {
            // Try to get recent alerts
            return getRecentAlerts()
        }

        override fun onPostExecute(errorStringResource: Int?) {
            // No longer reloading
            mIsReloading = false

            // Activity dead?
            if (isFinishing || isDestroyed) {
                return
            }

            // Hide loading
            mLoading.visibility = View.GONE

            // Hide loading indicator
            toggleProgressBarVisibility(false)

            // Success?
            if (errorStringResource == 0) {
                // Invalidate the list
                invalidateAlertList()
            }
            // No new alerts?
            else if (errorStringResource == R.string.noNewAlerts) {
                // Invalidate the list (for relative time ago to be refreshed)
                mAlertsAdapter.notifyDataSetChanged()
            }
            else {
                // Show error toast
                Toast.makeText(this@Main, getString(errorStringResource!!), Toast.LENGTH_LONG).show()
            }
        }
    }

    inner class RegisterPushAsync : AsyncTaskAdapter<Int, String, Exception>() {
        val mLoading: ProgressDialog

        init {
            // Prevent concurrent registration
            mIsRegistering = true

            // Set push tokens as refreshed
            mPushTokensRefreshed = true

            // Fix progress dialog appearance on old devices
            mLoading = ProgressDialogCompat.getStyledProgressDialog(this@Main)

            // Prevent cancel
            mLoading.setCancelable(false)

            // Set default message
            mLoading.setMessage(getString(R.string.signing_up))

            // First time registering?
            if (!FCMRegistration.isRegistered(this@Main) || !PushyRegistration.isRegistered(this@Main)) {
                // Show the progress dialog
                mLoading.show()
            }
        }

        override fun doInBackground(vararg parameter: Int?): Exception? {
            // Make sure we have Google Play Services installed
            if (!GooglePlayServices.isAvailable(this@Main)) {
                return Exception("This app requires Google Play Services.")
            }

            // Keep track of previously-saved Pushy & FCM tokens to detect changes and update the API
            val previousPushyToken = PushyRegistration.getRegistrationToken(this@Main)
            val previousFirebaseToken = FCMRegistration.getRegistrationToken(this@Main)

            // Retry mechanism (for temporary network errors)
            var tries = 0

            // Retry up to 5 times
            while (tries <= 5) {
                try {
                    // Increment tries
                    tries++

                    // Register for Pushy push notifications
                    val pushyToken = PushyRegistration.registerForPushNotifications(this@Main)

                    // Register for FCM push notifications
                    val fcmToken = FCMRegistration.registerForPushNotifications(this@Main)

                    // First time registering with the API?
                    if (!RedAlertAPI.isRegistered(this@Main)) {
                        // Register with RedAlert API and store user ID & hash
                        RedAlertAPI.register(fcmToken, pushyToken, this@Main)
                    } else {
                        // FCM or Pushy token have changed?
                        if ((previousFirebaseToken != null && previousFirebaseToken != fcmToken) ||
                                (previousPushyToken != null && previousPushyToken != pushyToken)) {
                            // Update token server-side
                            RedAlertAPI.updatePushTokens(fcmToken, pushyToken, this@Main)
                        }
                    }

                    // First time subscribing with the API?
                    if (!RedAlertAPI.isSubscribed(this@Main)) {
                        // Update Pub/Sub subscriptions
                        PushManager.updateSubscriptions(this@Main)

                        // Update notification preferences
                        RedAlertAPI.updateNotificationPreferences(this@Main)

                        // Subscribe for alerts based on current city/region selections
                        RedAlertAPI.subscribe(this@Main)
                    }

                    // If we're here, success
                    break
                } catch (exc: Exception) {
                    // Workaround for FCM error "Invalid argument for the given fid"
                    if (exc.message != null && exc.message!!.contains("Invalid argument for the given fid")) {
                        try {
                            // Try to delete Firebase Installations ID
                            Tasks.await(FirebaseInstallations.getInstance().delete())
                        } catch (e: Exception) {
                            // Log failure
                            Log.e(Logging.TAG, "Firebase installation deletion failed", e)
                        }
                    }

                    // Throw exception after 5 tries
                    if (tries > 5) {
                        return exc
                    }
                    else {
                        // Log error
                        Log.e(Logging.TAG, "Push registration failed, retrying...", exc)

                        try {
                            // Wait a second and try again
                            Thread.sleep(1000)
                        }
                        catch (e: InterruptedException) {}
                    }
                }
            }

            // Success
            return null
        }

        override fun onPostExecute(exc: Exception?) {
            // No longer registering
            mIsRegistering = false

            // Activity dead?
            if (isFinishing || isDestroyed) {
                return
            }

            // Hide loading
            if (mLoading.isShowing) {
                mLoading.dismiss()
            }

            // Failed?
            if (exc != null) {
                // Log it
                Log.e(Logging.TAG, "Push registration failed", exc)

                // Build an error message
                val errorMessage = "${getString(R.string.pushRegistrationFailed)}\n\n${exc.message}${if (exc.cause != null) "\n\n${exc.cause}" else ""}"

                // Build the dialog
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay), null, false, this@Main, null)
            }
            else {
                // Success, show success dialog if haven't yet
                showRegistrationSuccessDialog()
            }
        }
    }

    inner class CheckForUpdatesAsync : AsyncTaskAdapter<Int, String, String>() {
        override fun doInBackground(vararg parameter: Int?): String? {
            // Try to check for updates
            return checkForUpdates()
        }

        override fun onPostExecute(newVersion: String?) {
            // Activity dead?
            if (isFinishing || isDestroyed) {
                return
            }

            // Got a return code?
            if (!StringUtils.stringIsNullOrEmpty(newVersion)) {
                // Show update dialog
                UpdateDialogs.showUpdateDialog(this@Main, newVersion)
            }
        }
    }

    private fun handleNotificationClick(intent: Intent) {
        // Extract clicked notification params
        val cities = intent.getStringArrayExtra(AlertPopupParameters.CITIES)
        val threatType = intent.getStringExtra(AlertPopupParameters.THREAT_TYPE)
        val instructions = intent.getStringExtra(AlertPopupParameters.INSTRUCTIONS)
        val timestamp = intent.getLongExtra(AlertPopupParameters.TIMESTAMP, 0)

        // Display popup if all necessary parameters are set
        if (cities != null && cities.size > 0 && threatType != null && timestamp > 0) {
            // Fetch highest priority countdown in seconds for given alert cities list
            val countdown = LocationData.getPrioritizedCountdownForCities(cities, this)

            // Check for old (inactive) alert
            // Only display alert popup for currently active alerts (+ 10 minutes)
            if (threatType != ThreatTypes.EARLY_WARNING && threatType != ThreatTypes.LEAVE_SHELTER && timestamp < (DateTime.getUnixTimestamp() - countdown - (Safety.POST_IMPACT_WAIT_MINUTES * 60))) {
                return
            }

            // Create new popup intent
            val popupIntent = Intent()

            // Set class to popup activity
            popupIntent.setClass(this, Popup::class.java)

            // Pass on city name, threat type, and alert received timestamp
            popupIntent.putExtra(AlertPopupParameters.CITIES, cities)
            popupIntent.putExtra(AlertPopupParameters.TIMESTAMP, timestamp)
            popupIntent.putExtra(AlertPopupParameters.THREAT_TYPE, threatType)
            popupIntent.putExtra(AlertPopupParameters.INSTRUCTIONS, instructions)

            // Clear top, set as new task
            popupIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            popupIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

            // Delay popup by 300ms to allow main activity UI to finish rendering
            mHandler.postDelayed({
                // Display popup activity
                startActivity(popupIntent)
            }, 300)

            // Get intent extras
            val extras = intent.extras

            // Clear intent extras to avoid showing same popup again on re-entering activity
            if (extras != null) {
                extras.clear()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Just granted location permission?
        if (requestCode == LocationLogic.LOCATION_PERMISSION_REQUEST_CODE && grantResults.size > 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            // Location alerts enabled?
            if (AppPreferences.getLocationAlertsEnabled(this)) {
                // Start the location service
                ServiceManager.startLocationService(this)
            }
        }
    }
                }
