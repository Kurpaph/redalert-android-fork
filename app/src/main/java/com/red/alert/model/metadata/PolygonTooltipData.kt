package com.red.alert.model.metadata

import com.google.android.gms.maps.model.LatLng

class PolygonTooltipData(
    @JvmField var location: LatLng,
    @JvmField var localizedName: String,
    @JvmField var tooltip: String
)
