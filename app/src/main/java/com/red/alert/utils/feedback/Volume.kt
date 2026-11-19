package com.red.alert.utils.feedback

import android.app.Activity

import com.red.alert.config.Sound

object Volume {
    @JvmStatic
    fun setVolumeKeysAction(context: Activity) {
        // Set the appropriate alert stream
        context.volumeControlStream = Sound.STREAM_TYPE
    }
}
