package com.red.alert.model.res

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class RegistrationResponse {
    @JvmField
    @field:JsonProperty("id")
    var id: Long = 0

    @JvmField
    @field:JsonProperty("hash")
    var hash: String? = null
}
