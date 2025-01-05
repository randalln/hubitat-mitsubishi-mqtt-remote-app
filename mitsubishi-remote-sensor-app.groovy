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
            input "sensors", "capability.temperatureMeasurement", title: "Sensors", required: true, multiple: true
            input "thermostat", "device.MitsubishiHeatPumpMQTT", title: "Heat Pump", required: true
            input name: "timeout", type: "Sensor timeout", title: "Timeout (minutes)", width: 4
            input name: "canTurnOff", type: "bool", title: "Turn off if too far past setpoint?"
            input name: "offDelta", type: "decimal", title: "Degrees", width: 4
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

private void sensorHandler(evt) {
    logDebug "sensorHandler(): ${evt.name} ${evt.value}"
    scheduleSensorCheck() // If operating, reschedule an existing timeout from now
    thermostat.setRemoteTemperature(averageTemperature())
    toggleThermostatModeAsNeeded()
}

private void scheduleSensorCheck() {
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
private void checkSensorActivity() {
    log.info "Sensor timeout: Setting to internal sensor"
    useInternalSensor()
}

private void thermostatTempHandler(evt) {
    logDebug "thermostatTempHandler(): ${evt.name} ${evt.value}"
    toggleThermostatModeAsNeeded()
}

private void toggleThermostatModeAsNeeded() {
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

private boolean tooFarPastSetpoint(String thermostatMode) {
    boolean ret = false
    def thermostatSetpoint = thermostat.currentValue("thermostatSetpoint")
    def currentTemp = averageTemperature()

    if (thermostatMode == "heat" || state.previousThermostatMode == "heat") {
        ret = currentTemp > thermostatSetpoint + offDelta
    } else if (thermostatMode == "cool" || state.previousThermostatMode == "cool") {
        ret = currentTemp < thermostatSetpoint - offDelta
    }

    logDebug "tooFarPastSetpoint: ${ret}"
    return ret
}

private void thermostatModeHandler(evt) {
    logDebug "thermostatModeHandler(): ${evt.name} ${evt.value}"
    if (thermostat.currentValue("thermostatMode") != "off" && state.previousThermostatMode) {
        logDebug "Clearing previousThermostatMode"
        state.previousThermostatMode = null
    }
}

private void thermostatOperatingStateHandler(evt) {
    logDebug "thermostatOperatingStateHandler(): ${evt.name} ${evt.value}"
    scheduleSensorCheck()
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
        log.debug msg
    }
}
