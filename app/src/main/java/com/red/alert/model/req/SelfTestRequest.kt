package com.red.alert.model.req

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class SelfTestRequest(
    token: String?,
    locale: String?,
    platform: String?
) {
    // Set FCM/Pushy registration token
    @JvmField
    @field:JsonProperty("token")
    var token: String? = token

    // Set the push type
    @JvmField
    @field:JsonProperty("platform")
    var platform: String? = platform

    // Set the language for localization
    @JvmField
    @field:JsonProperty("locale")
    var locale: String? = locale
}
