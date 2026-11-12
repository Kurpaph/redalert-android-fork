package com.red.alert.services.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log

import androidx.core.app.NotificationCompat

import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.red.alert.R
import com.red.alert.activities.settings.alerts.LocationAlerts
import com.red.alert.config.Logging
import com.red.alert.config.NotificationChannels
import com.red.alert.logic.communication.broadcasts.LocationAlertsEvents
import com.red.alert.logic.location.LocationLogic
import com.red.alert.utils.communication.Broadcasts
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.metadata.LocationData

import me.pushy.sdk.config.PushyForegroundService

class LocationService : Service(),
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener {
    private var mServiceBinder: IBinder? = null
    private var mClient: GoogleApiClient? = null
    private var mLocationRequest: LocationRequest? = null
    private var mForegroundStarted = false

    override fun onCreate() {
        super.onCreate()

        // Check if all prerequisites are met
        if (!LocationLogic.canStartForegroundLocationService(this)) {
            stopSelf()
            return
        }

        // Start foreground service
        // API level 34 support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // Promote to foreground with location FGS type on Android 14+
                startForeground(PushyForegroundService.FOREGROUND_NOTIFICATION_ID, getForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

                // Record foreground so onDestroy can remove notification and detach
                mForegroundStarted = true
            }
            catch (exc: Exception) {
                // Android 14 may still occasionally throw a
                // SecurityException or ForegroundServiceStartNotAllowedException
                // Even throw we have all required permissions
                // And we're starting the service from an allowed place
                stopSelf()
                return
            }
        } else {
            // Promote to foreground on older API levels
            startForeground(PushyForegroundService.FOREGROUND_NOTIFICATION_ID, getForegroundNotification())

            // Record foreground so onDestroy can remove notification and detach
            mForegroundStarted = true
        }

        // Initialize location polling
        initializeLocationPolling()

        // Initialize binder
        mServiceBinder = LocalBinder()
    }

    private fun initializeLocationPolling() {
        // Create new request
        val request = LocationRequest.create()
        mLocationRequest = request

        // Low battery mode
        request.setPriority(LocationRequest.PRIORITY_LOW_POWER)

        // Set update intervals
        request.setInterval(LocationLogic.getUpdateIntervalMilliseconds(this))

        // Create new location receive client
        mClient = GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build()

        // Listen for GPS sensor enable/disable event
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        filter.addAction(Intent.ACTION_PROVIDER_CHANGED)

        // API level 34 support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(locationProvidersChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(locationProvidersChangedReceiver, filter)
        }

        // Write to log
        Log.d(Logging.TAG, "LocationService started, polling every ${LocationLogic.getUpdateIntervalMilliseconds(this) / 1000 / 60} minute(s)")
    }

    fun updateLocationServiceParams() {
        // Called when not requesting?
        val request = mLocationRequest

        if (request == null) {
            return
        }

        // Set new polling interval
        request.setInterval(LocationLogic.getUpdateIntervalMilliseconds(this))

        // Re-generate notification in case max distance was changed
        updateForegroundServiceNotification()

        // Write to log
        Log.d(Logging.TAG, "LocationService updated, polling every ${LocationLogic.getUpdateIntervalMilliseconds(this) / 1000 / 60} minute(s)")
    }

    private fun connectLocationClient() {
        // Got a location client?
        val client = mClient

        if (client != null) {
            // Client isn't already connected?
            if (!client.isConnected || !client.isConnecting) {
                // Ask for updates
                client.connect()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check if all prerequisites are met
        if (!LocationLogic.canStartForegroundLocationService(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground service
        // API level 34 support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // Ensure foreground notification is current when onStartCommand runs again
                startForeground(PushyForegroundService.FOREGROUND_NOTIFICATION_ID, getForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

                // Mark foreground in case onCreate took an early stopSelf path without startForeground
                mForegroundStarted = true
            }
            catch (exc: Exception) {
                // Android 14 may still occasionally throw a
                // SecurityException or ForegroundServiceStartNotAllowedException
                // Even throw we have all required permissions
                // And we're starting the service from an allowed place
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            // Ensure foreground notification is current when onStartCommand runs again
            startForeground(PushyForegroundService.FOREGROUND_NOTIFICATION_ID, getForegroundNotification())

            // Mark foreground in case onCreate took an early stopSelf path without startForeground
            mForegroundStarted = true
        }

        // Try connecting
        connectLocationClient()

        // Sticky service!
        return START_STICKY
    }

    private fun disconnectClient() {
        // Got a client?
        val client = mClient

        if (client != null && client.isConnected) {
            try {
                // Stop asking for updates
                LocationServices.FusedLocationApi.removeLocationUpdates(client, this@LocationService)

                // Disconnect client
                client.disconnect()
            } catch (exc: Exception) {
                // Log to logcat
                Log.e(Logging.TAG, "LocationClient disconnect failed", exc)
            }
        }

        try {
            // Unregister GPS state change receiver
            unregisterReceiver(locationProvidersChangedReceiver)
        } catch (e: Exception) {
            // Ignore exceptions
        }
    }

    private fun reconnect() {
        // First, try disconnecting
        disconnectClient()

        // Connect again
        connectLocationClient()
    }

    override fun onDestroy() {
        // Got a client?
        disconnectClient()

        // Remove foreground notification and detach if we ever entered foreground
        if (mForegroundStarted) {
            // API 24+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Remove notification and exit foreground (replaces deprecated stopForeground(true))
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                // Older APIs: remove notification when leaving foreground
                stopForeground(true)
            }

            // Clear so a hypothetical re-create does not double-stopForeground
            mForegroundStarted = false
        }

        // Write to log
        Log.d(Logging.TAG, "LocationService stopped")

        // Now we're ready to be destroyed
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        // Get latitude & longitude as float
        val latitude = location.latitude.toFloat()
        val longitude = location.longitude.toFloat()

        // Save to preferences
        LocationLogic.saveLastKnownLocation(this, latitude, longitude)

        // Reload nearby cities in case we're in the Location Alerts settings page
        Broadcasts.publish(this, LocationAlertsEvents.LOCATION_RECEIVED)

        // Write to log
        Log.d(Logging.TAG, "Location: $latitude, $longitude")

        // Update foreground service notification
        updateForegroundServiceNotification()
    }

    @SuppressLint("MissingPermission")
    override fun onConnected(dataBundle: Bundle?) {
        // No client?
        val client = mClient
        val request = mLocationRequest

        if (client == null || request == null) {
            Log.e(Logging.TAG, "Location API client or request is null")
            return
        }

        // One last permission check
        if (!LocationLogic.isLocationAccessGranted(this)) {
            Log.e(Logging.TAG, "Location access not granted")
            return
        }

        try {
            // Request updates
            LocationServices.FusedLocationApi.requestLocationUpdates(client, request, this)
        }
        catch (exc: Exception) {
            // Log it
            Log.e(Logging.TAG, "FusedLocationApi connection failed", exc)

            // Try reconnecting
            reconnect()
        }
    }

    private val locationProvidersChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (LocationManager.PROVIDERS_CHANGED_ACTION == intent.action) {
                // Re-generate notification as GPS may have been disabled or enabled
                updateForegroundServiceNotification()
            }
        }
    }

    override fun onConnectionSuspended(i: Int) {
        // Log error
        Log.e(Logging.TAG, "Location API connection suspended")
    }

    override fun onConnectionFailed(connectionResult: ConnectionResult) {
        // Log error
        Log.e(Logging.TAG, "Location API connection failed: ${connectionResult.errorMessage}")

        // Try reconnecting
        reconnect()
    }

    override fun onBind(arg0: Intent?): IBinder? {
        // Provide service binder
        return mServiceBinder
    }

    inner class LocalBinder : Binder() {
        // Return the instance
        val service: LocationService
            get() = this@LocationService
    }

    private fun updateForegroundServiceNotification() {
        // Get an instance of the NotificationManager service
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Refresh the notification
        notificationManager.notify(PushyForegroundService.FOREGROUND_NOTIFICATION_ID, getForegroundNotification())
    }

    private fun getForegroundNotification(): Notification {
        // Ensure the right language is displayed
        Localization.overridePhoneLocale(this)

        // Location alerts activity pending intent
        val launcherIntent = PendingIntent.getActivity(this, 0, Intent(this, LocationAlerts::class.java), PendingIntent.FLAG_IMMUTABLE)

        // Build notification title & desc
        val title = getString(R.string.locationAlerts)
        val text: String

        // Get current location
        val location = LocationLogic.getCurrentLocation(this)

        // Location permission revoked?
        if (!LocationLogic.isLocationAccessGranted(this)) {
            // Show error
            text = getString(R.string.enableGPSDesc)
        }

        // No recent location?
        else if (location == null) {
            // Show error
            text = getString(R.string.noLocation)
        }
        else {
            // Get nearby cities
            val nearbyCities = LocationData.getNearbyCityNames(location, this)

            // At least one city?
            if (!StringUtils.stringIsNullOrEmpty(nearbyCities)) {
                text = "${getString(R.string.nearbyCities)}: $nearbyCities"

                // Write to log
                Log.d(Logging.TAG, "Nearby cities: $nearbyCities")
            }
            else {
                // Show error message
                text = getString(R.string.noNearbyCities)

                // Write to log
                Log.d(Logging.TAG, "No nearby cities")
            }
        }

        // Android O and newer requires notification channel to be created prior to dispatching a notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create channel
            val channel = NotificationChannel(NotificationChannels.LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_ID, NotificationChannels.LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)

            // Some devices may need us to explicitly disable sound, even for IMPORTANCE_LOW
            channel.setSound(null, null)

            // Disable badge number
            channel.setShowBadge(false)

            // Register the channel with the system
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            // Delete old notification channel
            notificationManager.deleteNotificationChannel(NotificationChannels.LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_OLD_ID)
        }

        // Create foreground notification
        val notification = NotificationCompat.Builder(this, NotificationChannels.LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_ID)
                .setGroup(NotificationChannels.LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_location_service)
                .setColor(Color.TRANSPARENT)
                .setPriority(Notification.PRIORITY_LOW)
                .setContentIntent(launcherIntent)
                .setSound(null)
                .build()

        // All done
        return notification
    }
}
