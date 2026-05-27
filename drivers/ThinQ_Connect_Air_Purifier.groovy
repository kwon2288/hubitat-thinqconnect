/**
 *  ThinQ Connect Air Purifier
 *  jonozzz hubitat-thinqconnect 프레임워크 기반 (thinq_connect_core.groovy 연동)
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Air Purifier", namespace: "jonozzz", author: "Custom") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"   // ★ master 장치 판별에 필수 (없으면 MQTT 연결 안 됨)
        capability "Refresh"
        capability "FanControl"

        attribute "currentState",        "string"
        attribute "airPurifierMode",     "string"
        attribute "airFlowSpeed",        "string"
        attribute "pm1",                 "number"
        attribute "pm25",                "number"
        attribute "pm10",                "number"
        attribute "pm1Level",            "string"
        attribute "pm25Level",           "string"
        attribute "pm10Level",           "string"
        attribute "filterRemainPercent", "number"   // ★ 기존에 누락된 attribute
    }

    preferences {
        input name: "logLevel",     title: "Log Level",            type: "enum",  options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
        input name: "logDescText",  title: "Log Description Text", type: "bool",  defaultValue: false,  required: false
    }
}

// ── 라이프사이클 ─────────────────────────────────────────────────────────────

def installed() {
    logger("debug", "installed()")
    initialize()
}

def updated() {
    logger("debug", "updated()")
    initialize()
}

def uninstalled() {
    logger("debug", "uninstalled()")
}

def initialize() {
    logger("debug", "initialize()")

    // master 장치만 MQTT 연결 (core 앱이 첫 번째 장치에 master=true 설정)
    if (getDataValue("master") == "true") {
        if (interfaces.mqtt.isConnected())
            interfaces.mqtt.disconnect()
        mqttConnectUntilSuccessful()
    }

    refresh()
}

// ── MQTT (★ 기존 코드에 완전히 누락 → 상태가 로그에만 보이고 UI에 안 보이던 원인) ──

def mqttConnectUntilSuccessful() {
    logger("debug", "mqttConnectUntilSuccessful()")
    try {
        def mqtt = parent.retrieveMqttDetails()
        interfaces.mqtt.connect(
            mqtt.server,
            mqtt.clientId,
            null, null,
            tlsVersion: "1.2",
            privateKey: mqtt.privateKey,
            caCertificate: mqtt.caCertificate,
            clientCertificate: mqtt.certificate,
            cleanSession: true,
            ignoreSSLIssues: true
        )
        pauseExecution(3000)
        for (sub in mqtt.subscriptions) {
            interfaces.mqtt.subscribe(sub)
        }
        return true
    } catch (e) {
        logger("warn", "Lost connection to MQTT, retrying in 15 seconds ${e}")
        runIn(15, "mqttConnectUntilSuccessful")
        return false
    }
}

def parse(message) {
    def topic = interfaces.mqtt.parseMessage(message)
    def payload = new JsonSlurper().parseText(topic.payload)
    logger("trace", "parse(${payload})")
    parent.processMqttMessage(this, payload)
}

def mqttClientStatus(String message) {
    logger("debug", "mqttClientStatus(${message})")
    if (message.startsWith("Error:")) {
        logger("error", "MQTT Error: ${message}")
        try { interfaces.mqtt.disconnect() } catch (e) {}
        mqttConnectUntilSuccessful()
    }
}

// ── Refresh ───────────────────────────────────────────────────────────────────

def refresh() {
    logger("debug", "refresh()")
    def status = parent.getDeviceState(getDeviceId())
    processStateData(status)
}

// ── Switch ────────────────────────────────────────────────────────────────────

def on() {
    logger("debug", "on()")
    // ★ parent.sendCommand() → parent.sendDeviceCommand() 로 수정
    // ★ 페이로드 구조를 ThinQ Connect API 실제 스펙에 맞게 수정
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ airPurifierOperationMode: "POWER_ON" ]
    ])
    sendEvent(name: "switch", value: "on")
}

def off() {
    logger("debug", "off()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ airPurifierOperationMode: "POWER_OFF" ]
    ])
    sendEvent(name: "switch", value: "off")
}

// ── FanControl ────────────────────────────────────────────────────────────────

def setSpeed(String speed) {
    logger("debug", "setSpeed(${speed})")

    // Hubitat FanControl 표준값 → LG API 값 양방향 매핑
    def speedMap = [
        "low"         : "LOW",
        "medium-low"  : "LOW_MID",
        "medium"      : "MID",
        "medium-high" : "HIGH_MID",
        "high"        : "HIGH",
        "auto"        : "AUTO",
        "on"          : "AUTO"
    ]
    def apiSpeed = speedMap[speed?.toLowerCase()] ?: speed?.toUpperCase()

    parent.sendDeviceCommand(getDeviceId(), [
        airFlow: [ windStrength: apiSpeed ]
    ])
    sendEvent(name: "speed", value: speed)
}

// ── 상태 파싱 (core 앱 → processStateData(status) 로 호출됨) ─────────────────

def processStateData(data) {
    logger("debug", "processStateData(${data})")

    if (!data) return
    // API 응답이 List로 올 수 있음 (에어컨 드라이버와 동일한 처리)
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    // 1. runState (현재 동작 상태)
    if (data.runState?.currentState) {
        sendEvent(name: "currentState", value: data.runState.currentState)
    }

    // 2. 전원 상태 파싱
    def opMode = data.operation?.airPurifierOperationMode
    if (opMode != null) {
        def isOn = (opMode == "POWER_ON")
        sendEvent(name: "switch", value: isOn ? "on" : "off")
        if (logDescText) log.info "${device.displayName} switch → ${isOn ? 'on' : 'off'}"
    } else if (data.airPurifierJobMode?.currentJobMode != null) {
        // 구형 응답 구조 호환
        def isOn = (data.airPurifierJobMode.currentJobMode != "POWER_OFF")
        sendEvent(name: "switch", value: isOn ? "on" : "off")
    }

    // 3. 공기청정 모드
    if (data.airPurifierJobMode?.currentJobMode != null) {
        sendEvent(name: "airPurifierMode", value: cleanEnumValue(data.airPurifierJobMode.currentJobMode))
    }

    // 4. 팬 속도
    if (data.airFlow?.windStrength != null) {
        def windStrength = data.airFlow.windStrength
        // LG API 값 → Hubitat FanControl 표준값 역매핑
        def hubitatSpeed = [
            "LOW"      : "low",
            "LOW_MID"  : "medium-low",
            "MID"      : "medium",
            "HIGH_MID" : "medium-high",
            "HIGH"     : "high",
            "AUTO"     : "auto"
        ][windStrength] ?: windStrength.toLowerCase()

        sendEvent(name: "speed",        value: hubitatSpeed)
        sendEvent(name: "airFlowSpeed", value: windStrength)
        if (logDescText) log.info "${device.displayName} fanSpeed → ${windStrength}"
    }

    // 5. 공기질 센서 (로그 구조 반영: PM1, PM2, PM10, *Level)
    if (data.airQualitySensor != null) {
        def s = data.airQualitySensor
        if (s.PM1      != null) sendEvent(name: "pm1",      value: s.PM1)
        if (s.PM2      != null) sendEvent(name: "pm25",     value: s.PM2)    // PM2 = PM2.5
        if (s.PM10     != null) sendEvent(name: "pm10",     value: s.PM10)
        if (s.PM1Level != null) sendEvent(name: "pm1Level", value: cleanEnumValue(s.PM1Level))
        if (s.PM2Level != null) sendEvent(name: "pm25Level",value: cleanEnumValue(s.PM2Level))
        if (s.PM10Level!= null) sendEvent(name: "pm10Level",value: cleanEnumValue(s.PM10Level))
    }

    // 6. 필터 잔여량 (filterInfo 및 구형 filter 키 둘 다 처리)
    def filterPct = data.filterInfo?.filterRemainPercent ?: data.filter?.filterRemainPercent
    if (filterPct != null) {
        sendEvent(name: "filterRemainPercent", value: filterPct, unit: "%")
    }
}

// ── 헬퍼 ──────────────────────────────────────────────────────────────────────

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

def cleanEnumValue(value) {
    if (value == null) return ""
    return value.toString()
        .replaceAll(/_/, " ")
        .toLowerCase()
        .split(' ')
        .collect { it.capitalize() }
        .join(' ')
}

private logger(level, msg) {
    if (level && msg) {
        Integer levelIdx    = LOG_LEVELS.indexOf(level)
        Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
        if (setLevelIdx < 0) setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
        if (levelIdx <= setLevelIdx) {
            log."${level}" "${device.displayName} ${msg}"
        }
    }
}