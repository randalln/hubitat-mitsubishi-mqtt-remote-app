definition(
        name: "Mitsubishi Remote Sensor Manager",
        namespace: "randalln",
        author: "Randall Norviel",
        description: "Use a remote temperature sensor with a Mitsubishi heat pump",
        category: "",
        iconUrl: "",
        iconX2Url: "",
        singleInstance: true
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