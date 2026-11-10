package com.red.alert.model.req

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class SubscribeRequest(
    uid: Long,
    hash: String?,
    primarySubscriptions: List<String>,
    secondarySubscriptions: List<String>
) {
    @JvmField
    @field:JsonProperty("uid")
    var uid: Long = uid

    @JvmField
    @field:JsonProperty("hash")
    var hash: String? = hash

    @JvmField
    @field:JsonProperty("primary")
    var primary: Array<String> = primarySubscriptions.toTypedArray()

    @JvmField
    @field:JsonProperty("secondary")
    var secondary: Array<String> = secondarySubscriptions.toTypedArray()
}
