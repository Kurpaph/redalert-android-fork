package com.red.alert.model

import me.pushy.sdk.lib.jackson.annotation.JsonIgnore
import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class Alert {
    @JvmField
    @field:JsonProperty("id")
    var id: Int = 0

    @JvmField
    @field:JsonProperty("date")
    var date: Long = 0

    @JvmField
    @field:JsonProperty("area")
    var city: String? = null

    @JvmField
    @field:JsonProperty("threat")
    var threat: String? = null

    @JvmField
    var localizedTitle: String? = null

    @JvmField
    var localizedCity: String? = null

    @JvmField
    var localizedZone: String? = null

    @JvmField
    var localizedThreat: String? = null

    @JvmField
    var desc: String? = null

    @JvmField
    var dateString: String? = null

    @JvmField
    @field:JsonIgnore
    var groupedAlerts: MutableList<Alert>? = null

    @JvmField
    @field:JsonIgnore
    var groupedDescriptions: MutableList<String>? = null

    @JvmField
    @field:JsonIgnore
    var groupedCities: MutableList<String>? = null

    @JvmField
    var isExpanded: Boolean = false

    @JvmField
    var firstGroupedAlertTimestamp: Long = 0

    override fun toString(): String {
        return city!!
    }
}
