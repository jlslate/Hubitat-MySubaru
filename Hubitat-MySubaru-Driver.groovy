/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * For more information, please refer to <https://unlicense.org>
 */

/*
 * Child device created by the Subaru Connect app. All commands are proxied
 * back to the parent app, which owns the authenticated Subaru session - see
 * SubaruConnectApp.groovy for the actual HTTP/session handling.
 */

metadata {
    definition(name: "Subaru Vehicle", namespace: "jlslate", author: "jlslate (slate)") {
        capability "Lock"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        command "unlockDriverDoor"
        command "unlockTailgate"
        command "hornAndLights"
        command "stopHornAndLights"
        command "flashLights"
        command "stopFlashLights"
        command "locate"
        command "remoteStart", [[name: "presetName*", type: "STRING", description: "Climate preset name, as saved in the MySubaru app (see the 'presets' attribute for available names)"]]
        command "remoteStop"
        command "chargeStart"

        attribute "modelName", "string"
        attribute "modelYear", "string"
        attribute "vehicleState", "string"
        attribute "odometer", "number"
        attribute "fuelPercent", "number"
        attribute "avgFuelConsumption", "number"
        attribute "distanceToEmpty", "number"
        attribute "doorFrontLeft", "string"
        attribute "doorFrontRight", "string"
        attribute "doorRearLeft", "string"
        attribute "doorRearRight", "string"
        attribute "doorBoot", "string"
        attribute "doorEngineHood", "string"
        attribute "windowFrontLeft", "string"
        attribute "windowFrontRight", "string"
        attribute "windowRearLeft", "string"
        attribute "windowRearRight", "string"
        attribute "windowSunroof", "string"
        attribute "tirePressureFL", "number"
        attribute "tirePressureFR", "number"
        attribute "tirePressureRL", "number"
        attribute "tirePressureRR", "number"
        attribute "latitude", "number"
        attribute "longitude", "number"
        attribute "locationValid", "string"
        attribute "evPluggedIn", "string"
        attribute "evChargePercent", "number"
        attribute "evChargerState", "string"
        attribute "evDistanceToEmpty", "number"
        attribute "evTimeToFullyCharged", "string"
        attribute "presets", "string"
        attribute "lastUpdated", "string"
        attribute "lastCommandResult", "string"
    }
}

def installed() {
    refresh()
}

def updated() { }

def refresh() {
    parent?.componentRefresh(device)
}

def lock() {
    parent?.componentLock(device)
}

def unlock() {
    parent?.componentUnlock(device)
}

def unlockDriverDoor() {
    parent?.componentUnlock(device, "FRONT_LEFT_DOOR_CMD")
}

def unlockTailgate() {
    parent?.componentUnlock(device, "TAILGATE_DOOR_CMD")
}

def hornAndLights() {
    parent?.componentHornAndLights(device)
}

def stopHornAndLights() {
    parent?.componentStopHornAndLights(device)
}

def flashLights() {
    parent?.componentFlashLights(device)
}

def stopFlashLights() {
    parent?.componentStopFlashLights(device)
}

def locate() {
    parent?.componentLocate(device)
}

def remoteStart(String presetName) {
    parent?.componentRemoteStart(device, presetName)
}

def remoteStop() {
    parent?.componentRemoteStop(device)
}

def chargeStart() {
    parent?.componentChargeStart(device)
}
