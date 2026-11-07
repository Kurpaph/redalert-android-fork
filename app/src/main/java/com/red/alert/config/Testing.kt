package com.red.alert.config

object Testing {
    // Max time in seconds to wait for a test push to arrive
    @JvmField
    var PUSH_GATEWAY_TIMEOUT_SECONDS = 8

    // Max time in seconds to wait for an external device to connect
    @JvmField
    var DEVICE_CONNECTION_TIMEOUT_SECONDS = 15
}
