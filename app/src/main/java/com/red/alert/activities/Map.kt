package com.red.alert.activities

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.MenuItemCompat

import com.red.alert.databinding.AlertViewBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.red.alert.R
import com.red.alert.config.Logging
import com.red.alert.config.RecentAlerts
import com.red.alert.config.ThreatTypes
import com.red.alert.logic.communication.intents.AlertViewParameters
import com.red.alert.logic.communication.intents.MainActivityParameters
import com.red.alert.logic.location.LocationLogic
import com.red.alert.logic.settings.AppPreferences
import com.red.alert.model.Alert
import com.red.alert.model.metadata.City
import com.red.alert.model.metadata.PolygonTooltipData
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.ui.localization.rtl.adapters.RTLMarkerInfoWindowAdapter
import com.red.alert.ui.notifications.AppNotifications
import com.red.alert.utils.caching.Singleton
import com.red.alert.utils.communication.Broadcasts
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.localization.DateTime
import com.red.alert.utils.localization.Localization
import com.red.alert.utils.metadata.LocationData
import com.red.alert.utils.networking.HTTP
import com.red.alert.utils.threading.AsyncTaskAdapter

import me.pushy.sdk.lib.jackson.core.JsonFactory
import me.pushy.sdk.lib.jackson.core.JsonParser
import me.pushy.sdk.lib.jackson.core.JsonToken
import me.pushy.sdk.lib.jackson.databind.ObjectMapper

class Map : AppCompatActivity(), OnMapsSdkInitializedCallback {
    private lateinit var mMap: GoogleMap

    // Inflated layout binding for alert_view.xml (replaces findViewById)
    private var binding: AlertViewBinding? = null

    private lateinit var mDisplayAlerts: MutableList<Alert>

    private var mLiveMap = false
    private var mIsMapReady = false
    private var mIsResumed = false
    private var mIsReloading = false

    private var mDisplayedAlertsCount = 0

    private lateinit var mAppIcon: ImageView
    private var mShareItem: MenuItem? = null
    private var mLoadingItem: MenuItem? = null
    private lateinit var mMapCover: RelativeLayout
    private var mClearRecentAlertsItem: MenuItem? = null

    private var mTooltipMarker: Marker? = null

    companion object {
        // Singleton alerts
        @JvmField
        var mIsExpandedAlert = false

        @JvmField
        var mNewestAlertId: Long = 0

        @JvmField
        var mAlerts: MutableList<Alert> = ArrayList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Use legacy maps renderer to fix blank map bug for 1% of users
        useLegacyRenderer()

        // Initialize alert
        unpackExtras()

        // Initialize UI
        initializeUI()
    }

    override fun attachBaseContext(base: Context) {
        // Reapply locale
        Localization.overridePhoneLocale(base)
        super.attachBaseContext(base)
    }

    private fun useLegacyRenderer() {
        // Use legacy maps renderer
        MapsInitializer.initialize(this@Map, MapsInitializer.Renderer.LEGACY, this@Map)
    }

    private fun unpackExtras() {
        // Live map mode
        mLiveMap = intent.getBooleanExtra(AlertViewParameters.LIVE, false)
    }

    private fun mapLoadedListener() {
        // Wait for map to load
        mMap.setOnCameraChangeListener(GoogleMap.OnCameraChangeListener {
            // Prevent from being called again
            mMap.setOnCameraChangeListener(null)

            // Fix RTL bug
            mMap.setInfoWindowAdapter(RTLMarkerInfoWindowAdapter(layoutInflater))

            // Show my location button
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mMap.isMyLocationEnabled = true
            }

            // Set activity title
            setActivityTitle()

            // Load polygon data async
            LoadPolygonData().execute()
        })

        // Live map?
        if (mLiveMap) {
            pollRecentAlerts()
        }
    }

    private fun setActivityTitle() {
        // Live map?
        if (mLiveMap) {
            // Set recent alerts title
            title = getString(R.string.recentAlerts)
        }

        // Have at least one alert?
        else if (mAlerts.size > 0) {
            // Get first alert object
            val firstAlert = mAlerts[0]

            // Set title to alert relative time ago
            title = LocationData.getAlertRelativeTimeAgo(firstAlert.date, this)

            // Add localized threat type to title
            if (!StringUtils.stringIsNullOrEmpty(firstAlert.localizedThreat)) {
                title = "${firstAlert.localizedThreat}: $title"
            }

            // Display special title for nearby cities display
            if (firstAlert.threat == ThreatTypes.NEARBY_CITIES_DISPLAY) {
                title = "${getString(R.string.nearbyCities)}: ${LocationLogic.getMaxDistanceKilometers(this, -1f)} ${getString(R.string.kilometer)}"
            }
        }
        else {
            // Nearby cities display and no nearby cities
            title = getString(R.string.noNearbyCities)
        }
    }

    private fun initializeMap() {
        // Get map instance
        if (!::mMap.isInitialized) {
            // Stop execution
            return
        }

        // Disable toolbar buttons (navigation / etc)
        mMap.uiSettings.isMapToolbarEnabled = false

        // Check if night mode is enabled
        if ((resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            // Use dark map styling
            mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(
                    this, R.raw.map_style_dark))
        }

        // Wait for map to load
        mapLoadedListener()

        // Map click listener
        mMap.setOnMapClickListener(GoogleMap.OnMapClickListener {
            // Remove previous tooltip
            if (mTooltipMarker != null) {
                mTooltipMarker!!.remove()
                mTooltipMarker = null
            }
        })

        // Listen for polygon click event
        mMap.setOnPolygonClickListener(GoogleMap.OnPolygonClickListener { polygon ->
            // Have tag?
            if (polygon.tag != null) {
                // Remove previous tooltip
                if (mTooltipMarker != null) {
                    mTooltipMarker!!.remove()
                }

                // Get polygon data from tag
                val data = polygon.tag as PolygonTooltipData

                // Create a new tooltip marker
                mTooltipMarker = mMap.addMarker(MarkerOptions()
                        .position(data.location)
                        .title(data.localizedName)
                        .snippet(data.tooltip)
                )

                // Show tooltip (info window)
                mTooltipMarker!!.showInfoWindow()
            }
        })
    }

    private fun redrawOverlays() {
        // Workaround for ConcurrentModificationException
        // Wait for reloading to complete
        if (mIsReloading || !mIsMapReady) {
            return
        }

        // Clear currently-displayed alerts list
        mDisplayAlerts.clear()

        // Clear all existing map annotations
        mMap.clear()

        // Prepare a boundary with all geo-located cities
        val builder = LatLngBounds.Builder()

        // Keep track of whether any cities could be geo-located
        var cityFound = false

        // Keep track of unique marker coordinates
        val uniqueCoordinates = ArrayList<String>()

        // Get polygons data for all cities (execution takes roughly 1 second)
        val polygons: HashMap<String, ArrayList<ArrayList<Double>>> = LocationData.getAllPolygons(this)

        // List of processed cities
        val cityNames: MutableList<String> = ArrayList()

        // Check if user cleared the recent alerts
        val cutoffTimestamp = AppPreferences.getRecentAlertsCutoffTimestamp(this)

        // Traverse alerts
        for (alert in mAlerts) {
            // Remove alerts that occurred before the cutoff
            if (mLiveMap && cutoffTimestamp > 0 && alert.date <= cutoffTimestamp) {
                continue
            }

            // Live map: Skip alerts that are early warnings / leave shelter alerts
            if (mLiveMap && (alert.threat == ThreatTypes.EARLY_WARNING || alert.threat == ThreatTypes.LEAVE_SHELTER)) {
                continue
            }

            // Get city object
            val city: City? = LocationData.getCityByName(alert.city, this)

            // No city found?
            if (city == null) {
                continue
            }

            // Only display one marker for each city
            if (cityNames.contains(alert.city)) {
                continue
            }

            // Keep track of city name to avoid duplicates
            cityNames.add(alert.city)

            // Store polygon object
            var polygon: Polygon? = null

            // Check if we have polygon data for this city
            if (polygons.containsKey(city.id.toString())) {
                // Get polygons for city
                val cityPolygons = polygons[city.id.toString()]!!

                // Prepare list of LatLng objects with all polygon points
                val polygonPoints: MutableList<LatLng> = ArrayList()

                // Prepare a boundary calculator to find polygon center point
                val polygonCenter = LatLngBounds.Builder()

                // Traverse polygon points
                for (polygonPoint in cityPolygons) {
                    // Create new LatLng object for each point
                    val point = LatLng(polygonPoint[0], polygonPoint[1])

                    // Include in ArrayList
                    polygonPoints.add(point)

                    // Include in LatLngBounds
                    polygonCenter.include(point)
                }

                // Default polygon colors
                var strokeColor = resources.getColor(R.color.colorPolygonStroke)
                var fillColor = resources.getColor(R.color.colorPolygonFill)

                // Leave shelter alert?
                if (alert.threat == ThreatTypes.LEAVE_SHELTER) {
                    // Show green polygons
                    strokeColor = resources.getColor(R.color.colorPolygonStrokeEventEnd)
                    fillColor = resources.getColor(R.color.colorPolygonFillEventEnd)
                }

                // Add city polygon to map with custom styling
                polygon = mMap.addPolygon(PolygonOptions()
                        .clickable(true)
                        .strokeWidth(4f)
                        .strokeColor(strokeColor)
                        .fillColor(fillColor)
                        .addAll(polygonPoints))

                // Get polygon center point
                val bounds = polygonCenter.build()

                // Use center of polygon instead of default city location
                val center = bounds.center

                // Override city lat, lng with polygon center point
                city.latitude = center.latitude
                city.longitude = center.longitude
            }

            // Does this city have a geolocation?
            if (city.latitude != 0.0) {
                // Get localized city and zone names
                val localizedName = "${LocationData.getLocalizedCityName(city.name, this)} (${LocationData.getLocalizedZoneByCityName(city.name, this)})"

                // Create LatLng location object
                val location = LatLng(city.latitude, city.longitude)

                // Marker tooltip
                var tooltip = LocationData.getAlertDateTimeString(alert.date, 0, this)

                // Add shelter count if exists for this city
                if (city.shelters > 0) {
                    tooltip += "\n${getString(R.string.lifeshieldShelters)} ${city.shelters}"
                }

                // Custom tooltip for nearby cities display
                if (alert.threat == ThreatTypes.NEARBY_CITIES_DISPLAY) {
                    // Display current distance from city center
                    tooltip = "${LocationData.getDistanceFromCity(city, this)} ${getString(R.string.kilometer)}"
                }

                // Add marker to map (if less than X alert cities and not a leave shelter alert)
                if (mAlerts.size < 10 && alert.threat != ThreatTypes.LEAVE_SHELTER) {
                    mMap.addMarker(MarkerOptions()
                            .position(location)
                            .title(localizedName)
                            .snippet(tooltip))
                }
                else if (polygon != null) {
                    // Pass tooltip info to polygon to show marker only on polygon click
                    polygon.tag = PolygonTooltipData(location, localizedName, tooltip)
                }

                // Include location in zoom boundaries
                builder.include(location)

                // We found at least one city
                cityFound = true

                // Avoid adding another marker at these exact coordinates
                uniqueCoordinates.add("${city.latitude}-${city.longitude}")
            }

            // Set to threat drawable (unless in live map mode)
            if (!mLiveMap) {
                mAppIcon.setImageResource(LocationData.getThreatDrawable(alert.threat))
            }

            // Add to display alerts
            mDisplayAlerts.add(alert)
        }

        // Reveal icon
        mAppIcon.visibility = View.VISIBLE

        // Update displayed icon according to how many alerts are being displayed
        updateClearAlertsButton(cutoffTimestamp)

        // Geolocation failure?
        if (!cityFound) {
            return
        }

        // Live map?
        // Don't move map if no change in alerts
        if (mLiveMap && mDisplayAlerts.size == mDisplayedAlertsCount) {
            return
        }

        // Keep track of currently displayed alert count
        mDisplayedAlertsCount = mDisplayAlerts.size

        // Build a boundary for the map positioning
        val bounds = builder.build()

        // Set padding/offset from the edges of the screen (px)
        val padding = 200

        // Set max zoom for animation to 13
        mMap.setMaxZoomPreference(13f)

        // Delay animation slightly
        Handler().postDelayed({
            try {
                // Animate nicely
                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding), 600, object : GoogleMap.CancelableCallback {
                    override fun onFinish() {
                        // Allow user to zoom in freely
                        mMap.resetMinMaxZoomPreference()
                    }

                    override fun onCancel() {
                    }
                })
            } catch (exc: Exception) {
                // Ignore rare exception "View size is too small after padding is applied"
            }
        }, 350)
    }

    override fun onCreateOptionsMenu(optionsMenu: Menu): Boolean {
        // Add clear recent alerts button
        initializeClearRecentAlertsButton(optionsMenu)

        // Add share button
        initializeShareButton(optionsMenu)

        // Add loading indicator
        initializeLoadingIndicator(optionsMenu)

        // Show the menu
        return true
    }

    private fun initializeClearRecentAlertsButton(optionsMenu: Menu) {
        // Only display button in live map mode
        if (!mLiveMap) {
            return
        }

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
                AlertDialogBuilder.showGenericDialog(getString(R.string.clearRecentAlerts), getString(R.string.clearRecentAlertsDesc), getString(R.string.yes), getString(R.string.no), true, this@Map, DialogInterface.OnClickListener { _, which ->
                    // Clicked okay?
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        // Set recent alerts cutoff timestamp to now
                        AppPreferences.updateRecentAlertsCutoffTimestamp(DateTime.getUnixTimestamp(), this@Map)

                        // Redraw map overlays
                        redrawOverlays()

                        // Clear app notifications
                        AppNotifications.clearAll(this@Map)
                    }
                })
            }
            else {
                // No alerts displayed, so display all of them
                AppPreferences.updateRecentAlertsCutoffTimestamp(0, this@Map)

                // Redraw map overlays
                redrawOverlays()
            }

            // Consume event
            true
        })

        // No alerts?
        if (mDisplayAlerts.size == 0) {
            // By default, hide button until alerts are returned by the server
            clearItem.isVisible = false
        }
    }

    private fun getShareMessage(): String {
        // Live map mode or no alerts?
        if (mLiveMap || mAlerts.size == 0) {
            // Return generic app share message
            return getString(R.string.shareMessage)
        }

        // Nearby cities display?
        if (mAlerts.size > 0 && mAlerts[0].threat == ThreatTypes.NEARBY_CITIES_DISPLAY) {
            // Return generic app share message
            return getString(R.string.shareMessage)
        }

        // Get city name or cities if grouped
        var cities = mAlerts[0].localizedCity

        // Remove HTML entities (<b>)
        cities = HtmlCompat.fromHtml(cities, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

        // Expanded alert?
        if (mIsExpandedAlert) {
            // Remove HTML entities from city names (<b>)
            val expandedCities = HtmlCompat.fromHtml(mAlerts[0].desc, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

            // Construct share message
            return "$cities\r\n${mAlerts[0].dateString}\n\n$expandedCities\n\n${getString(R.string.alertSentVia)}"
        }
        else {
            // Construct share message
            return "${mAlerts[0].localizedThreat} ${getString(R.string.alertSoundedAt)}$cities\n${mAlerts[0].dateString}\n\n${getString(R.string.alertSentVia)}"
        }
    }

    private fun initializeShareButton(optionsMenu: Menu) {
        // Add share icon to Action Bar
        val shareItem = optionsMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, getString(R.string.shareAlert))
        mShareItem = shareItem

        // Set share icon
        shareItem.setIcon(R.drawable.ic_share)

        // Hide by default
        shareItem.isVisible = false

        // Specify the show flags
        MenuItemCompat.setShowAsAction(shareItem, MenuItem.SHOW_AS_ACTION_ALWAYS)

        // On click, open share
        shareItem.setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener {
            // Prepare share intent
            val shareIntent = Intent(Intent.ACTION_SEND)

            // Set as text/plain
            shareIntent.type = "text/plain"

            // Add text
            shareIntent.putExtra(Intent.EXTRA_TEXT, getShareMessage())

            // Show chooser
            startActivity(Intent.createChooser(shareIntent, getString(R.string.shareAlert)))

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

    override fun onResume() {
        super.onResume()

        // Save state
        mIsResumed = true

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)

        // Clear notifications and stop sound from playing
        AppNotifications.clearAll(this)

        // Register for broadcasts
        Broadcasts.subscribe(this, mBroadcastListener)
    }

    override fun onPause() {
        super.onPause()

        // Save state
        mIsResumed = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this)
    }

    private fun initializeUI() {
        // Initialize display alerts list
        mDisplayAlerts = ArrayList()

        // Allow click on home button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // Inflate layout with view binding
        binding = AlertViewBinding.inflate(layoutInflater)

        // Attach root to this activity
        setContentView(binding!!.root)

        // Store reference to app icon & map cover for later
        mAppIcon = binding!!.appIcon
        mMapCover = binding!!.mapCover

        // Align app icon based on whether language is RTL to avoid hiding Google logo
        val params = mAppIcon.layoutParams as RelativeLayout.LayoutParams
        params.addRule(if (Localization.isRTLLocale(this)) RelativeLayout.ALIGN_PARENT_LEFT else RelativeLayout.ALIGN_PARENT_RIGHT)
        mAppIcon.layoutParams = params

        // Get map instance
        (supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment).getMapAsync(OnMapReadyCallback { googleMap ->
            mMap = googleMap

            // Map ready flag
            mIsMapReady = true

            // Initialize map
            initializeMap()
        })
    }

    private fun initializeLoadingIndicator(optionsMenu: Menu) {
        // Add loading spinner to Action Bar
        val loadingItem = optionsMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, getString(R.string.loading))
        mLoadingItem = loadingItem

        // Set up the view
        MenuItemCompat.setActionView(loadingItem, R.layout.loading)

        // Specify the show flags
        MenuItemCompat.setShowAsAction(loadingItem, MenuItem.SHOW_AS_ACTION_ALWAYS)

        // Hide by default
        loadingItem.isVisible = false
    }

    override fun onMapsSdkInitialized(renderer: MapsInitializer.Renderer) {
        // Do nothing
    }

    inner class LoadPolygonData : AsyncTaskAdapter<Int, String, Int>() {
        init {
            // Null check
            if (mLoadingItem != null) {
                // Show loading indicator
                mLoadingItem!!.isVisible = true
            }
        }

        override fun doInBackground(vararg parameter: Int?): Int? {
            // Load polygons data for all cities (execution takes roughly 1 second)
            LocationData.getAllPolygons(this@Map)

            // No errors
            return 0
        }

        override fun onPostExecute(error: Int?) {
            // Activity dead?
            if (isFinishing || isDestroyed) {
                return
            }

            // Add city polygons and markers
            redrawOverlays()

            // Null check
            if (mLoadingItem != null) {
                // Hide loading indicator
                mLoadingItem!!.isVisible = false
            }

            // Null check
            if (mShareItem != null) {
                // Show share button
                mShareItem!!.isVisible = true
            }

            // Delay by 200ms
            Handler().postDelayed({
                // Prevent white flicker as map loads in dark mode
                mMapCover.visibility = View.GONE
            }, 200)
        }
    }

    private val mBroadcastListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Asked for reload?
        if (key.equals(MainActivityParameters.RELOAD_RECENT_ALERTS, ignoreCase = true)) {
            // Live map?
            if (mLiveMap) {
                reloadRecentAlerts()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unregister for broadcasts
        Broadcasts.unsubscribe(this, mBroadcastListener)

        // Drop view binding reference after teardown
        binding = null
    }

    private fun pollRecentAlerts() {
        // Schedule a new timer
        val handler = Handler(Looper.getMainLooper())

        // Schedule a new timer
        handler.postDelayed(object : Runnable {
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
                handler.postDelayed(this, 1000L * RecentAlerts.RECENT_ALERTS_POLLING_INTERVAL_SEC)
            }
        }, 1000L * RecentAlerts.RECENT_ALERTS_POLLING_INTERVAL_SEC)
    }

    private fun reloadRecentAlerts() {
        // Not already reloading?
        if (!mIsReloading) {
            // Get recent alerts async
            GetRecentAlertsAsync().execute()
        }
    }

    inner class GetRecentAlertsAsync : AsyncTaskAdapter<Int, String, Int>() {
        init {
            // Prevent concurrent reload
            mIsReloading = true

            // Null check
            if (mLoadingItem != null) {
                // Show loading indicator
                mLoadingItem!!.isVisible = true
            }

            // Null check
            if (mShareItem != null) {
                // Hide share button
                mShareItem!!.isVisible = false
            }
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
            mLoadingItem!!.isVisible = false

            // Success?
            if (errorStringResource == 0) {
                // Redraw map overlays
                redrawOverlays()

                // Show share button
                mShareItem!!.isVisible = true

                // Prevent white flicker as map loads in dark mode
                mMapCover.visibility = View.GONE
            }
            // No new alerts?
            else if (errorStringResource == R.string.noNewAlerts) {
                // Show share button
                mShareItem!!.isVisible = true
            }
            else {
                // Show error toast
                Toast.makeText(this@Map, getString(errorStringResource!!), Toast.LENGTH_LONG).show()
            }
        }
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
        val recentAlerts: MutableList<Alert> = ArrayList()

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

                        // No new alerts, do nothing
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

        // Loop over alerts
        for (alert in recentAlerts) {
            // Prepare string with relative time ago and fixed HH:mm:ss
            alert.dateString = LocationData.getAlertDateTimeString(alert.date, 0, this)

            // Prepare localized zone & countdown for display
            alert.desc = LocationData.getLocalizedZoneWithCountdown(alert.city, alert.threat, this)

            // Localize it
            alert.localizedCity = LocationData.getLocalizedCityName(alert.city, this)
            alert.localizedZone = LocationData.getLocalizedZoneByCityName(alert.city, this)
        }

        // Clear global list
        mAlerts.clear()

        // Add all the new alerts
        mAlerts.addAll(recentAlerts)

        // Success
        return 0
    }

    private fun updateClearAlertsButton(cutoffTimestamp: Long) {
        // Do nothing if not in live map mode
        if (!mLiveMap) {
            return
        }

        // Null pointer check
        val clearItem = mClearRecentAlertsItem

        if (clearItem == null) {
            return
        }

        // By default, show clear button
        clearItem.isVisible = true

        // In case no alerts are being displayed but alerts were returned
        if (cutoffTimestamp > 0 && mAlerts.size > 0 && mDisplayAlerts.size == 0) {
            // Show restore icon to allow user to restore all recent alerts
            clearItem.setIcon(R.drawable.ic_restore)
        }
        // In case there are no alerts in the past 24 hours
        else if (mAlerts.size == 0) {
            // Hide clear button
            clearItem.isVisible = false
        }
        else {
            // There are alerts, show default clear icon
            clearItem.setIcon(R.drawable.ic_clear)
        }
    }
}
