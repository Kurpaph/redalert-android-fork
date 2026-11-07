package com.red.alert.activities

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

import com.red.alert.R
import com.red.alert.databinding.PopupBinding
import com.red.alert.config.Alerts
import com.red.alert.config.Safety
import com.red.alert.config.ThreatTypes
import com.red.alert.logic.alerts.AlertTypes
import com.red.alert.logic.communication.intents.AlertPopupParameters
import com.red.alert.logic.phone.PowerManagement
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.ui.notifications.AppNotifications
import com.red.alert.utils.feedback.Volume
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.localization.DateTime
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.metadata.LocationData
import com.red.alert.utils.ui.DensityUtil

class Popup : AppCompatActivity() {
    private val mHandler = Handler(Looper.getMainLooper())

    // Inflated layout binding for popup.xml (replaces findViewById)
    private var binding: PopupBinding? = null

    private var mRunnable: Runnable? = null

    private lateinit var mCities: TextView
    private lateinit var mCounter: TextView
    private lateinit var mInstructions: TextView

    private lateinit var mClose: Button
    private lateinit var mSilence: Button

    private lateinit var mThreatIcon: ImageView

    private val timeBuffer = charArrayOf('0', '0', ':', '0', '0')

    companion object {
        @JvmStatic
        fun showAlertPopup(alertType: String, cities: List<String>, threatType: String?, instructions: String?, context: Context) {
            // Only display popup for primary/secondary alerts (no test/sound/system notifications)
            if (alertType != AlertTypes.PRIMARY && alertType != AlertTypes.SECONDARY) {
                return
            }

            // User hasn't enabled popup for primary alerts?
            if (alertType == AlertTypes.PRIMARY && !AppPreferences.getPopupEnabled(context)) {
                return
            }

            // User hasn't enabled popup for secondary alerts?
            if (alertType == AlertTypes.SECONDARY && !AppPreferences.getSecondaryPopupEnabled(context)) {
                return
            }

            // Android M: Check if we have permission to draw over other apps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                return
            }

            // Turn screen on
            PowerManagement.wakeUpScreen(context)

            // Create new popup intent
            val popupIntent = Intent()

            // Set class to popup activity
            popupIntent.setClass(context, Popup::class.java)

            // Pass on alert cities & threat type
            popupIntent.putExtra(AlertPopupParameters.THREAT_TYPE, threatType)
            popupIntent.putExtra(AlertPopupParameters.INSTRUCTIONS, instructions)
            popupIntent.putExtra(AlertPopupParameters.CITIES, cities.toTypedArray())

            // Clear top, set as new task
            popupIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            popupIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

            // Display popup activity
            context.startActivity(popupIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load UI elements
        initializeUI()

        // Populate UI elements
        initializeAlert(intent)

        // Volume keys should control alert volume
        Volume.setVolumeKeysAction(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Update popup with new alert info
        initializeAlert(intent)
    }

    private fun initializeUI() {
        // RTL action bar hack
        RTLSupport.mirrorActionBar(this)

        // Inflate layout with view binding
        binding = PopupBinding.inflate(layoutInflater)

        // Attach root to this activity
        setContentView(binding!!.root)

        // Cache UI objects from binding
        mClose = binding!!.close
        mCities = binding!!.cities
        mSilence = binding!!.silence
        mCounter = binding!!.counter
        mThreatIcon = binding!!.threatIcon
        mInstructions = binding!!.instructions

        // Allow scrolling on cities list
        mCities.movementMethod = ScrollingMovementMethod()

        // Set up listeners
        initializeListeners()
    }

    override fun attachBaseContext(base: Context) {
        // Reapply locale
        Localization.overridePhoneLocale(base)
        super.attachBaseContext(base)
    }

    private fun initializeListeners() {
        // Close button clicked
        mClose.setOnClickListener {
            // Stop playing sounds (including custom sounds)
            AppNotifications.clearAll(this@Popup)

            // Close popup
            finish()
        }

        // Silence button clicked
        mSilence.setOnClickListener {
            // Stop playing sounds (including custom sounds)
            AppNotifications.clearAll(this@Popup)
        }
    }

    private fun initializeAlert(intent: Intent) {
        // Get alert city, threat type, and alert timestamp
        val cities = intent.getStringArrayExtra(AlertPopupParameters.CITIES)
        val instructions = intent.getStringExtra(AlertPopupParameters.INSTRUCTIONS)
        val timestamp = intent.getLongExtra(AlertPopupParameters.TIMESTAMP, DateTime.getUnixTimestamp())

        // None given?
        if (cities == null || cities.isEmpty()) {
            return
        }

        // Threat type (declared after the early return so a missing value fails at the same point as in Java)
        val threatType = intent.getStringExtra(AlertPopupParameters.THREAT_TYPE)!!

        // Set localized threat type as popup title
        title = LocationData.getLocalizedThreatType(threatType, this)

        // Display threat icon, localized cities, safety instructions
        mThreatIcon.setImageResource(LocationData.getThreatDrawable(threatType))
        mCities.text = LocationData.getLocalizedCityNamesCSV(cities.toList(), this)
        mInstructions.text = LocationData.getLocalizedThreatInstructions(threatType, this)

        // HFC update with instructions?
        if ((threatType == ThreatTypes.EARLY_WARNING || threatType == ThreatTypes.LEAVE_SHELTER) && !StringUtils.stringIsNullOrEmpty(instructions)) {
            mInstructions.text = instructions
        }

        // System alert?
        if (threatType == ThreatTypes.SYSTEM) {
            // Hide instructions text as it's redundant
            mInstructions.visibility = View.GONE
        }

        // Not a missile / hostile aircraft intrusion alert?
        // Hide countdown timer
        if (!threatType.contains(ThreatTypes.MISSILES) && !threatType.contains(ThreatTypes.HOSTILE_AIRCRAFT_INTRUSION) && threatType != ThreatTypes.EARLY_WARNING && threatType != ThreatTypes.LEAVE_SHELTER) {
            // Countdown is only relevant for rocket fire
            mCounter.visibility = View.GONE
        }
        else {
            // Show countdown
            mCounter.visibility = View.VISIBLE

            // Fetch highest priority countdown in seconds for given alert cities list
            val countdown = LocationData.getPrioritizedCountdownForCities(cities, this)

            // Start counting down
            scheduleRocketCountdown(timestamp, countdown)

            // Hide countdown text for Early Warnings / Leave Shelter Alerts
            if (threatType == ThreatTypes.EARLY_WARNING || threatType == ThreatTypes.LEAVE_SHELTER) {
                // Countdown is only relevant for rocket fire
                mCounter.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        // Countdown timer running?
        if (mRunnable != null) {
            mHandler.removeCallbacks(mRunnable!!)
        }

        // Drop view binding reference before activity teardown
        binding = null

        // Destroy activity
        super.onDestroy()
    }

    private fun scheduleRocketCountdown(timestamp: Long, seconds: Int) {
        // Cancel previous timer if set
        if (mRunnable != null) {
            mHandler.removeCallbacks(mRunnable!!)
        }

        // Offset impact to account for delivery delay
        // Seconds = Seconds - Main.IMPACT_COUNTDOWN_OFFSET;

        // Calculate time to impact
        val impactTimestamp = (timestamp * 1000) + (seconds * 1000)

        // Run every 1000ms
        mRunnable = object : Runnable {
            override fun run() {
                // Activity died?
                if (isFinishing || isDestroyed) {
                    return
                }

                // Update countdown
                updateCountdownTimer(impactTimestamp)

                // Schedule for future execution
                mHandler.postDelayed(this, 1000)
            }
        }

        // Start running
        mHandler.post(mRunnable!!)
    }

    private fun updateCountdownTimer(impactTimestamp: Long) {
        // Get current time
        val currentTimestamp = System.currentTimeMillis()

        // Calculate seconds left to brace for impact
        val seconds = Math.abs((impactTimestamp - currentTimestamp) / 1000).toInt()

        // Did rocket already impact?
        if (currentTimestamp <= impactTimestamp) {
            // Convert it
            updateCountdownTimerText(seconds, R.color.colorCountdownPreImpact)
        }
        else if (currentTimestamp > impactTimestamp) {
            // Number of seconds to wait after impact
            val postImpactSeconds = (Safety.POST_IMPACT_WAIT_MINUTES * 60)

            // Stop counting after X minutes
            if (seconds >= postImpactSeconds) {
                // Show green counter
                updateCountdownTimerText(postImpactSeconds, R.color.colorCountdownPostImpactSafe)

                // Shut down activity after post impact + X seconds (padding)
                if (seconds >= postImpactSeconds + Alerts.ALERT_POPUP_DONE_PADDING) {
                    // Done with activity (shuts down screen as well)
                    finish()
                }

                // Stop execution
                return
            }

            // Convert it
            updateCountdownTimerText(seconds, R.color.colorCountdownPostImpact)
        }
    }

    private fun updateCountdownTimerText(seconds: Int, color: Int) {
        // Get human-readable minutes
        val minutes = seconds / 60

        // Convert seconds to support the minutes
        val remainingSeconds = seconds % 60

        // Set text color
        mCounter.setTextColor(ContextCompat.getColor(this, color))

        // Use timeBuffer to work around rare OutOfMemoryError
        timeBuffer[0] = '0' + minutes / 10
        timeBuffer[1] = '0' + minutes % 10
        timeBuffer[3] = '0' + remainingSeconds / 10
        timeBuffer[4] = '0' + remainingSeconds % 10

        try {
            // Set countdown text
            mCounter.setText(timeBuffer, 0, 5)
        }
        catch (error: OutOfMemoryError) {
            // Ignore rare OOM (don't crash the app)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)
    }

    override fun onPause() {
        super.onPause()

        // Fade out gradually
        overridePendingTransition(0, R.anim.fade_out)
    }

    private fun centerPopupInParent(window: Window) {
        // Fade out
        val rootView = window.decorView as FrameLayout

        // Get action bar layout
        val actionBarLayout = rootView.getChildAt(0)

        // Detach it from decor view
        rootView.removeView(actionBarLayout)

        // Create new frame layout
        val wrapLayout = FrameLayout(this)

        // Set params to WRAP_CONTENT
        val layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)

        // Get 25 as PX value
        val dpToPixels = DensityUtil.convertDPToPixels(this, 25)

        // Set margins
        layoutParams.setMargins(dpToPixels, dpToPixels, dpToPixels, dpToPixels)

        // Set gravity to CENTER_VERTICAL
        layoutParams.gravity = Gravity.CENTER_VERTICAL

        // Set layout params
        wrapLayout.layoutParams = layoutParams

        // Add ActionBar to layout
        wrapLayout.addView(actionBarLayout)

        // Add it to root view
        rootView.addView(wrapLayout)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Get window object
        val window = this.window

        // Turn screen on, show when locked and keep screen on
        window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        //WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Call parent
        centerPopupInParent(window)
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
