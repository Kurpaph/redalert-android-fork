package com.red.alert.config.push

object PushyGateway {
    // Set custom heartbeat interval (in ms) for the underlying MQTT socket
    @JvmField
    var SOCKET_HEARTBEAT_INTERVAL = 60 * 5
}
