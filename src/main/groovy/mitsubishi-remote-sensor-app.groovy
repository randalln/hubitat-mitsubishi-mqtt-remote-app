// Copyright (c) 2025 Randall Norviel
// SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>
//
// SPDX-License-Identifier: MIT

/**
 * Mitsubishi Heat Pump Remote Sensor
 * https://github.com/randalln/hubitat-mitsubishi-mqtt-remote-app
 */

import groovy.transform.Field

@Field static def avoidImmediateCycleDegrees = 2.0

definition(
        name: "Mitsubishi Remote Sensor with Heat Pump",
        namespace: "randalln",
        author: "Randall Norviel",
        description: "Use a remote temperature sensor with a Mitsubishi heat pump",
        category: "",
        iconUrl: "",
        iconX2Url: "",
        parent: "randalln:Mitsubishi Remote Sensor Manager",
        importUrl: "https://raw.githubusercontent.com/randalln/hubitat-mitsubishi-mqtt-remote-app/main/src/main/groovy/" +
                "mitsubishi-remote-sensor-app.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            label()
            input "thermostat", "device.MitsubishiHeatPumpMQTT", title: "Heat Pump", required: true
            input "sensors", "capability.temperatureMeasurement", title: "Sensors", required: true, multiple: true
            input name: "timeout", type: "number",
                  title: "Sensor timeout (minutes while operating) before switching to internal heat pump sensor"
            input name: "canTurnOff", type: "bool", title: "Turn off if too far past setpoint?", width: 4, submitOnChange: true
            if (canTurnOff) {
                input name: "offDeltaByVariable", type: "bool", title: "Use variable", submitOnChange: true, width: 4
                if (!offDeltaByVariable) {
                    input name: "offDelta", type: "decimal", title: "Degrees past setpoint", range: "1.1..10"
                } else {
                    List vars = []
                    input "offDeltaVariable", "enum", options: getGlobalVarsByType("bigdecimal").keySet().collect().sort { it.capitalize() }
                }
            }
            input name: "logEnable", type: "bool", title: "Enable logging?"
        }
    }
}

void installed() {
    updated() // since installed() rather than updated() will run the first time the user selects "Done"
}

void uninstalled() {
    log.trace "uninstalled()"
    useInternalSensor()
}

private void useInternalSensor() {
    if (thermostat) {
        thermostat.setRemoteTemperature(0)
    }
}

void updated() {
    logDebug "updated()"
    removeAllInUseGlobalVar()
    unschedule()
    unsubscribe()

    subscribeToOffDeltaVariable()
    subscribe(sensors, "temperature", sensorHandler)
    // To turn HP on or off
    subscribe(thermostat, "thermostatSetpoint", thermostatTempHandler)
    subscribe(thermostat, "temperature", thermostatTempHandler)
    // To clear app state
    subscribe(thermostat, "thermostatMode", thermostatModeHandler)
    // To check for remote sensor timeout
    subscribe(thermostat, "thermostatOperatingState", thermostatOperatingStateHandler)

    thermostat.setRemoteTemperature(averageTemperature())

    scheduleSensorCheck()
}

private void subscribeToOffDeltaVariable() {
    if (canTurnOff && offDeltaByVariable && offDeltaVariable) {
        offDelta = getGlobalVar(offDeltaVariable).value
        logDebug "offDelta: ${offDelta}"
        addInUseGlobalVar(offDeltaVariable)
        subscribe(location, "variable:${offDeltaVariable}", "offDeltaVariableHandler")
    }
}

void offDeltaVariableHandler(evt) {
    offDelta = evt.value
    logDebug "offDelta: ${offDelta}"
}

void renameVariable(String oldName, String newName) {
    logDebug "${oldName} renamed to ${newName}"
    removeInUseGlobalVar(oldName)
    offDeltaVariable = newName
    subscribeToOffDeltaVariable()
}

void sensorHandler(evt) {
    logDebug "sensorHandler(): ${evt.name} ${evt.value}"
    try { // Protect (as best one can) against Groovy
        scheduleSensorCheck() // If operating, reschedule an existing timeout from now
    } catch (Exception e) {
        sensorTimeout()
        throw e
    }
    thermostat.setRemoteTemperature(averageTemperature())
    toggleThermostatModeAsNeeded()
}

private void scheduleSensorCheck() {
    unschedule("sensorTimeout")
    if (timeout) {
        String thermostatOperatingState = thermostat.currentValue("thermostatOperatingState")
        if (thermostatOperatingState == "heating" || thermostatOperatingState == "cooling") {
            logDebug("Scheduling sensorTimeout in ${timeout} minutes")
            runIn(timeout * 60, "sensorTimeout")
        }
    }
}

/**
 * If the callback is not unscheduled, set the internal HP sensor active
 */
void sensorTimeout() {
    logInfo "Sensor timeout: Setting to internal sensor"
    useInternalSensor()
}

void thermostatTempHandler(evt) {
    logDebug "thermostatTempHandler(): ${evt.name} ${evt.value}"
    toggleThermostatModeAsNeeded()
}

void toggleThermostatModeAsNeeded() {
    String thermostatMode = thermostat.currentValue("thermostatMode")

    if (canTurnOff && offDelta > 0) {
        if (tooFarPastSetpoint(thermostatMode)) {
            if (thermostatMode != "off") {
                logInfo "Turning off heat pump"
                if (thermostat.currentValue("thermostatOperatingState") != "idle") {
                    log.warn "Heat pump currently operating"
                }
                state.previousThermostatMode = thermostatMode
                thermostat.off()
            }
        } else if (thermostatMode == "off") {
            if (state.previousThermostatMode == "heat") {
                logInfo "Setting thermostatMode back to heat"
                thermostat.heat()
            } else if (state.previousThermostatMode == "cool") {
                logInfo "Setting thermostatMode back to cool"
                thermostat.cool()
            }
        }
    }
}

boolean tooFarPastSetpoint(String thermostatMode) {
    boolean ret = false
    def thermostatSetpoint = thermostat.currentValue("thermostatSetpoint")
    def currentTemp = averageTemperature()

    if (thermostatMode == "heat") {
        ret = currentTemp > thermostatSetpoint + offDelta
        if (ret) {
            logDebug "${currentTemp} > ${thermostatSetpoint} + ${offDelta}"
        }
    } else if (state.previousThermostatMode == "heat") {
        ret = currentTemp > thermostatSetpoint + offDelta - 0.5
    } else if (thermostatMode == "cool") {
        ret = currentTemp < thermostatSetpoint - offDelta
        if (ret) {
            logDebug "${currentTemp} < ${thermostatSetpoint} - ${offDelta}"
        }
    } else if (state.previousThermostatMode == "cool") {
        ret = currentTemp < thermostatSetpoint - offDelta + 0.5
    }

    return ret
}

void thermostatModeHandler(evt) {
    logDebug "thermostatModeHandler(): ${evt.name} ${evt.value}"
    String thermostatMode = thermostat.currentValue("thermostatMode")
    if (thermostatMode != "off") {
        if (state.previousThermostatMode) { // HP was turned off by this app
            logDebug "Clearing previousThermostatMode"
            state.previousThermostatMode = null
        }
    } else {
        unschedule("sensorTimeout")
    }
}

void thermostatOperatingStateHandler(evt) {
    logDebug "thermostatOperatingStateHandler(): ${evt.name} ${evt.value}"
    scheduleSensorCheck()
    if (evt.value == "idle") {
        logDebug "Setting remote temp at idle: ${averageTemperature()}"
        thermostat.setRemoteTemperature(averageTemperature())
    }
}

private def averageTemperature() {
    def total = 0
    def count = 0

    for (sensor in sensors) {
        total += sensor.currentValue("temperature")
        count++
    }

    return total / count
}

private void logDebug(String msg) {
    if (logEnable) {
        log.debug "${app.label}: ${msg}"
    }
}

private void logInfo(String msg) {
    log.info "${app.label}: ${msg}"
}
