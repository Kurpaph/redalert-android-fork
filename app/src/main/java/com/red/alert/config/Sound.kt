package com.red.alert.config

import android.media.AudioManager

object Sound {
    const val SCHEME_URI_IDENTIFIER = "://"
    const val APP_SOUND_PREFIX = "com.red.alert://"

    // The AudioManager stream that sirens will be played on
    @JvmField
    var STREAM_TYPE = AudioManager.STREAM_ALARM

    // DUser selected custom sound key
    @JvmField
    var CUSTOM_SOUND_NAME = "custom"
}
