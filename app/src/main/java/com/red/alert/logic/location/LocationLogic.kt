package com.red.alert.logic.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import com.red.alert.R
import com.red.alert.config.LocationAlerts
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.integration.GooglePlayServices

object LocationLogic {
    @JvmField
    var LOCATION_PERMISSION_REQUEST_CODE = 1000

    @JvmStatic
    fun getUpdateIntervalMinutes(context: Context, overrideSetting: Float): Int {
        // Get stored value
        var sliderValue = Singleton.getSharedPreferences(context).getFloat(context.getString(R.string.gpsFrequencyPref), context.getString(R.string.defaultGPSPollingFrequency).toFloat())

        // Override it?
        if (overrideSetting != -1f) {
            sliderValue = overrideSetting
        }

        // Calculate frequency in minutes
        val frequencyMin = (LocationAlerts.MAX_FREQUENCY_MINUTES * sliderValue).toInt()

        // Return it
        return frequencyMin
    }

    @JvmStatic
    fun getMaxDistanceKilometers(context: Context, overrideSetting: Float): Int {
        // Get stored value
        var sliderValue = Singleton.getSharedPreferences(context).getFloat(context.getString(R.string.maxDistancePref), context.getString(R.string.defaultGPSMaxDistance).toFloat())

        // Override it?
        if (overrideSetting != -1f) {
            sliderValue = overrideSetting
        }

        // Calculate frequency in minutes
        val maxDistance = (LocationAlerts.MAX_DISTANCE_KM * sliderValue).toInt()

        // Return it
        return maxDistance
    }

    @JvmStatic
    fun getUpdateIntervalMilliseconds(context: Context): Long {
        // Convert to milliseconds
        return (getUpdateIntervalMinutes(context, -1f) * 60 * 1000).toLong()
    }

    @JvmStatic
    fun saveLastKnownLocation(context: Context, latitude: Float, longitude: Float) {
        // Get shared preferences
        val preferences = Singleton.getSharedPreferences(context)

        // Edit preferences
        val editor: SharedPreferences.Editor = preferences.edit()

        // Store latitude & longitude
        editor.putFloat(context.getString(R.string.latitudePref), latitude)
        editor.putFloat(context.getString(R.string.longitudePref), longitude)

        // Commit changes
        editor.commit()
    }

    @JvmStatic
    fun getCurrentLocation(context: Context): Location? {
        // Get shared preferences
        val preferences = Singleton.getSharedPreferences(context)

        // Load saved latitude and longitude from SharedPreferences
        val latitude = preferences.getFloat(context.getString(R.string.latitudePref), 0f).toDouble()
        val longitude = preferences.getFloat(context.getString(R.string.longitudePref), 0f).toDouble()

        // No location stored?
        if (latitude == 0.0) {
            return GetLastKnownLocation(context)
        }

        // Prepare new location object
        val location = Location(LocationManager.PASSIVE_PROVIDER)

        // Set latitude & longitude
        location.latitude = latitude
        location.longitude = longitude

        // Return it
        return location
    }

    @SuppressLint("MissingPermission")
    @JvmStatic
    fun GetLastKnownLocation(context: Context): Location? {
        // No permission?
        if (!isLocationAccessGranted(context)) {
            return null
        }

        // Get location manager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Create location criteria
        val criteria = Criteria()

        // Set coarse accuracy
        criteria.accuracy = Criteria.ACCURACY_COARSE

        // Get best provider
        val bestProvider = locationManager.getBestProvider(criteria, false)

        // No provider?
        if (bestProvider == null) {
            // Return null
            return null
        }

        // Return last location
        return locationManager.getLastKnownLocation(bestProvider)
    }

    @JvmStatic
    fun isGPSEnabled(context: Context): Boolean {
        // Get location manager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // GPS enabled?
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @JvmStatic
    fun isLocationPermissionGranted(context: Context): Boolean {
        // Check if location permissions are granted
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    @JvmStatic
    fun requestLocationPermission(context: Activity) {
        // Display location permission dialog
        ActivityCompat.requestPermissions(context,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE)
    }

    @JvmStatic
    fun isLocationAccessGranted(context: Context): Boolean {
        // Check if GPS sensor enabled and location permissions are granted (Android 10+)
        return isGPSEnabled(context) && isLocationPermissionGranted(context)
    }

    @JvmStatic
    fun canStartForegroundLocationService(context: Context): Boolean {
        // Must have Google Play Services
        if (!GooglePlayServices.isAvailable(context)) {
            return false
        }

        // Interval set to 0?
        if (LocationLogic.getUpdateIntervalMilliseconds(context) == 0L) {
            return false
        }

        // GPS sensor disabled?
        // API level 34 specific behavior
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !LocationLogic.isGPSEnabled(context)) {
            // Don't try to start a foreground service
            // as it will throw a SecurityException
            return false
        }

        // Check if the user revoked location permission
        // Must have location permission to continue
        if (!LocationLogic.isLocationPermissionGranted(context)) {
            return false
        }

        // We should be good to go
        return true
    }

    @JvmStatic
    fun showLocationAccessRequestDialog(context: Activity) {
        // GPS sensor disabled?
        if (!isGPSEnabled(context)) {
            // Show a dialog to the user
            AlertDialogBuilder.showGenericDialog(context.getString(R.string.locationAlerts), context.getString(R.string.enableGPSDesc), context.getString(R.string.okay), context.getString(R.string.notNow), true, context, DialogInterface.OnClickListener { _, which ->
                // Clicked okay?
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Display the GPS setting screen
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            })
        }

        // Check if location permissions need to be granted
        else if (!isLocationPermissionGranted(context)) {
            // Request location permission
            requestLocationPermission(context)
        }
    }
}
