package com.red.alert.model.req

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class RegistrationRequest(
    fcmToken: String?,
    pushyToken: String?,
    platform: String?
) {
    @JvmField
    @field:JsonProperty("platform")
    var platform: String? = platform

    @JvmField
    @field:JsonProperty("token")
    var fcmToken: String? = fcmToken

    @JvmField
    @field:JsonProperty("pushyToken")
    var pushyToken: String? = pushyToken
}
