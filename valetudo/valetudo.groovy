def DRIVER_VERSION = "0.0.7"

metadata {
    definition(name: "Valetudo REST Robot", namespace: "custom", author: "Stephen") {
        capability "Polling"
        capability "Refresh"
        capability "Battery"
        capability "Switch"

        attribute "robotStatus", "string"
        attribute "robotError", "string"
        attribute "dockStatus", "string"
        attribute "operationMode", "string"
        attribute "fanSpeed", "string"
        attribute "waterGrade", "string"
        attribute "mopAttached", "boolean"
        attribute "version", "string"

        command "home"
        command "start"
        command "stop"
        command "pause"
        command "setOperationMode", [[name: "Operation Mode*", type: "ENUM", constraints: state.operationModeOptions ?: ["vacuum_and_mop", "mop", "vacuum", "vacuum_then_mop"]]]
        command "setFanSpeed", [[name: "Fan Speed*", type: "ENUM", constraints: state.fanSpeedPresets ?: ["low", "medium", "high", "max"]]]
    }

    preferences {
        input name: "robotIP", type: "string", title: "Robot IP Address", required: true
    }
}

def installed() {
    log.info "Installed"
    initialize()
}

def updated() {
    log.info "Updated"
    unschedule()
    initialize()
}

def initialize() {
    log.info "Driver version: ${DRIVER_VERSION}"
    sendEvent(name: "version", value: DRIVER_VERSION)

    schedule("0 0/15 * * * ?", poll) // every 15 minutes
    poll()
    fetchSupportedPresets()
}

def fetchSupportedPresets() {
    fetchFanPresets()
    fetchOperationModePresets()
}

def fetchFanPresets() {
    def url = "http://${robotIP}/api/v2/robot/capabilities/FanSpeedControlCapability/presets"
    httpGet(url) { resp ->
        if (resp.status == 200 && resp.data instanceof List) {
            state.fanSpeedPresets = resp.data
            device.updateSetting("fanSpeedPresets", [type: "enum", name: "Fan Speed", constraints: resp.data])
        } else {
            log.warn "Could not fetch fan presets"
        }
    }
}

def fetchOperationModePresets() {
    def url = "http://${robotIP}/api/v2/robot/capabilities/OperationModeControlCapability/presets"
    httpGet(url) { resp ->
        if (resp.status == 200 && resp.data instanceof List) {
            state.operationModeOptions = resp.data
        } else {
            log.warn "Could not fetch operation mode presets"
        }
    }
}

def poll() {
    refresh()
}

def refresh() {
    def url = "http://${robotIP}/api/v2/robot/state"
    httpGet(url) { resp ->
        if (resp.status == 200) {
            def attrs = resp.data.attributes
            def getAttr = { cls, filter = [:] ->
                attrs.find { it.__class == cls && filter.every { k, v -> it[k] == v } }
            }

            def battery = getAttr("BatteryStateAttribute")?.level ?: 0
            def dock = getAttr("DockStatusStateAttribute")?.value ?: "unknown"
            def status = getAttr("StatusStateAttribute")?.value ?: "unknown"
            def error = getAttr("StatusStateAttribute")?.flag ?: "none"
            def mode = getAttr("PresetSelectionStateAttribute", [type: "operation_mode"])?.value ?: "unknown"
            def fan = getAttr("PresetSelectionStateAttribute", [type: "fan_speed"])?.value ?: "unknown"
            def water = getAttr("PresetSelectionStateAttribute", [type: "water_grade"])?.value ?: "unknown"
            def mop = getAttr("AttachmentStateAttribute", [type: "mop"])?.attached ?: false

            sendEvent(name: "battery", value: battery)
            sendEvent(name: "dockStatus", value: dock)
            sendEvent(name: "robotStatus", value: status)
            sendEvent(name: "robotError", value: error)
            sendEvent(name: "operationMode", value: mode)
            sendEvent(name: "fanSpeed", value: fan)
            sendEvent(name: "waterGrade", value: water)
            sendEvent(name: "mopAttached", value: mop)

            state.lastFanSpeed = fan
            state.lastOperationMode = mode
        } else {
            log.warn "Failed to fetch state: HTTP ${resp.status}"
        }
    }
}

def home() {
    def url = "http://${robotIP}/api/v2/robot/capabilities/BasicControlCapability"
    def body = [action: "home"]
    sendPut(url, body)
}

def start() {
    def url = "http://${robotIP}/api/v2/robot/capabilities/BasicControlCapability"
    def body = [action: "start"]
    sendPut(url, body)
}

def stop() {
    def url = "http://${robotIP}/api/v2/robot/capabilities/BasicControlCapability"
    def body = [action: "stop"]
    sendPut(url, body)
}

def pause() {
    def url = "http://${robotIP}/api/v2/robot/capabilities/BasicControlCapability"
    def body = [action: "pause"]
    sendPut(url, body)
}

def setFanSpeed(String speed) {
    def url = "http://${robotIP}/api/v2/robot/capabilities/FanSpeedControlCapability/preset"
    def body = [name: speed]
    state.lastFanSpeed = speed
    sendPut(url, body)
    refresh()
}

def setOperationMode(String mode) {
    def url = "http://${robotIP}/api/v2/robot/capabilities/OperationModeControlCapability/preset"
    def body = [name: mode]
    state.lastOperationMode = mode
    sendPut(url, body)
    refresh()
}

private sendPut(String url, Map body) {
    try {
        httpPut([
            uri: url,
            requestContentType: 'application/json',
            body: body
        ]) { resp ->
            if (resp.status in 200..299) {
                log.debug "PUT succeeded to $url"
            } else {
                log.warn "PUT failed to $url: HTTP ${resp.status}"
            }
        }
    } catch (e) {
        log.error "PUT exception: ${e.message}"
    }
}