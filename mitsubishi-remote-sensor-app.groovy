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
            input "sensors", "capability.temperatureMeasurement", title: "Sensors", multiple: true
            input "thermostat", "device.MitsubishiHeatPumpMQTT", title: "Heat Pump"
            input name: "canTurnOff", type: "bool", title: "Turn off if too far past setpoint?"
            input name: "offDelta", type: "decimal", title: "Degrees"
            input name: "logEnable", type: "bool", title: "Enable logging?"
        }
    }
}

def installed() {
    updated()  // since installed() rather than updated() will run the first time the user selects "Done"
}

def uninstalled() {
    log.trace "uninstalled"
    // Set the HP back to the internal sensor
    if (thermostat) {
        thermostat.setRemoteTemperature(0)
    }
}

def updated() {
    logDebug "updated: $app.label"
    unsubscribe()

    subscribe(sensors, "temperature", sensorHandler)
    // To turn HP on or off
    subscribe(thermostat, "thermostatSetpoint", thermostatHandler)
    subscribe(thermostat, "temperature", thermostatHandler)
    // To clear app state
    subscribe(thermostat, "thermostatMode", thermostatModeHandler)

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
    thermostat.setRemoteTemperature(averageTemperature())
    toggleThermostatModeAsNeeded()
}

def thermostatHandler(evt) {
    logDebug "thermostatHandler(): ${evt.name} ${evt.value}"
    toggleThermostatModeAsNeeded()
}

def thermostatModeHandler(evt) {
    logDebug "thermostatModeHandler(): ${evt.name} ${evt.value}"
    if (thermostat.currentValue("thermostatMode") != "off") {
        logDebug "Clearing state"
        state.previousThermostatMode = null
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
    def delta = thermostatMode == "off" && state.previousThermostatMode ? 1.0 : offDelta

    if (thermostatMode == "heat" || state.previousThermostatMode == "heat") {
        ret = currentTemp > thermostatSetpoint + delta
    } else if (thermostatMode == "cool" || state.previousThermostatMode == "cool") {
        ret = currentTemp < thermostatSetpoint - delta
    }

    logDebug "shouldBeOff(): ${ret}"
    return ret
}

private void logDebug(String msg) {
    if (logEnable) {
        log.debug msg
    }
}