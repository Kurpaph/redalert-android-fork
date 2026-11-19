package com.red.alert.utils.communication

import android.content.Context
import android.content.SharedPreferences

import com.red.alert.utils.caching.Singleton

object Broadcasts {
    @JvmStatic
    fun subscribe(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        // Listen for preference changes
        Singleton.getSharedPreferences(context).registerOnSharedPreferenceChangeListener(listener)
    }

    @JvmStatic
    fun unsubscribe(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        // Unsubscribe from preference changes
        Singleton.getSharedPreferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    @JvmStatic
    fun publish(context: Context, property: String) {
        // Edit shared preferences
        val edit: SharedPreferences.Editor = Singleton.getSharedPreferences(context).edit()

        // Store current ms in settings with the given property,
        // so that listeners will be notified
        edit.putLong(property, System.currentTimeMillis())

        // Save and flush
        edit.commit()
    }
}
