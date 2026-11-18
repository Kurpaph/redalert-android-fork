package com.red.alert.utils.caching

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

import me.pushy.sdk.lib.jackson.databind.DeserializationFeature
import me.pushy.sdk.lib.jackson.databind.ObjectMapper

object Singleton {
    private var mMapper: ObjectMapper? = null
    private var mSettings: SharedPreferences? = null

    @JvmStatic
    fun getSharedPreferences(context: Context): SharedPreferences {
        // First time?
        var settings = mSettings

        if (settings == null) {
            // Open shared preferences
            settings = PreferenceManager.getDefaultSharedPreferences(context)
            mSettings = settings
        }

        // Return cached object
        return settings!!
    }

    @JvmStatic
    fun getJackson(): ObjectMapper {
        // First time?
        var mapper = mMapper

        if (mapper == null) {
            // Get Jackson instance
            mapper = ObjectMapper()

            // Ignore unknown properties
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            mMapper = mapper
        }

        // Return cached object
        return mapper
    }
}
