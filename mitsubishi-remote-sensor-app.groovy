// Copyright (c) 2025 Randall Norviel
// SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>
//
// SPDX-License-Identifier: MIT

/**
 * Mitsubishi Heat Pump Remote Sensor
 * v0.2.4
 * https://github.com/randalln/hubitat-mitsubishi-mqtt-remote-app
 *
 * Changelog:
 * v0.2.1 Disabling avoidImmediateCycle for now
 * v0.2.2 Bug fix
 * v0.2.3 Fix avoidImmediateCycle
 * v0.2.4 Logging and documentation
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
        importUrl: "https://raw.githubusercontent.com/randalln/hubitat-mitsubishi-mqtt-remote-app/main/mitsubishi-remote-sensor-app.groovy"
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
            input name: "avoidImmediateCycle", type: "bool", title: "Adjust setpoint to avoid an immediate cycle when turned on?",
                  submitOnChange: true
            input name: "timeout", type: "Sensor timeout",
                  title: "Sensor timeout (minutes while operating) before switching to internal heat pump sensor"
            input name: "canTurnOff", type: "bool", title: "Turn off if too far past setpoint?", submitOnChange: true
            input name: "offDelta", type: "decimal", title: "Degrees past setpoint", width: 4
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

void useInternalSensor() {
    if (thermostat) {
        thermostat.setRemoteTemperature(0)
    }
}

void updated() {
    logDebug "updated()"
    unschedule()
    unsubscribe()

    subscribe(sensors, "temperature", sensorHandler)
    // To turn HP on or off
    subscribe(thermostat, "thermostatSetpoint", thermostatTempHandler)
    subscribe(thermostat, "temperature", thermostatTempHandler)
    // To clear app state
    subscribe(thermostat, "thermostatMode", thermostatModeHandler)
    // To check for remote sensor timeout
    subscribe(thermostat, "thermostatOperatingState", thermostatOperatingStateHandler)

    thermostat.setRemoteTemperature(averageTemperature())
}

void sensorHandler(evt) {
    logDebug "sensorHandler(): ${evt.name} ${evt.value}"
    scheduleSensorCheck() // If operating, reschedule an existing timeout from now
    thermostat.setRemoteTemperature(averageTemperature())
    toggleThermostatModeAsNeeded()
}

void scheduleSensorCheck() {
    logDebug("scheduleSensorCheck()")
    unschedule("checkSensorActivity")
    if (timeout) {
        String thermostatOperatingState = thermostat.currentValue("thermostatOperatingState")
        if (thermostatOperatingState == "heating" || thermostatOperatingState == "cooling") {
            logDebug("Scheduling checkSensorActivity in ${timeout} minutes")
            runIn(Long.parseLong(timeout) * 60, "checkSensorActivity")
        }
    }
}

/**
 * If the callback is not unscheduled, set the internal HP sensor active
 */
void checkSensorActivity() {
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
    if (!state.restoredSetpoint) { // Skip while waiting to restore setpoint
        def thermostatSetpoint = thermostat.currentValue("thermostatSetpoint")
        def currentTemp = averageTemperature()

        if (thermostatMode == "heat" || state.previousThermostatMode == "heat") {
            ret = currentTemp > thermostatSetpoint + offDelta
            if (ret) {
                logDebug "${currentTemp} > ${thermostatSetpoint} + ${offDelta}"
            }
        } else if (thermostatMode == "cool" || state.previousThermostatMode == "cool") {
            ret = currentTemp < thermostatSetpoint - offDelta
            if (ret) {
                logDebug "${currentTemp} < ${thermostatSetpoint} - ${offDelta}"
            }
        }
    }

    return ret
}

void thermostatModeHandler(evt) {
    logDebug "thermostatModeHandler(): ${evt.name} ${evt.value}"
    if (thermostat.currentValue("thermostatMode") != "off") {
        if (state.previousThermostatMode) { // HP was turned off by this app
            logDebug "Clearing previousThermostatMode"
            state.previousThermostatMode = null
        }

        if (avoidImmediateCycle) {
            // Set the setpoint low enough to not trigger an immediate cycle
            String thermostatMode = thermostat.currentValue("thermostatMode")
            if (thermostatMode == "heat") {
                logDebug "Saving setpoint to restore: ${thermostat.currentValue("thermostatSetpoint")}"
                state.restoredSetpoint = thermostat.currentValue("thermostatSetpoint")
                thermostat.setHeatingSetpoint(averageTemperature() - avoidImmediateCycleDegrees)
                runIn(60, "restoreSetpoint")
            } else if (thermostatMode == "cool") {
                logDebug "Saving setpoint to restore: ${thermostat.currentValue("thermostatSetpoint")}"
                state.restoredSetpoint = thermostat.currentValue("thermostatSetpoint")
                thermostat.setCoolingSetpoint(averageTemperature() + avoidImmediateCycleDegrees)
                runIn(30, "restoreSetpoint")
            }
        }
    }
}

void restoreSetpoint() {
    if (state.restoredSetpoint) {
        logDebug "Restoring setpoint ${state.restoredSetpoint}"
        thermostat.setHeatingSetpoint(state.restoredSetpoint)
        state.restoredSetpoint = null
    }
}

void thermostatOperatingStateHandler(evt) {
    logDebug "thermostatOperatingStateHandler(): ${evt.name} ${evt.value}"
    scheduleSensorCheck()
}

def averageTemperature() {
    def total = 0
    def count = 0

    for (sensor in sensors) {
        total += sensor.currentValue("temperature")
        count++
    }

    return total / count
}

void logDebug(String msg) {
    if (logEnable) {
        log.debug "${app.label}: ${msg}"
    }
}

void logInfo(String msg) {
    log.info "${app.label}: ${msg}"
}
