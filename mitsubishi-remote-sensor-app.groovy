/**
 * Hubitat App
 * Mitsubishi Heat Pump Remote Sensor
 * v0.2
 * https://github.com/randalln/hubitat-mitsubishi-mqtt-remote-app
 *
 * MIT License
 *
 * Copyright (c) 2025 Randall Norviel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
        )

preferences {
    page(name: "mainPage", install: true, uninstall: true) {
        section {
            label()
            input "thermostat", "device.MitsubishiHeatPumpMQTT", title: "Heat Pump", required: true
            input "sensors", "capability.temperatureMeasurement", title: "Sensors", required: true, multiple: true
            input name: "avoidImmediateCycle", type: "bool", title: "Adjust setpoint to avoid an immediate cycle when turned on?"
            input name: "timeout", type: "Sensor timeout", title: "Sensor timeout (minutes) before switching to internal heat pump sensor"
            input name: "canTurnOff", type: "bool", title: "Turn off if too far past setpoint? (<= ${avoidImmediateCycleDegrees})"
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
    logDebug "updated(): $app.label"
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
    log.info "Sensor timeout: Setting to internal sensor"
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
                log.info "Turning off heat pump"
                if (thermostat.currentValue("thermostatOperatingState") != "idle") {
                    log.warn "Heat pump currently operating"
                }
                state.previousThermostatMode = thermostatMode
                thermostat.off()
            }
        } else if (thermostatMode == "off") {
            if (state.previousThermostatMode == "heat") {
                log.info "Setting thermostatMode back to heat"
                thermostat.heat()
            } else if (state.previousThermostatMode == "cool") {
                log.info "Setting thermostatMode back to cool"
                thermostat.cool()
            }
        }
    }
}

boolean tooFarPastSetpoint(String thermostatMode) {
    boolean ret = false
    def thermostatSetpoint = thermostat.currentValue("thermostatSetpoint")
    def currentTemp = averageTemperature()
    def delta = offDelta > avoidImmediateCycleDegrees ? avoidImmediateCycleDegrees : offDelta

    if (thermostatMode == "heat" || state.previousThermostatMode == "heat") {
        ret = currentTemp > thermostatSetpoint + delta
    } else if (thermostatMode == "cool" || state.previousThermostatMode == "cool") {
        ret = currentTemp < thermostatSetpoint - delta
    }

    logDebug "tooFarPastSetpoint: ${ret}"
    return ret
}

void thermostatModeHandler(evt) {
    logDebug "thermostatModeHandler(): ${evt.name} ${evt.value}"
    if (thermostat.currentValue("thermostatMode") != "off") {
        if (state.previousThermostatMode) { // HP was turned off by this app
            logDebug "Clearing previousThermostatMode"
            state.previousThermostatMode = null
        } else if (avoidImmediateCycle) {
            logDebug "Adjusting setpoint to avoid immediate cycle"
            // When the HP is turned on outside this app, set the setpoint low enough to not trigger an immediate cycle
            String thermostatMode = thermostat.currentValue("thermostatMode")
            def thermostatTemp = thermostat.currentValue("temperature")
            if (thermostatMode == "heat") {
                thermostat.setHeatingSetpoint(thermostatTemp - avoidImmediateCycleDegrees)
            } else if (thermostatMode == "cool") {
                thermostat.setHeatingSetpoint(thermostatTemp + avoidImmediateCycleDegrees)
            }
        }
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
        log.debug msg
    }
}
