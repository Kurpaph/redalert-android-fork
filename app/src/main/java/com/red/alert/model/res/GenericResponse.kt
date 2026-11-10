package com.red.alert.model.res

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class GenericResponse {
    @JvmField
    @field:JsonProperty("success")
    var success: Boolean = false

    @JvmField
    @field:JsonProperty("error")
    var error: String? = null
}
