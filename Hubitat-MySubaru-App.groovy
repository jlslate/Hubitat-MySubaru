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

// Vehicle-health / warning-lamp status. Plain GET at the API base (not under /service), no PIN.
// Returns data.vehicleHealthItems[] where each item has featureCode / isTrouble / onDates.
@Field static final String API_VEHICLE_HEALTH = "/vehicleHealth.json"

@Field static final String DOOR_ALL = "ALL_DOORS_CMD"

// Warning lamps worth their own attribute (highest home-automation value); every other lamp still
// rolls up into warningLamps/warningLampsActive. Keyed by the API's featureCode.
@Field static final Map HEALTH_CURATED = ["CEL_MIL": "checkEngine", "WASH_MIL": "washerFluid", "TPMS_MIL": "tirePressureWarning"]

// Kept in sync with packageManifest.json's version - shown in the UI and logs to make it
// easy to tell which release someone's running when troubleshooting.
@Field static final String CODE_VERSION = "1.5.0"

definition(
    name: "Subaru Connect",
    namespace: "jlslate",
    author: "jlslate (slate)",
    description: "Links your MySubaru STARLINK account to Hubitat: lock/unlock, horn/lights, remote start/stop, and vehicle status for each enrolled vehicle",
    category: "Convenience",
    menu: "Integrations",
    importUrl: "https://raw.githubusercontent.com/jlslate/Hubitat-MySubaru/main/Hubitat-MySubaru-App.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Subaru Connect", install: canInstall(), uninstall: true) {
        section {
            paragraph "Subaru Connect v${CODE_VERSION}"
        }
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
            input name: "txtEnable", type: "bool", title: "Enable descriptive text logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
            input name: "traceEnable", type: "bool", title: "Enable trace logging (very verbose - raw HTTP/session detail)", defaultValue: false
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
    logTxt "Subaru Connect v${CODE_VERSION} installed"
    manageLogging()
    initialize()
}

def updated() {
    logTxt "Subaru Connect v${CODE_VERSION} updated"
    unschedule()
    unsubscribe()
    if (state.savedPin != null && settings.subaruPin != state.savedPin) state.pinLockout = false
    state.savedPin = settings.subaruPin
    manageLogging()
    initialize()
}

// Auto-disables debug/trace logging after 30 minutes so it doesn't run indefinitely once
// someone turns it on to troubleshoot and forgets about it.
private void manageLogging() {
    unschedule("logsOff")
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.warn "Debug/trace logging disabled after 30 minutes"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

def uninstalled() {
    getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    if (state.vehicles) {
        createChildDevices()
        // Re-arm the poll loop after a hub reboot, since a runIn chain doesn't survive one.
        subscribe(location, "systemStart", "scheduledPoll")
        runIn(5, "scheduledPoll")
    }
}

// evt is unused and only present because the systemStart subscription below dispatches with
// an Event argument, while runIn's self-reschedule calls this with none - the default makes
// both call shapes resolve to the same method.
def scheduledPoll(evt = null) {
    // Schedule the next run first so one bad cycle (a flaky vendor API, a thrown exception)
    // can't silently kill the polling loop - only the reschedule call below guarantees that.
    Integer mins = Math.max(5, (settings.pollIntervalMinutes ?: 30) as Integer)
    runIn(mins * 60, "scheduledPoll")
    try {
        getChildDevices()?.each { cd -> componentRefresh(cd) }
    } catch (Exception e) {
        logWarn "scheduledPoll: ${e.message}"
    }
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
            // New device: seed lock as "unknown" so the Lock capability has a defined initial
            // state. It becomes locked/unlocked only after a command - Subaru never reports lock
            // status via polls (see updateConditionAttributes).
            cd.sendEvent(name: "lock", value: "unknown")
        }
        cd.updateDataValue("vin", vin)
        cd.sendEvent(name: "modelName", value: info.modelName)
        cd.sendEvent(name: "modelYear", value: info.modelYear)
    }
}

/* ---------------- Component commands, called by the child driver ---------------- */

void componentRefresh(childDevice) {
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

        Map healthResp = subaruGet(API_VEHICLE_HEALTH)
        if (healthResp?.data instanceof Map && healthResp.data.vehicleHealthItems instanceof List) {
            updateHealthAttributes(childDevice, vin, healthResp.data.vehicleHealthItems as List)
        }
    }

    if (hasRemoteStart(vin) || isEv(vin)) fetchPresets(childDevice, vin)
}

void componentLock(childDevice) {
    String vin = childDevice.getDataValue("vin")
    executeRemoteCommand(childDevice, vin, API_LOCK, [forceKeyInCar: false], null, "LOCK")
}

void componentUnlock(childDevice, String door = DOOR_ALL) {
    String vin = childDevice.getDataValue("vin")
    executeRemoteCommand(childDevice, vin, API_UNLOCK, [unlockDoorType: door], null, "UNLOCK")
}

void componentHornAndLights(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String poll = effectiveApiGen(vin) == "g1" ? API_G1_HORN_LIGHTS_STATUS : API_REMOTE_SVC_STATUS
    executeRemoteCommand(childDevice, vin, API_HORN_LIGHTS, [:], poll)
}

void componentStopHornAndLights(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String poll = effectiveApiGen(vin) == "g1" ? API_G1_HORN_LIGHTS_STATUS : API_REMOTE_SVC_STATUS
    executeRemoteCommand(childDevice, vin, API_HORN_LIGHTS_STOP, [:], poll)
}

void componentFlashLights(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String poll = effectiveApiGen(vin) == "g1" ? API_G1_HORN_LIGHTS_STATUS : API_REMOTE_SVC_STATUS
    executeRemoteCommand(childDevice, vin, API_LIGHTS, [:], poll)
}

void componentStopFlashLights(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String poll = effectiveApiGen(vin) == "g1" ? API_G1_HORN_LIGHTS_STATUS : API_REMOTE_SVC_STATUS
    executeRemoteCommand(childDevice, vin, API_LIGHTS_STOP, [:], poll)
}

void componentLocate(childDevice) {
    String vin = childDevice.getDataValue("vin")
    String gen = effectiveApiGen(vin)
    String url = gen == "g1" ? API_G1_LOCATE_UPDATE : API_G2_LOCATE_UPDATE
    String poll = gen == "g1" ? API_G1_LOCATE_STATUS : API_G2_LOCATE_STATUS
    executeRemoteCommand(childDevice, vin, url, [:], poll, "LOCATE")
}

// Active "update from vehicle": wakes the car to report fresh telematics (~30s), then pulls the
// refreshed cached data. Uses the same locate/execute flow as componentLocate; the "UPDATE" kind
// tells finishCommand to run a follow-up refresh. Throttled and manual-only - it nibbles the 12V
// battery, so it must never run on the poll schedule.
@Field static final long ACTIVE_UPDATE_THROTTLE_MS = 300000L

void componentUpdateFromVehicle(childDevice) {
    String vin = childDevice.getDataValue("vin")
    if (!hasRemoteService(vin)) {
        logWarn "Update from vehicle needs an active remote subscription (${vin})"
        childDevice.sendEvent(name: "lastCommandResult", value: "failed: no remote subscription")
        return
    }
    Map lastMap = (state.lastActiveUpdate ?: [:]) as Map
    long sinceMs = now() - ((lastMap[vin] ?: 0L) as long)
    if (sinceMs < ACTIVE_UPDATE_THROTTLE_MS) {
        int waitS = ((ACTIVE_UPDATE_THROTTLE_MS - sinceMs) / 1000L) as int
        logTxt "Update from vehicle throttled - retry in ${waitS}s (protects the 12V battery)"
        childDevice.sendEvent(name: "lastCommandResult", value: "throttled: retry in ${waitS}s")
        return
    }
    lastMap[vin] = now()
    state.lastActiveUpdate = lastMap
    String gen = effectiveApiGen(vin)
    String url = gen == "g1" ? API_G1_LOCATE_UPDATE : API_G2_LOCATE_UPDATE
    String poll = gen == "g1" ? API_G1_LOCATE_STATUS : API_G2_LOCATE_STATUS
    logTxt "Requesting active update from vehicle ${vin} - wakes the car, ~30s"
    executeRemoteCommand(childDevice, vin, url, [:], poll, "UPDATE")
}

// Follow-up after an active update completes: the car has reported to Subaru's servers, so a normal
// cached refresh now returns fresh odometer / tire pressures / vehicle state.
def refreshAfterActiveUpdate(Map data) {
    def cd = getChildDevice(data.dni as String)
    if (cd) componentRefresh(cd)
}

void componentRemoteStop(childDevice) {
    String vin = childDevice.getDataValue("vin")
    if (!(hasRemoteStart(vin) || isEv(vin))) {
        logWarn "Remote stop not supported on ${vin}"
        return
    }
    executeRemoteCommand(childDevice, vin, API_G2_REMOTE_ENGINE_STOP)
}

void componentRemoteStart(childDevice, String presetName) {
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
    // Staging the preset is a single quick call, not the multi-attempt poll loop below, so it
    // stays synchronous.
    Map saveResp = subaruPostJson(API_G2_SAVE_RES_QUICK_START_SETTINGS, preset)
    if (!saveResp?.success) {
        logWarn "Failed to stage climate preset '${presetName}': ${saveResp?.errorCode}"
        childDevice.sendEvent(name: "lastCommandResult", value: "failed: ${saveResp?.errorCode}")
        return
    }
    executeRemoteCommand(childDevice, vin, API_G2_REMOTE_ENGINE_START, preset)
}

void componentChargeStart(childDevice) {
    String vin = childDevice.getDataValue("vin")
    if (!isEv(vin)) {
        logWarn "Charge start not supported on ${vin}"
        return
    }
    executeRemoteCommand(childDevice, vin, API_EV_CHARGE_NOW)
}

/* ---------------- Attribute parsing ---------------- */

private void updateStatusAttributes(childDevice, String vin, Map data) {
    // Subaru's API returns both imperial and native-metric fields (e.g. odometerValue and
    // odometerValueKilometers). For CAN, read the metric fields directly rather than converting -
    // Subaru's own km value is authoritative (matches the dash). Tire pressures are reported in PSI,
    // so those are converted for CAN. Sentinels (16383/32767) may be string or number.
    boolean metric = (settings.country == "CAN")

    BigDecimal odo = asNum(metric ? data.odometerValueKilometers : data.odometerValue)
    if (odo != null) childDevice.sendEvent(name: "odometer", value: odo, unit: metric ? "km" : "mi")

    def fcRaw = metric ? data.avgFuelConsumptionLitersPer100Kilometers : data.avgFuelConsumptionMpg
    BigDecimal fc = asNum(fcRaw)
    if (fc != null && fcRaw.toString() != "16383") childDevice.sendEvent(name: "avgFuelConsumption", value: fc, unit: metric ? "L/100km" : "mpg")

    def dteRaw = metric ? data.distanceToEmptyFuelKilometers10s : data.distanceToEmptyFuelMiles10s
    BigDecimal dte = asNum(dteRaw)
    if (dte != null && dteRaw.toString() != "16383") childDevice.sendEvent(name: "distanceToEmpty", value: dte, unit: metric ? "km" : "mi")

    if (data.vehicleStateType) childDevice.sendEvent(name: "vehicleState", value: data.vehicleStateType)

    // Tire pressures come in PSI; convert to kPa for CAN. (Previously gated on a "TPMS_MIL" feature
    // flag - unnecessary; subarulink applies no such gate. Values are null until the car reports.)
    [tirePressureFrontLeftPsi: "tirePressureFL", tirePressureFrontRightPsi: "tirePressureFR",
     tirePressureRearLeftPsi: "tirePressureRL", tirePressureRearRightPsi: "tirePressureRR"].each { apiKey, attr ->
        BigDecimal psi = asNum(data[apiKey])
        if (psi != null && data[apiKey].toString() != "32767") childDevice.sendEvent(name: attr, value: metric ? psiToKpa(psi) : psi, unit: metric ? "kPa" : "psi")
    }

    // Location is emitted only from updateLocationAttributes (the locate payload). vehicleStatus and
    // locate report the same coordinate at slightly different precision; emitting both flip-flopped
    // the lat/long events on every refresh.

    emitRecommendedTirePressure(childDevice, vin)
}

// Manufacturer-recommended cold tire pressure, encoded in the vehicle's feature flags as
// "TIF_<psi>" (front) / "TIR_<psi>" (rear) - no separate API call needed. PSI -> kPa for CAN.
private void emitRecommendedTirePressure(childDevice, String vin) {
    boolean metric = (settings.country == "CAN")
    List feats = state.vehicles[vin]?.features ?: []
    def front = feats.find { it?.startsWith("TIF_") }
    def rear  = feats.find { it?.startsWith("TIR_") }
    BigDecimal fp = front ? asNum(front.tokenize("_").getAt(1)) : null
    BigDecimal rp = rear  ? asNum(rear.tokenize("_").getAt(1)) : null
    if (fp != null) childDevice.sendEvent(name: "recommendedTirePressureFront", value: metric ? psiToKpa(fp) : fp, unit: metric ? "kPa" : "psi")
    if (rp != null) childDevice.sendEvent(name: "recommendedTirePressureRear",  value: metric ? psiToKpa(rp) : rp, unit: metric ? "kPa" : "psi")
}

/* ---------------- Numeric helpers (Subaru reports PSI; gives native metric distance/fuel fields) ---------------- */

private BigDecimal asNum(v) {
    if (v == null) return null
    try { return v as BigDecimal } catch (Exception ignored) { return null }
}
private BigDecimal psiToKpa(BigDecimal psi) { BigDecimal.valueOf(Math.round(psi.toDouble() * 6.894757d)) }
private BigDecimal round5(BigDecimal v)     { v.setScale(5, BigDecimal.ROUND_HALF_UP) }

private void updateConditionAttributes(childDevice, String vin, Map data) {
    [doorBootPosition: "doorBoot", doorEngineHoodPosition: "doorEngineHood",
     doorFrontLeftPosition: "doorFrontLeft", doorFrontRightPosition: "doorFrontRight",
     doorRearLeftPosition: "doorRearLeft", doorRearRightPosition: "doorRearRight"].each { apiKey, attr ->
        if (data[apiKey]) childDevice.sendEvent(name: attr, value: data[apiKey])
    }

    if (data.remainingFuelPercent != null) childDevice.sendEvent(name: "fuelPercent", value: data.remainingFuelPercent)

    // Lock status is intentionally NOT derived from polls. Subaru's API reports door lock status as
    // UNKNOWN except right after a remote command (confirmed on-vehicle, and the Home Assistant
    // reference integration notes lock status "is always unknown"). The lock attribute reflects the
    // last commanded state, set in finishCommand(), so refreshes no longer clobber it to "unknown".

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
        // 65535 is Subaru's "no valid reading" sentinel for time-to-full (e.g. not charging) - skip it,
        // same handling as the 16383/32767 sentinels above.
        if (data.evTimeToFullyCharged != null && data.evTimeToFullyCharged.toString() != "65535") childDevice.sendEvent(name: "evTimeToFullyCharged", value: data.evTimeToFullyCharged)
    }

    if (data.lastUpdatedTime) childDevice.sendEvent(name: "lastUpdated", value: data.lastUpdatedTime)
}

private void updateLocationAttributes(childDevice, Map data) {
    BigDecimal lat = asNum(data.latitude)
    BigDecimal lon = asNum(data.longitude)
    if (lat != null && lon != null && lat != 90 && lon != 180) {
        // Round to ~1 m (5 dp). Subaru's cached fix jitters in the last decimals between reports,
        // which would otherwise fire a new lat/long event on every refresh even while parked.
        childDevice.sendEvent(name: "latitude", value: round5(lat))
        childDevice.sendEvent(name: "longitude", value: round5(lon))
        childDevice.sendEvent(name: "locationValid", value: "true")
    } else {
        childDevice.sendEvent(name: "locationValid", value: "false")
    }
}

// Vehicle-health warning lamps. The endpoint returns every lamp the platform knows about; we keep
// only the ones this vehicle actually advertises in its feature list (mirrors subarulink), roll the
// active ones up into warningStatus/warningLampsActive/warningLamps, and surface a curated few as
// their own attributes for direct RM/dashboard use. Each item: featureCode, isTrouble, onDates.
private void updateHealthAttributes(childDevice, String vin, List items) {
    List feats = state.vehicles[vin]?.features ?: []
    List applicable = items.findAll { it instanceof Map && it.featureCode && (feats.isEmpty() || feats.contains(it.featureCode)) }
    List active = applicable.findAll { isTroubleFlag(it.isTrouble) }

    childDevice.sendEvent(name: "warningStatus", value: active ? "warning" : "clear")
    childDevice.sendEvent(name: "warningLampsActive", value: active.size())
    childDevice.sendEvent(name: "warningLamps", value: active.collect { it.b2cCode ?: it.featureCode }.join(", "))

    HEALTH_CURATED.each { code, attr ->
        def item = applicable.find { it.featureCode == code }
        if (item != null) childDevice.sendEvent(name: attr, value: isTroubleFlag(item.isTrouble) ? "warning" : "clear")
    }
}

// Defensive: the API returns a JSON boolean, but tolerate a stringified "true" just in case.
private boolean isTroubleFlag(v) {
    v == true || v?.toString()?.equalsIgnoreCase("true")
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
    // Reassign the whole top-level key rather than mutating the nested map in place -
    // Hubitat's state persistence doesn't reliably detect in-place mutation of nested collections.
    Map presetsByVin = (state.vehiclePresets ?: [:]) as Map
    presetsByVin[vin] = presets
    state.vehiclePresets = presetsByVin
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

/* ---------------- Remote command execution + polling ----------------
 * Fully async: fire the command, then poll for completion via runInMillis
 * continuations instead of blocking a platform thread with pauseExecution.
 * A single command can take up to ~45s round trip (Subaru's own server-side
 * timing), which is too long to hold a synchronous Hubitat worker thread for.
 *
 * "kind" tags the command so finishCommand() knows what device-state side
 * effect (if any) to apply once it completes: LOCK/UNLOCK set the lock
 * attribute, LOCATE updates lat/long, anything else just records
 * lastCommandResult.
 */

private void executeRemoteCommand(childDevice, String vin, String path, Map extraBody = [:], String pollPath = null, String kind = "GENERIC") {
    Map ctx = [dni: childDevice.deviceNetworkId, vin: vin, kind: kind]
    if (state.pinLockout) { finishCommand(ctx, [success: false, errorCode: "PIN_LOCKOUT"]); return }
    if (!ensureSession(vin)) { finishCommand(ctx, [success: false, errorCode: "SESSION_FAILED"]); return }
    String gen = effectiveApiGen(vin)
    String resolvedPath = path.replace("api_gen", gen)
    ctx.pollPath = (pollPath ?: API_REMOTE_SVC_STATUS).replace("api_gen", gen)
    Map body = [pin: settings.subaruPin, delay: 0, vin: vin] + extraBody
    Map params = [uri: apiBase() + resolvedPath, headers: httpHeaders(), body: body, requestContentType: "application/json", timeout: 25]
    asynchttpPost("onCommandPosted", params, ctx)
}

def onCommandPosted(resp, Map ctx) {
    Map result = parseAsyncJson(resp)
    if (result == null) { finishCommand(ctx, [success: false, errorCode: "HTTP_${resp?.status ?: 'ERROR'}"]); return }
    if (result.errorCode in ["InvalidCredentials", "SXM40006"]) {
        state.pinLockout = true
        logWarn "Subaru rejected the remote services PIN - commands disabled until it's corrected in app settings"
        finishCommand(ctx, [success: false, errorCode: "INVALID_PIN"])
        return
    }
    if (result.errorCode in ["ServiceAlreadyStarted", "SXM40009"]) {
        // A prior command is still in flight server-side; treat as non-fatal, nothing more to do here.
        finishCommand(ctx, [success: false, errorCode: result.errorCode])
        return
    }
    if (!result.success) {
        logWarn "Command ${ctx.pollPath} failed: ${result.errorCode}"
        finishCommand(ctx, [success: false, errorCode: result.errorCode ?: "REQUEST_FAILED"])
        return
    }
    String reqId = result.data?.serviceRequestId
    if (!reqId) { finishCommand(ctx, [success: false, errorCode: "NO_REQUEST_ID"]); return }
    Map pollCtx = ctx + [reqId: reqId, deadline: now() + 45000, intervalMs: 2000]
    runInMillis(pollCtx.intervalMs as Long, "pollCommand", [data: pollCtx, overwrite: false])
}

def pollCommand(Map ctx) {
    if (now() > (ctx.deadline as Long)) { finishCommand(ctx, [success: false, errorCode: "TIMEOUT"]); return }
    Map params = [uri: apiBase() + (ctx.pollPath as String), headers: httpHeaders(), query: [serviceRequestId: ctx.reqId], timeout: 25]
    asynchttpGet("onPollResult", params, ctx)
}

def onPollResult(resp, Map ctx) {
    Map result = parseAsyncJson(resp)
    if (result == null) { finishCommand(ctx, [success: false, errorCode: "HTTP_${resp?.status ?: 'ERROR'}"]); return }
    if (result.errorCode) {
        if (result.errorCode in ["403-soa-unableToParseResponseBody", "InvalidToken"]) {
            state.cookies = null
            state.cookieJar = [:]
            ensureSession(ctx.vin as String)
            runInMillis(1000, "pollCommand", [data: ctx + [intervalMs: 1000], overwrite: false])
            return
        }
        finishCommand(ctx, [success: false, errorCode: result.errorCode])
        return
    }
    Map data = result.data as Map
    if (data?.remoteServiceState == "finished") { finishCommand(ctx, data); return }
    Integer nextInterval = Math.min(((ctx.intervalMs as Integer) * 1.5) as int, 15000)
    runInMillis(nextInterval as Long, "pollCommand", [data: ctx + [intervalMs: nextInterval], overwrite: false])
}

private void finishCommand(Map ctx, Map result) {
    def childDevice = getChildDevice(ctx.dni as String)
    if (!childDevice) return
    childDevice.sendEvent(name: "lastCommandResult", value: result?.success ? "success" : "failed: ${result?.errorCode}")
    switch (ctx.kind) {
        case "LOCK":
            if (result?.success) childDevice.sendEvent(name: "lock", value: "locked")
            break
        case "UNLOCK":
            if (result?.success) childDevice.sendEvent(name: "lock", value: "unlocked")
            break
        case "LOCATE":
            if (result?.success && result?.result) updateLocationAttributes(childDevice, result.result as Map)
            break
        case "UPDATE":
            if (result?.success && result?.result) updateLocationAttributes(childDevice, result.result as Map)
            // Car has now reported fresh telematics; pull the refreshed cached data after a short
            // settle delay (odometer / tire pressures / vehicle state).
            if (result?.success) runInMillis(3000, "refreshAfterActiveUpdate", [data: [dni: ctx.dni], overwrite: false])
            break
    }
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
        // Names only - never log cookie values, they're live session credentials.
        logTrace "Cookie jar now has ${jar.size()} cookie(s): ${jar.keySet()}"
    } catch (Exception e) {
        logDebug "captureCookies error: ${e.message}"
    }
}

// Async counterpart of captureCookies(). Hubitat's AsyncResponse may expose headers as either
// a Map (single value per name) or an iterable of {name, value} pairs depending on platform
// version - handled defensively here since it can't be verified without a live hub.
private void captureCookiesFromAsync(resp) {
    try {
        def headers = resp?.headers
        if (!headers) return
        List rawCookies = []
        if (headers instanceof Map) {
            headers.each { k, v -> if (k?.toString()?.equalsIgnoreCase("Set-Cookie")) rawCookies << v?.toString() }
        } else {
            headers.each { h ->
                try {
                    if (h?.name?.toString()?.equalsIgnoreCase("Set-Cookie")) rawCookies << h.value?.toString()
                } catch (ignored) { }
            }
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
        logTrace "Cookie jar now has ${jar.size()} cookie(s): ${jar.keySet()}"
    } catch (Exception e) {
        logDebug "captureCookiesFromAsync error: ${e.message}"
    }
}

// Note: unlike the sync helpers, this doesn't auto-retry on a 404 API-version bump - the next
// command still goes through ensureSession()'s synchronous validateSession call first, which
// already carries that retry logic, so the version self-corrects before the async path is hit again.
private Map parseAsyncJson(resp) {
    try {
        if (resp == null || resp.hasError() || (resp.status ?: 0) != 200) {
            logWarn "Async request failed: status=${resp?.status} error=${resp?.errorMessage}"
            return null
        }
        captureCookiesFromAsync(resp)
        def json = resp.getJson()
        return (json instanceof Map) ? json as Map : null
    } catch (Exception e) {
        logWarn "Failed to parse async response: ${e.message}"
        return null
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

private void logTxt(String msg) {
    if (settings.txtEnable) log.info msg
}

private void logDebug(String msg) {
    if (settings.debugEnable) log.debug msg
}

private void logTrace(String msg) {
    if (settings.traceEnable) log.trace msg
}

private void logWarn(String msg) {
    log.warn msg
}
