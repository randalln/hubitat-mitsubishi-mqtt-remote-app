// Copyright (c) 2025 Randall Norviel
// SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>
//
// SPDX-License-Identifier: MIT

/**
 * Mitsubishi Heat Pump Remote Sensor
 * v0.2.9
 * https://github.com/randalln/hubitat-mitsubishi-mqtt-remote-app
 *
 * Changelog:
 * v0.2.9 Convert to gradle project
 * v0.2.8 Set temperature at idle
 * v0.2.7 Bug fix
 * v0.2.6 Bug fix
 * v0.2.5 Cancel setpoint restoration if user updates it
 * v0.2.4 Logging and documentation
 * v0.2.3 Fix avoidImmediateCycle
 * v0.2.2 Bug fix
 * v0.2.1 Disabling avoidImmediateCycle for now
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
            input name: "avoidImmediateCycle", type: "bool", title: "Adjust setpoint to avoid an immediate cycle when turned on?"
            input name: "timeout", type: "Sensor timeout",
                  title: "Sensor timeout (minutes while operating) before switching to internal heat pump sensor"
            input name: "canTurnOff", type: "bool", title: "Turn off if too far past setpoint?"
            input name: "offDelta", type: "decimal", title: "Degrees past setpoint", range: "1.1..10", width: 4
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

    scheduleSensorCheck()
}

void sensorHandler(evt) {
    logDebug "sensorHandler(): ${evt.name} ${evt.value}"
    scheduleSensorCheck() // If operating, reschedule an existing timeout from now
    thermostat.setRemoteTemperature(averageTemperature())
    toggleThermostatModeAsNeeded()
}

void scheduleSensorCheck() {
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
    logDebug "thermostatTempHandler(): ${evt.name} ${evt.value} ${state.restoringSetpointCounter}"
    if (state.restoringSetpointCounter == 1) { // Ignore the app changing the setpoint
        state.restoringSetpointCounter++
    } else if (state.restoringSetpointCounter >= 2) { // The user changed the setpoint
        clearRestoredSetpoint()
    }
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
    if (!state.restoringSetpointCounter) { // Skip while waiting to restore setpoint
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

        if (avoidImmediateCycle) {
            // Set the setpoint low enough to not trigger an immediate cycle
            def thermostatSetpoint = thermostat.currentValue("thermostatSetpoint")
            if (thermostatMode == "heat") {
                def tempSetpoint = averageTemperature() - avoidImmediateCycleDegrees
                if (tempSetpoint < thermostatSetpoint) {
                    saveSetpoint(thermostatSetpoint)
                    setSetpoint(tempSetpoint)
                }
            } else if (thermostatMode == "cool") {
                def tempSetpoint = averageTemperature() + avoidImmediateCycleDegrees
                if (tempSetpoint > thermostatSetpoint) {
                    saveSetpoint(thermostatSetpoint)
                    setSetpoint(tempSetpoint)
                }
            }
        }
    } else {
        unschedule("checkSensorActivity")
        clearRestoredSetpoint()
    }
}

void saveSetpoint(def setpoint) {
    logDebug "Saving setpoint to restore: ${setpoint}"
    state.restoringSetpointCounter = 1
    state.restoredSetpoint = setpoint
    runIn(60, "restoreSetpoint")
}

void restoreSetpoint() {
    if (state.restoredSetpoint) {
        logDebug "Restoring setpoint ${state.restoredSetpoint}"
        setSetpoint(state.restoredSetpoint)
        clearRestoredSetpoint()
    }
}

void clearRestoredSetpoint() {
    if (state.restoringSetpointCounter > 0 || state.restoredSetpoint) {
        logDebug("Clearing saved setpoint")
        state.restoringSetpointCounter = 0
        state.restoredSetpoint = null
    }
}

void setSetpoint(def setpoint) {
    String thermostatMode = thermostat.currentValue("thermostatMode")

    if (thermostatMode == "heat") {
        thermostat.setHeatingSetpoint(setpoint)
    } else if (thermostatMode == "cool") {
        thermostat.setCoolingSetpoint(setpoint)
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
