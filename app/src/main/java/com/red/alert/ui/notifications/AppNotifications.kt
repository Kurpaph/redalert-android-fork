package com.red.alert.ui.notifications

import android.app.NotificationManager
import android.content.Context

import com.red.alert.logic.feedback.sound.SoundLogic

object AppNotifications {
    @JvmStatic
    fun clearAll(context: Context) {
        // Get notification manager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Cancel all notifications
        notificationManager.cancelAll()

        // Stop alert sound if playing
        SoundLogic.stopSound(context)
    }
}
