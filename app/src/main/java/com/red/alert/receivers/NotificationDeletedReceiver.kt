package com.red.alert.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import com.red.alert.logic.communication.intents.RocketNotificationParameters
import com.red.alert.logic.feedback.sound.SoundLogic

class NotificationDeletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Got deleted notification event?
        if (RocketNotificationParameters.NOTIFICATION_DELETED_ACTION == intent.action) {
            // Silence currently playing sound
            SoundLogic.stopSound(context)
        }
    }
}
