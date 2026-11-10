package com.red.alert.model.req

import me.pushy.sdk.lib.jackson.annotation.JsonProperty

class UpdateNotificationsRequest(
    uid: Long,
    hash: String?,
    primary: Boolean,
    secondary: Boolean
) {
    @JvmField
    @field:JsonProperty("uid")
    var uid: Long = uid

    @JvmField
    @field:JsonProperty("hash")
    var hash: String? = hash

    @JvmField
    @field:JsonProperty("primary")
    var primary: String = if (primary) "1" else "0"

    @JvmField
    @field:JsonProperty("secondary")
    var secondary: String = if (secondary) "1" else "0"
}
