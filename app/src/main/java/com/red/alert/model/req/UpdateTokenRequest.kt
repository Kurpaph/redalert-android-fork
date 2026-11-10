package com.red.alert.model.req

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class UpdateTokenRequest(
    uid: Long,
    hash: String?,
    fcmToken: String?,
    pushyToken: String?
) {
    @JvmField
    @field:JsonProperty("uid")
    var uid: Long = uid

    @JvmField
    @field:JsonProperty("hash")
    var hash: String? = hash

    @JvmField
    @field:JsonProperty("token")
    var fcmToken: String? = fcmToken

    @JvmField
    @field:JsonProperty("pushyToken")
    var pushyToken: String? = pushyToken
}
