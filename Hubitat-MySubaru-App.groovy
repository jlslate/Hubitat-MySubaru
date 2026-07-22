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
 * Talks to Subaru's undocumented MySubaru Connected Services (STARLINK) mobile
 * app API - the same one used by the official MySubaru Android/iOS app. Subaru
 * publishes no public API, so this is a reverse-engineered client modeled on
 * the endpoint/flow analysis done by the subarulink project
 * (https://github.com/G-Two/subarulink), which itself powers Home Assistant's
 * official Subaru integration. It can change or break without warning.
 *
 * Requires an active MySubaru account with a vehicle already enrolled, and an
 * active Security Plus/Remote subscription for lock/unlock/horn/lights/remote
 * start/locate commands to work. A 4-digit remote services PIN (set in the
 * MySubaru app under Account) is required for actuation commands.
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field static final Map API_SERVER = [USA: "mobileapi.prod.subarucs.com", CAN: "mobileapi.ca.prod.subarucs.com"]
@Field static final Map API_MOBILE_APP = [USA: "com.subaru.telematics.app.remote", CAN: "ca.subaru.telematics.remote"]
@Field static final String API_VERSION = "/g2v33"

@Field static final String API_LOGIN = "/login.json"
@Field static final String API_2FA_CONTACT = "/twoStepAuthContacts.json"
@Field static final String API_2FA_SEND_VERIFICATION = "/twoStepAuthSendVerification.json"
@Field static final String API_2FA_AUTH_VERIFY = "/twoStepAuthVerify.json"
@Field static final String API_SELECT_VEHICLE = "/selectVehicle.json"
@Field static final String API_VALIDATE_SESSION = "/validateSession.json"
@Field static final String API_VEHICLE_STATUS = "/vehicleStatus.json"

@Field static final String API_LOCK = "/service/api_gen/lock/execute.json"
@Field static final String API_UNLOCK = "/service/api_gen/unlock/execute.json"
@Field static final String API_HORN_LIGHTS = "/service/api_gen/hornLights/execute.json"
@Field static final String API_HORN_LIGHTS_STOP = "/service/api_gen/hornLights/stop.json"
@Field static final String API_LIGHTS = "/service/api_gen/lightsOnly/execute.json"
@Field static final String API_LIGHTS_STOP = "/service/api_gen/lightsOnly/stop.json"
@Field static final String API_CONDITION = "/service/api_gen/condition/execute.json"
@Field static final String API_LOCATE = "/service/api_gen/locate/execute.json"
@Field static final String API_REMOTE_SVC_STATUS = "/service/api_gen/remoteService/status.json"
@Field static final String API_G1_HORN_LIGHTS_STATUS = "/service/g1/hornLights/status.json"

@Field static final String API_G1_LOCATE_UPDATE = "/service/g1/vehicleLocate/execute.json"
@Field static final String API_G1_LOCATE_STATUS = "/service/g1/vehicleLocate/status.json"
@Field static final String API_G2_LOCATE_UPDATE = "/service/g2/vehicleStatus/execute.json"
@Field static final String API_G2_LOCATE_STATUS = "/service/g2/vehicleStatus/locationStatus.json"

@Field static final String API_G2_REMOTE_ENGINE_START = "/service/g2/engineStart/execute.json"
@Field static final String API_G2_REMOTE_ENGINE_STOP = "/service/g2/engineStop/execute.json"
@Field static final String API_G2_FETCH_RES_USER_PRESETS = "/service/g2/remoteEngineStartSettings/fetch.json"
@Field static final String API_G2_FETCH_RES_SUBARU_PRESETS = "/service/g2/climatePresetSettings/fetch.json"
@Field static final String API_G2_SAVE_RES_QUICK_START_SETTINGS = "/service/g2/remoteEngineQuickStartSettings/save.json"

@Field static final String API_EV_CHARGE_NOW = "/service/g2/phevChargeNow/execute.json"

@Field static final String DOOR_ALL = "ALL_DOORS_CMD"

definition(
    name: "Subaru Connect",
    namespace: "jlslate",
    author: "jlslate (slate)",
    description: "Links your MySubaru STARLINK account to Hubitat: lock/unlock, horn/lights, remote start/stop, and vehicle status for each enrolled vehicle",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Subaru Connect", install: canInstall(), uninstall: true) {
        section("MySubaru Account") {
            input name: "subaruUsername", type: "text", title: "MySubaru Username/Email", required: true, submitOnChange: true
            input name: "subaruPassword", type: "password", title: "MySubaru Password", required: true, submitOnChange: true
            input name: "subaruPin", type: "password", title: "4-digit Remote Services PIN", required: true, submitOnChange: true
            input name: "country", type: "enum", title: "Country", options: ["USA", "CAN"], defaultValue: "USA", required: true, submitOnChange: true
            input name: "deviceName", type: "text", title: "Device Name (shown in MySubaru app under registered devices)", defaultValue: "Hubitat", required: true
        }
        section("Connect") {
            input name: "btnConnect", type: "button", title: state.authenticated ? "Reconnect / Refresh Vehicles" : "Connect"
            if (state.flowError) paragraph "<span style='color:red'>${state.flowError}</span>"
            if (state.authenticated && !state.needs2FA) paragraph "<span style='color:green'>Connected to MySubaru.</span>"
        }
        if (state.needs2FA) {
            section("Two-Factor Verification Required") {
                paragraph "MySubaru requires verifying this device the first time. Choose where to receive a code."
                List options = contactMethodOptions()
                input name: "contactMethod", type: "enum", title: "Send code via", options: options, required: true, submitOnChange: true
                input name: "btnSendCode", type: "button", title: "Send Code"
                if (state.codeSent) {
                    input name: "verificationCode", type: "text", title: "6-digit code", required: true, submitOnChange: true
                    input name: "btnVerifyCode", type: "button", title: "Verify Code"
                }
            }
        }
        if (state.vehicles) {
            section("Vehicles Found") {
                state.vehicles.each { vin, info ->
                    paragraph "${info.nickname ?: info.modelName} - ${info.modelYear} ${info.modelName} (${vin})"
                }
                paragraph "Click Done to create Hubitat devices for the vehicle(s) above."
            }
        }
        section("Options") {
            input name: "pollIntervalMinutes", type: "number", title: "Status refresh interval (minutes, minimum 5). This only pulls cached data from Subaru's servers - it does not wake up the car.", defaultValue: 30, range: "5..1440"
            input name: "enableLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        }
        if (state.pinLockout) {
            section("PIN Locked Out") {
                paragraph "<span style='color:red'>Subaru rejected the remote services PIN. Correct it above and click Connect to re-enable remote commands. Repeated bad attempts can lock your MySubaru account.</span>"
            }
        }
    }
}

private boolean canInstall() {
    return state.authenticated && !state.needs2FA && state.vehicles
}

private List contactMethodOptions() {
    def opts = state.contactOptions
    if (opts instanceof Map) return opts.collect { k, v -> [(k): "${k}: ${v}"] }
    if (opts instanceof List) return opts
    return []
}

def appButtonHandler(String btn) {
    state.flowError = null
    switch (btn) {
        case "btnConnect":
            handleConnect()
            break
        case "btnSendCode":
            handleSendCode()
            break
        case "btnVerifyCode":
            handleVerifyCode()
            break
    }
}

private void handleConnect() {
    if (!state.deviceId) state.deviceId = 1000000000L + new Random().nextInt(900000000)
    state.pinLockout = false
    state.needs2FA = false
    state.codeSent = false
    boolean ok = doLogin()
    if (!ok) {
        state.flowError = "Login failed: ${state.loginError}"
        return
    }
    if (!state.deviceRegistered) {
        state.needs2FA = true
        fetchContactMethods()
    } else {
        fetchVehicleDetails()
    }
}

private void handleSendCode() {
    if (!settings.contactMethod) {
        state.flowError = "Select a contact method first"
        return
    }
    boolean ok = sendVerificationCode(settings.contactMethod)
    state.codeSent = ok
    if (!ok) state.flowError = "Failed to send verification code"
}

private void handleVerifyCode() {
    if (!settings.verificationCode) {
        state.flowError = "Enter the code sent to you first"
        return
    }
    boolean ok = verifyCode(settings.verificationCode as String)
    if (!ok) {
        state.flowError = "Invalid or expired code - request a new one and try again"
        return
    }
    // Device registration can take a few seconds to take effect server-side.
    doLogin()
    state.needs2FA = !state.deviceRegistered
    if (state.needs2FA) {
        state.flowError = "Still finishing registration - wait a few seconds and click Verify Code again"
    } else {
        state.codeSent = false
        fetchVehicleDetails()
    }
}

def installed() {
    log.info "Subaru Connect installed"
    initialize()
}

def updated() {
    log.info "Subaru Connect updated"
    unschedule()
    if (state.savedPin != null && settings.subaruPin != state.savedPin) state.pinLockout = false
    state.savedPin = settings.subaruPin
    initialize()
}

def uninstalled() {
    getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    if (state.vehicles) {
        createChildDevices()
        runIn(5, "scheduledPoll")
    }
}

def scheduledPoll() {
    getChildDevices()?.each { cd -> componentRefresh(cd) }
    Integer mins = (settings.pollIntervalMinutes ?: 30) as Integer
    if (mins < 5) mins = 5
    runIn(mins * 60, "scheduledPoll")
}

private void createChildDevices() {
    state.vehicles.each { vin, info ->
        String dni = "subaru-${vin}"
        def cd = getChildDevice(dni)
        if (!cd) {
            cd = addChildDevice("jlslate", "Subaru Vehicle", dni, [
                name : "Subaru ${info.modelYear ?: ''} ${info.modelName ?: ''}".trim(),
                label: info.nickname ?: "Subaru ${info.modelName ?: vin}"
            ])
        }
        cd.updateDataValue("vin", vin)
        cd.sendEvent(name: "modelName", value: info.modelName)
        cd.sendEvent(name: "modelYear", value: info.modelYear)
    }
}

/* ---------------- Component commands, called by the child driver ---------------- */

def componentRefresh(childDevice) {
    String vin = childDevice.getDataValue("vin")
    if (!vin) return
    if (!ensureSession(vin)) {
        logWarn "Refresh failed: could not establish Subaru session for ${vin}"
        return
    }
    Map statusResp = subaruGet(API_VEHICLE_STATUS)
    if (statusResp?.success && statusResp.data) updateStatusAttributes(childDevice, vin, statusResp.data)

    if (hasRemoteService(vin) && effectiveApiGen(vin) == "g2") {
        String gen = effectiveApiGen(vin)
        Map conditionResp = subaruGet(API_CONDITION.replace("api_gen", gen))
        if (conditionResp?.success && conditionResp.data?.result) updateConditionAttributes(childDevice, vin, conditionResp.data.result as Map)

        Map locateResp = subaruGet(API_LOCATE.replace("api_gen", gen))
        if (locateResp?.success && locateResp.data?.result) updateLocationAttributes(childDevice, locateResp.data.result as Map)
    }

    if (hasRemoteStart(vin) || isEv(vin)) fetchPresets(childDevice, vin)
}

def componentLock(childDevice) {
    String vin = childDevice.getDataValue("vin")
    Map result = executeRemoteCommand(vin, API_LOCK, [forceKeyInCar: false])
    if (result?.success) childDevice.sendEvent(name: "lock", value: "locked")
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentUnlock(childDevice, String door = DOOR_ALL) {
    String vin = childDevice.getDataValue("vin")
    Map result = executeRemoteCommand(vin, API_UNLOCK, [unlockDoorType: door])
    if (result?.success) childDevice.sendEvent(name: "lock", value: "unlocked")
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentHornAndLights(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String poll = effectiveApiGen(vin) == "g1" ? API_G1_HORN_LIGHTS_STATUS : API_REMOTE_SVC_STATUS
    Map result = executeRemoteCommand(vin, API_HORN_LIGHTS, [:], poll)
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentStopHornAndLights(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String poll = effectiveApiGen(vin) == "g1" ? API_G1_HORN_LIGHTS_STATUS : API_REMOTE_SVC_STATUS
    Map result = executeRemoteCommand(vin, API_HORN_LIGHTS_STOP, [:], poll)
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentFlashLights(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String poll = effectiveApiGen(vin) == "g1" ? API_G1_HORN_LIGHTS_STATUS : API_REMOTE_SVC_STATUS
    Map result = executeRemoteCommand(vin, API_LIGHTS, [:], poll)
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentStopFlashLights(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String poll = effectiveApiGen(vin) == "g1" ? API_G1_HORN_LIGHTS_STATUS : API_REMOTE_SVC_STATUS
    Map result = executeRemoteCommand(vin, API_LIGHTS_STOP, [:], poll)
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentLocate(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String gen = effectiveApiGen(vin)
    String url = gen == "g1" ? API_G1_LOCATE_UPDATE : API_G2_LOCATE_UPDATE
    String poll = gen == "g1" ? API_G1_LOCATE_STATUS : API_G2_LOCATE_STATUS
    Map result = executeRemoteCommand(vin, url, [:], poll)
    if (result?.success && result?.result) updateLocationAttributes(childDevice, result.result as Map)
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentRemoteStop(childDevice) {
    String vin = childDevice.getDataValue("vin")
    if (!(hasRemoteStart(vin) || isEv(vin))) {
        logWarn "Remote stop not supported on ${vin}"
        return
    }
    Map result = executeRemoteCommand(vin, API_G2_REMOTE_ENGINE_STOP)
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentRemoteStart(childDevice, String presetName) {
    String vin = childDevice.getDataValue("vin")
    if (!hasRemoteStart(vin)) {
        logWarn "Remote start not supported on ${vin}"
        return
    }
    if (!state.vehiclePresets?.get(vin)) fetchPresets(childDevice, vin)
    Map preset = findPreset(vin, presetName)
    if (!preset) {
        logWarn "Preset '${presetName}' not found for ${vin}. Available: ${childDevice.currentValue('presets')}"
        childDevice.sendEvent(name: "lastCommandResult", value: "failed: unknown preset")
        return
    }
    Map saveResp = subaruPostJson(API_G2_SAVE_RES_QUICK_START_SETTINGS, preset)
    if (!saveResp?.success) {
        logWarn "Failed to stage climate preset '${presetName}': ${saveResp?.errorCode}"
        childDevice.sendEvent(name: "lastCommandResult", value: "failed: ${saveResp?.errorCode}")
        return
    }
    Map result = executeRemoteCommand(vin, API_G2_REMOTE_ENGINE_START, preset)
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

def componentChargeStart(childDevice) {
    String vin = childDevice.getDataValue("vin")
    if (!isEv(vin)) {
        logWarn "Charge start not supported on ${vin}"
        return
    }
    Map result = executeRemoteCommand(vin, API_EV_CHARGE_NOW)
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
}

/* ---------------- Attribute parsing ---------------- */

private void updateStatusAttributes(childDevice, String vin, Map data) {
    if (data.odometerValue != null) childDevice.sendEvent(name: "odometer", value: data.odometerValue)
    if (data.avgFuelConsumptionMpg && data.avgFuelConsumptionMpg != "16383") childDevice.sendEvent(name: "avgFuelConsumption", value: data.avgFuelConsumptionMpg)
    if (data.distanceToEmptyFuelMiles10s && data.distanceToEmptyFuelMiles10s != "16383") childDevice.sendEvent(name: "distanceToEmpty", value: data.distanceToEmptyFuelMiles10s)
    if (data.vehicleStateType) childDevice.sendEvent(name: "vehicleState", value: data.vehicleStateType)
    if (hasFeature(vin, "TPMS_MIL")) {
        [tirePressureFrontLeftPsi: "tirePressureFL", tirePressureFrontRightPsi: "tirePressureFR",
         tirePressureRearLeftPsi: "tirePressureRL", tirePressureRearRightPsi: "tirePressureRR"].each { apiKey, attr ->
            if (data[apiKey] && data[apiKey] != "32767") childDevice.sendEvent(name: attr, value: data[apiKey])
        }
    }
    if (data.latitude != null && data.longitude != null && data.latitude != 90 && data.longitude != 180) {
        childDevice.sendEvent(name: "latitude", value: data.latitude)
        childDevice.sendEvent(name: "longitude", value: data.longitude)
        childDevice.sendEvent(name: "locationValid", value: "true")
    }
}

private void updateConditionAttributes(childDevice, String vin, Map data) {
    [doorBootPosition: "doorBoot", doorEngineHoodPosition: "doorEngineHood",
     doorFrontLeftPosition: "doorFrontLeft", doorFrontRightPosition: "doorFrontRight",
     doorRearLeftPosition: "doorRearLeft", doorRearRightPosition: "doorRearRight"].each { apiKey, attr ->
        if (data[apiKey]) childDevice.sendEvent(name: attr, value: data[apiKey])
    }

    if (data.remainingFuelPercent != null) childDevice.sendEvent(name: "fuelPercent", value: data.remainingFuelPercent)

    List locks = [data.doorFrontLeftLockStatus, data.doorFrontRightLockStatus, data.doorRearLeftLockStatus, data.doorRearRightLockStatus].findAll { it }
    if (locks) {
        String lockState
        if (locks.every { it == "LOCKED" }) lockState = "locked"
        else if (locks.any { it == "UNLOCKED" }) lockState = "unlocked"
        else lockState = "unknown"
        childDevice.sendEvent(name: "lock", value: lockState)
    }

    [windowFrontLeftStatus: "windowFrontLeft", windowFrontRightStatus: "windowFrontRight",
     windowRearLeftStatus: "windowRearLeft", windowRearRightStatus: "windowRearRight",
     windowSunroofStatus: "windowSunroof"].each { apiKey, attr ->
        if (data[apiKey]) childDevice.sendEvent(name: attr, value: data[apiKey])
    }

    if (isEv(vin)) {
        if (data.evStateOfChargePercent != null) childDevice.sendEvent(name: "evChargePercent", value: data.evStateOfChargePercent)
        if (data.evIsPluggedIn) childDevice.sendEvent(name: "evPluggedIn", value: data.evIsPluggedIn)
        if (data.evChargerStateType) childDevice.sendEvent(name: "evChargerState", value: data.evChargerStateType)
        if (data.evDistanceToEmpty != null) childDevice.sendEvent(name: "evDistanceToEmpty", value: data.evDistanceToEmpty)
        if (data.evTimeToFullyCharged != null) childDevice.sendEvent(name: "evTimeToFullyCharged", value: data.evTimeToFullyCharged)
    }

    if (data.lastUpdatedTime) childDevice.sendEvent(name: "lastUpdated", value: data.lastUpdatedTime)
}

private void updateLocationAttributes(childDevice, Map data) {
    if (data.latitude != null && data.longitude != null && data.latitude != 90 && data.longitude != 180) {
        childDevice.sendEvent(name: "latitude", value: data.latitude)
        childDevice.sendEvent(name: "longitude", value: data.longitude)
        childDevice.sendEvent(name: "locationValid", value: "true")
    } else {
        childDevice.sendEvent(name: "locationValid", value: "false")
    }
}

private void fetchPresets(childDevice, String vin) {
    List presets = []
    Map builtIn = subaruGet(API_G2_FETCH_RES_SUBARU_PRESETS)
    if (builtIn?.success && builtIn.data instanceof List) {
        builtIn.data.each { raw ->
            try {
                Map p = new JsonSlurper().parseText(raw as String) as Map
                if ((isEv(vin) && p.vehicleType == "phev") || (!isEv(vin) && p.vehicleType == "gas")) presets << p
            } catch (Exception e) {
                logDebug "Failed to parse built-in climate preset: ${e.message}"
            }
        }
    }
    Map userPresets = subaruGet(API_G2_FETCH_RES_USER_PRESETS)
    if (userPresets?.success && userPresets.data instanceof String) {
        try {
            List parsed = new JsonSlurper().parseText(userPresets.data as String) as List
            presets.addAll(parsed)
        } catch (Exception e) {
            logDebug "Failed to parse user climate presets: ${e.message}"
        }
    }
    state.vehiclePresets = state.vehiclePresets ?: [:]
    state.vehiclePresets[vin] = presets
    childDevice.sendEvent(name: "presets", value: presets.collect { it.name }.join(", "))
}

private Map findPreset(String vin, String name) {
    (state.vehiclePresets?.get(vin) ?: []).find { it.name == name } as Map
}

/* ---------------- Vehicle capability helpers ---------------- */

private boolean hasFeature(String vin, String feature) {
    (state.vehicles[vin]?.features ?: []).contains(feature)
}

private String apiGenOf(String vin) {
    List f = state.vehicles[vin]?.features ?: []
    if (f.contains("g4")) return "g4"
    if (f.contains("g3")) return "g3"
    if (f.contains("g2")) return "g2"
    if (f.contains("g1")) return "g1"
    return "unknown"
}

// G3/G4 vehicles share the G2 endpoint set; only G1 has its own.
private String effectiveApiGen(String vin) {
    String gen = apiGenOf(vin)
    return (gen == "g3" || gen == "g4") ? "g2" : gen
}

private boolean isEv(String vin) {
    hasFeature(vin, "PHEV")
}

private boolean hasRemoteService(String vin) {
    List sub = state.vehicles[vin]?.subscriptionFeatures ?: []
    boolean active = state.vehicles[vin]?.subscriptionStatus == "ACTIVE"
    return active && (sub.contains("REMOTE") || sub.contains("COMPANION_PLUS"))
}

private boolean hasRemoteStart(String vin) {
    hasFeature(vin, "RES") && hasRemoteService(vin)
}

/* ---------------- Session / auth ---------------- */

private boolean doLogin() {
    Map body = [
        env             : "cloudprod",
        loginUsername   : settings.subaruUsername,
        password        : settings.subaruPassword,
        deviceId        : state.deviceId,
        passwordToken   : "",
        selectedVin     : "",
        pushToken       : "",
        deviceType      : "android"
    ]
    Map resp = subaruPostForm(API_LOGIN, body)
    if (resp?.success) {
        state.authenticated = true
        state.sessionLoginTime = now()
        state.deviceRegistered = resp.data?.deviceRegistered ?: false
        state.vins = (resp.data?.vehicles ?: []).collect { it.vin }
        state.currentVin = ""
        state.loginError = null
        return true
    }
    state.authenticated = false
    state.loginError = resp?.errorCode ?: "Unknown error"
    return false
}

private boolean fetchContactMethods() {
    Map resp = subaruPostForm(API_2FA_CONTACT, [:])
    if (resp?.success) {
        state.contactOptions = resp.data
        return true
    }
    return false
}

private boolean sendVerificationCode(String contactMethod) {
    Map resp = subaruPostQuery(API_2FA_SEND_VERIFICATION, [contactMethod: contactMethod, languagePreference: "EN"])
    return resp?.success == true
}

private boolean verifyCode(String code) {
    Map resp = subaruPostQuery(API_2FA_AUTH_VERIFY, [
        deviceId        : state.deviceId,
        deviceName      : settings.deviceName ?: "Hubitat",
        verificationCode: code,
        rememberDevice  : "on"
    ])
    return resp?.success == true
}

private void fetchVehicleDetails() {
    Map vehicles = [:]
    (state.vins ?: []).each { vin ->
        Map resp = subaruGet(API_SELECT_VEHICLE, [vin: vin, "_": now()])
        if (resp?.success && resp.data) {
            vehicles[vin] = [
                vin                  : vin,
                nickname             : resp.data.nickname,
                modelName            : resp.data.modelName,
                modelYear            : resp.data.modelYear,
                features             : resp.data.features ?: [],
                subscriptionFeatures : resp.data.subscriptionFeatures ?: [],
                subscriptionStatus   : resp.data.subscriptionStatus
            ]
            state.currentVin = vin
        }
    }
    state.vehicles = vehicles
}

private boolean selectVehicle(String vin) {
    Map resp = subaruGet(API_SELECT_VEHICLE, [vin: vin, "_": now()])
    if (resp?.success) {
        state.currentVin = vin
        return true
    }
    return false
}

private boolean ensureSession(String vin) {
    if (!state.authenticated) {
        if (!doLogin()) return false
    }
    // Subaru's backend session tends to go stale after a few hours; force a fresh login.
    if ((now() - (state.sessionLoginTime ?: 0)) > (240 * 60000)) {
        state.cookies = null
        state.cookieJar = [:]
        if (!doLogin()) return false
    }
    Map resp = subaruGet(API_VALIDATE_SESSION)
    if (resp?.success && state.currentVin == vin) return true
    if (resp?.success) return selectVehicle(vin)
    if (!doLogin()) return false
    return selectVehicle(vin)
}

/* ---------------- Remote command execution + polling ---------------- */

private Map executeRemoteCommand(String vin, String path, Map extraBody = [:], String pollPath = null) {
    if (state.pinLockout) return [success: false, errorCode: "PIN_LOCKOUT"]
    if (!ensureSession(vin)) return [success: false, errorCode: "SESSION_FAILED"]
    String gen = effectiveApiGen(vin)
    String resolvedPath = path.replace("api_gen", gen)
    String resolvedPoll = (pollPath ?: API_REMOTE_SVC_STATUS).replace("api_gen", gen)
    Map body = [pin: settings.subaruPin, delay: 0, vin: vin] + extraBody
    Map resp = subaruPostJson(resolvedPath, body)
    if (resp?.errorCode in ["InvalidCredentials", "SXM40006"]) {
        state.pinLockout = true
        logWarn "Subaru rejected the remote services PIN - commands disabled until it's corrected in app settings"
        return [success: false, errorCode: "INVALID_PIN"]
    }
    if (resp?.errorCode == "ServiceAlreadyStarted" || resp?.errorCode == "SXM40009") {
        // A prior command is still in flight server-side; treat as non-fatal, nothing more to do here.
        return [success: false, errorCode: resp.errorCode]
    }
    if (!resp?.success) {
        logWarn "Command ${resolvedPath} failed: ${resp?.errorCode}"
        return [success: false, errorCode: resp?.errorCode ?: "REQUEST_FAILED"]
    }
    String reqId = resp.data?.serviceRequestId
    if (!reqId) return [success: false, errorCode: "NO_REQUEST_ID"]
    return pollServiceRequest(vin, reqId, resolvedPoll)
}

private Map pollServiceRequest(String vin, String reqId, String pollPath) {
    // Real vehicle commands take 5-20s round trip; give it up to 45s total before giving up.
    long deadline = now() + 45000
    double intervalSec = 2.0
    while (now() < deadline) {
        Map resp = subaruGet(pollPath, [serviceRequestId: reqId])
        if (resp?.errorCode) {
            if (resp.errorCode in ["403-soa-unableToParseResponseBody", "InvalidToken"]) {
                state.cookies = null
                state.cookieJar = [:]
                ensureSession(vin)
                pauseExecution(1000)
                continue
            }
            return [success: false, errorCode: resp.errorCode]
        }
        Map data = resp?.data as Map
        if (data?.remoteServiceState == "finished") return data
        pauseExecution((intervalSec * 1000) as int)
        intervalSec = Math.min(intervalSec * 1.5, 15.0)
    }
    return [success: false, errorCode: "TIMEOUT"]
}

/* ---------------- HTTP plumbing ---------------- */

private String currentApiVersion() {
    if (!state.apiVersion) state.apiVersion = API_VERSION
    state.apiVersion
}

// Subaru periodically bumps its API version (e.g. /g2v33 -> /g2v34), which breaks every
// hardcoded path with an HTTP 404 until updated. Auto-increment in place (persisted in
// state) so the integration keeps working without a code update, same trick subarulink uses.
private boolean bumpApiVersion() {
    String v = currentApiVersion()
    java.util.regex.Matcher m = (v =~ /\d+$/)
    if (!m.find()) return false
    String numStr = m.group()
    int next = (numStr as int) + 1
    state.apiVersion = v.substring(0, v.length() - numStr.length()) + next
    state.apiVersionRetries = (state.apiVersionRetries ?: 0) + 1
    logWarn "Subaru API HTTP 404 - bumping API version to ${state.apiVersion} and retrying"
    return (state.apiVersionRetries as int) <= 5
}

private String apiBase() {
    "https://${API_SERVER[settings.country ?: 'USA']}${currentApiVersion()}"
}

private Map httpHeaders() {
    Map h = [
        "User-Agent"      : "Mozilla/5.0 (Linux; Android 10; Android SDK built for x86 Build/QSR1.191030.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/74.0.3729.185 Mobile Safari/537.36",
        "Origin"          : "file://",
        "X-Requested-With": API_MOBILE_APP[settings.country ?: "USA"],
        "Accept-Language" : "en-US,en;q=0.9",
        "Accept"          : "*/*"
    ]
    if (state.cookies) h["Cookie"] = state.cookies
    return h
}

// Subaru's API is cookie/session based (mirrors the mobile app's cookie jar), not token based.
// Hubitat's sync HTTP client exposes response headers as an iterable of {name, value} pairs, so
// duplicate Set-Cookie headers (there are usually several) are folded into a single Cookie jar here.
private void captureCookies(resp) {
    try {
        def headers = resp?.headers
        if (!headers) return
        List rawCookies = []
        headers.each { h ->
            try {
                if (h?.name?.toString()?.equalsIgnoreCase("Set-Cookie")) rawCookies << h.value?.toString()
            } catch (ignored) { }
        }
        if (!rawCookies) return
        Map jar = state.cookieJar ? new LinkedHashMap(state.cookieJar as Map) : [:]
        rawCookies.each { raw ->
            String pair = raw?.split(";")?.getAt(0)
            int idx = pair ? pair.indexOf("=") : -1
            if (idx > 0) jar[pair.substring(0, idx)] = pair.substring(idx + 1)
        }
        state.cookieJar = jar
        state.cookies = jar.collect { k, v -> "${k}=${v}" }.join("; ")
        logDebug "Cookie jar now: ${state.cookies}"
    } catch (Exception e) {
        logDebug "captureCookies error: ${e.message}"
    }
}

private Map subaruGet(String path, Map query = [:]) {
    while (true) {
        Map result = [success: false]
        Map params = [uri: apiBase() + path, headers: httpHeaders(), timeout: 25]
        if (query) params.query = query
        try {
            httpGet(params) { resp ->
                captureCookies(resp)
                result = (resp?.data instanceof Map) ? resp.data as Map : [success: false]
            }
            return result
        } catch (groovyx.net.http.HttpResponseException e) {
            if (e.statusCode == 404 && bumpApiVersion()) continue
            logWarn "GET ${path} failed: HTTP ${e.statusCode}"
            return [success: false, errorCode: "HTTP_${e.statusCode}"]
        } catch (Exception e) {
            logWarn "GET ${path} failed: ${e.message}"
            return [success: false, errorCode: "EXCEPTION", message: e.message]
        }
    }
}

private Map subaruPostForm(String path, Map body) {
    while (true) {
        Map result = [success: false]
        Map params = [uri: apiBase() + path, headers: httpHeaders(), body: body, requestContentType: "application/x-www-form-urlencoded", timeout: 25]
        try {
            httpPost(params) { resp ->
                captureCookies(resp)
                result = (resp?.data instanceof Map) ? resp.data as Map : [success: false]
            }
            return result
        } catch (groovyx.net.http.HttpResponseException e) {
            if (e.statusCode == 404 && bumpApiVersion()) continue
            logWarn "POST(form) ${path} failed: HTTP ${e.statusCode}"
            return [success: false, errorCode: "HTTP_${e.statusCode}"]
        } catch (Exception e) {
            logWarn "POST(form) ${path} failed: ${e.message}"
            return [success: false, errorCode: "EXCEPTION", message: e.message]
        }
    }
}

private Map subaruPostQuery(String path, Map query) {
    while (true) {
        Map result = [success: false]
        Map params = [uri: apiBase() + path, headers: httpHeaders(), query: query, timeout: 25]
        try {
            httpPost(params) { resp ->
                captureCookies(resp)
                result = (resp?.data instanceof Map) ? resp.data as Map : [success: false]
            }
            return result
        } catch (groovyx.net.http.HttpResponseException e) {
            if (e.statusCode == 404 && bumpApiVersion()) continue
            logWarn "POST(query) ${path} failed: HTTP ${e.statusCode}"
            return [success: false, errorCode: "HTTP_${e.statusCode}"]
        } catch (Exception e) {
            logWarn "POST(query) ${path} failed: ${e.message}"
            return [success: false, errorCode: "EXCEPTION", message: e.message]
        }
    }
}

private Map subaruPostJson(String path, Map jsonBody) {
    while (true) {
        Map result = [success: false]
        Map params = [uri: apiBase() + path, headers: httpHeaders(), body: jsonBody, timeout: 25]
        try {
            httpPostJson(params) { resp ->
                captureCookies(resp)
                result = (resp?.data instanceof Map) ? resp.data as Map : [success: false]
            }
            return result
        } catch (groovyx.net.http.HttpResponseException e) {
            if (e.statusCode == 404 && bumpApiVersion()) continue
            logWarn "POST(json) ${path} failed: HTTP ${e.statusCode}"
            return [success: false, errorCode: "HTTP_${e.statusCode}"]
        } catch (Exception e) {
            logWarn "POST(json) ${path} failed: ${e.message}"
            return [success: false, errorCode: "EXCEPTION", message: e.message]
        }
    }
}

private void logDebug(String msg) {
    if (settings.enableLogging) log.debug msg
}

private void logWarn(String msg) {
    log.warn msg
}
