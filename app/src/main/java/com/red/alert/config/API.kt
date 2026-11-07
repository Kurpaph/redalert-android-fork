package com.red.alert.config

object API {
    // RedAlert API Endpoint(s)
    @JvmField
    var ENDPOINTS = arrayOf("https://redalert.me", "https://api.redalert.me", "https://api.tzevaadom.app", "https://tzevaadom.app")

    // Define platform to be sent by our API
    @JvmField
    var PLATFORM_IDENTIFIER = "android"

    // Define platform for Pushy self test
    @JvmField
    var PUSHY_PLATFORM_IDENTIFIER = "pushy"
}
