package com.red.alert.ui.localization.rtl.adapters

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker
import com.red.alert.R

class RTLMarkerInfoWindowAdapter(inflater: LayoutInflater) : GoogleMap.InfoWindowAdapter {
    private var mInflater: LayoutInflater = inflater

    override fun getInfoContents(marker: Marker): View? {
        return null
    }

    override fun getInfoWindow(marker: Marker): View? {
        // Inflate custom view
        val popup = mInflater.inflate(R.layout.map_tooltip, null)

        // Get text view
        var textView = popup.findViewById<TextView>(R.id.title)

        // Set text
        textView.text = marker.title

        // Get snippet view
        textView = popup.findViewById<TextView>(R.id.snippet)

        // Set snippet
        textView.text = marker.snippet

        // No snippet?
        if (marker.snippet == null) {
            textView.visibility = View.GONE
        }

        // Return popup
        return popup
    }
}
