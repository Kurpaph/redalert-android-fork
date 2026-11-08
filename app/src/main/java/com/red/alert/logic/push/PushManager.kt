package com.red.alert.logic.push

import android.content.Context

object PushManager {
    @JvmStatic
    fun updateSubscriptions(context: Context) {
        // Update FCM subscriptions
        FCMRegistration.updateSubscriptions(context)

        // Update Pushy subscriptions
        PushyRegistration.updateSubscriptions(context)
    }
}
