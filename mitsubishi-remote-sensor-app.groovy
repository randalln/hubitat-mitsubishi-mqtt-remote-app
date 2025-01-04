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

def installed() {
    updated()  // since installed() rather than updated() will run the first time the user selects "Done"
}

def uninstalled() {
    log.trace "uninstalled()"
    // Set the HP back to the internal sensor
    if (thermostat) {
        thermostat.setRemoteTemperature(0)
    }
}

def updated() {
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

private def averageTemperature() {
    def total = 0
    def count = 0

    for (sensor in sensors) {
        total += sensor.currentValue("temperature")
        count++
    }

    return total / count
}

def sensorHandler(evt) {
    logDebug "sensorHandler(): ${evt.name} ${evt.value}"
    scheduleSensorCheck()
    thermostat.setRemoteTemperature(averageTemperature())
    toggleThermostatModeAsNeeded()
}

def thermostatTempHandler(evt) {
    logDebug "thermostatTempHandler(): ${evt.name} ${evt.value}"
    toggleThermostatModeAsNeeded()
}

def thermostatModeHandler(evt) {
    logDebug "thermostatModeHandler(): ${evt.name} ${evt.value}"
    if (thermostat.currentValue("thermostatMode") != "off") {
        logDebug "Clearing state"
        state.previousThermostatMode = null
    }
}

def thermostatOperatingStateHandler(evt) {
    logDebug "thermostatOperatingStateHandler(): ${evt.name} ${evt.value}"
    scheduleSensorCheck()
}

private void scheduleSensorCheck() {
    logDebug("scheduleSensorCheck()")
    unschedule()
    if (timeout) {
        def thermostatOperatingState = thermostat.currentValue("thermostatOperatingState")
        if (thermostatOperatingState == "heating" || thermostatOperatingState == "cooling") {
            logDebug("Scheduling checkSensorActivity in ${timeout} minutes")
            runIn(Long.parseLong(timeout) * 60, "checkSensorActivity")
        }
    }
}

def checkSensorActivity() {
    logDebug("checkSensorActivity()")
    Calendar then = Calendar.instance
    then.add(Calendar.MINUTE, -Integer.parseInt(timeout))
    def events = null
    for (sensor in sensors) {
        events = sensor.events([max: 10]).find {
            it.name == "temperature" && it.getDate().toInstant().isAfter(then.toInstant())
        }
        if (events) {
            break
        }
    }
    if (!events) {
        log.info "Sensor timeout: Setting to internal sensor"
        thermostat.setRemoteTemperature(0)
    } else {
        logDebug("No sensor timeout: Scheduling next check")
        scheduleSensorCheck()
    }
}

private void toggleThermostatModeAsNeeded() {
    String thermostatMode = thermostat.currentValue("thermostatMode")

    if (canTurnOff && offDelta > 0) {
        if (shouldBeOff(thermostatMode)) {
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

private boolean shouldBeOff(String thermostatMode) {
    boolean ret = false
    def thermostatSetpoint = thermostat.currentValue("thermostatSetpoint")
    def currentTemp = averageTemperature()

    if (thermostatMode == "heat" || state.previousThermostatMode == "heat") {
        ret = currentTemp > thermostatSetpoint + offDelta
    } else if (thermostatMode == "cool" || state.previousThermostatMode == "cool") {
        ret = currentTemp < thermostatSetpoint - offDelta
    }

    logDebug "shouldBeOff(): ${ret}"
    return ret
}

private void logDebug(String msg) {
    if (logEnable) {
        log.debug msg
    }
}