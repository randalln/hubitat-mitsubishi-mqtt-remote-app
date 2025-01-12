// SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>
//
// SPDX-License-Identifier: MIT

/**
 * Mitsubishi Heat Pump Remote Sensor
 * https://github.com/randalln/hubitat-mitsubishi-mqtt-remote-app
 */

definition(
        name: "Mitsubishi Remote Sensor Manager",
        namespace: "randalln",
        author: "Randall Norviel",
        description: "Use a remote temperature sensor with a Mitsubishi heat pump",
        category: "",
        iconUrl: "",
        iconX2Url: "",
        singleInstance: true,
        importURL: "https://raw.githubusercontent.com/randalln/hubitat-mitsubishi-mqtt-remote-app/main/mitsubishi-remote-sensor-manager.groovy"
)

preferences {
    page(
            name: "Install",
            title: "Sensor Manager",
            install: true,
            uninstall: true
    ) {
        section {
            app(
                    name: "sensors",
                    appName: "Mitsubishi Remote Sensor with Heat Pump",
                    namespace: "randalln",
                    title: "New Remote Sensor",
                    multiple: true
            )
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {}