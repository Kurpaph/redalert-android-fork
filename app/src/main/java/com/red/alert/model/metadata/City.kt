package com.red.alert.model.metadata

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class City {
    @JvmField
    @JsonProperty("id")
    var id: Int = 0

    @JvmField
    @JsonProperty("lat")
    var latitude: Double = 0.0

    @JvmField
    @JsonProperty("lng")
    var longitude: Double = 0.0

    @JvmField
    @JsonProperty("name")
    var name: String? = null

    @JvmField
    @JsonProperty("name_en")
    var nameEnglish: String? = null

    @JvmField
    @JsonProperty("name_ru")
    var nameRussian: String? = null

    @JvmField
    @JsonProperty("name_ar")
    var nameArabic: String? = null

    @JvmField
    @JsonProperty("zone")
    var zone: String? = null

    @JvmField
    @JsonProperty("zone_en")
    var zoneEnglish: String? = null

    @JvmField
    @JsonProperty("zone_ru")
    var zoneRussian: String? = null

    @JvmField
    @JsonProperty("zone_ar")
    var zoneArabic: String? = null

    @JvmField
    @JsonProperty("value")
    var value: String? = null

    @JvmField
    @JsonProperty("shelters")
    var shelters: Int = 0

    @JvmField
    @JsonProperty("countdown")
    var countdown: Int = 0

    override fun toString(): String {
        return value!!
    }
}
