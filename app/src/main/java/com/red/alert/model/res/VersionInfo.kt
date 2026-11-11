package com.red.alert.model.res

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class VersionInfo {
    @JvmField
    @field:JsonProperty("version")
    var version: String? = null

    @JvmField
    @field:JsonProperty("version_code")
    var versionCode: Int = 0

    @JvmField
    @field:JsonProperty("show_dialog")
    var showDialog: Boolean = false

    override fun toString(): String {
        return version!!
    }
}
