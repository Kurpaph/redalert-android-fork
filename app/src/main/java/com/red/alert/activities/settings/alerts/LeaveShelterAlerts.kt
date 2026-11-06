package com.red.alert.activities.settings.alerts

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem

import com.red.alert.R
import com.red.alert.ui.activities.AppCompatPreferenceActivity
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.utils.feedback.Volume
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.ui.NavbarUtil

class LeaveShelterAlerts : AppCompatPreferenceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Load UI elements
        initializeUI()

        // Volume keys should control alert volume
        Volume.setVolumeKeysAction(this)
    }

    override fun onResume() {
        super.onResume()

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)
    }

    override fun attachBaseContext(base: Context) {
        // Reapply locale
        Localization.overridePhoneLocale(base)
        super.attachBaseContext(base)
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
        addPreferencesFromResource(R.xml.settings_leave_shelter_alerts)

        // Fix nav bar color and styling
        NavbarUtil.fixPreferenceActivityNavbarColor(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Check item ID
        when (item.itemId) {
            // Home button?
            android.R.id.home -> onBackPressed()
        }

        return super.onOptionsItemSelected(item)
    }
}
