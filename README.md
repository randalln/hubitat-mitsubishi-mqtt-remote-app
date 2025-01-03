# Mitsubishi Heat Pump Hubitat Remote Sensor App

This is a companion app for sethkinast's fantastic [Hubitat driver](https://github.com/randalln/hubitat-mitsubishi-mqtt) for Mitsubishi heat pumps.

## Features

- Remote Sensor app can turn off the heat pump if it overshoots setpoints (because multi-head Mitsubishi HPs bleed to 
  all heads), and will turn it back on when the temp gets back into range

## Prerequisites

Set up the [Hubitat driver](https://github.com/randalln/hubitat-mitsubishi-mqtt) for your heat pumps

## Installation
1. Add the apps code to Hubitat by going to **Apps Code -> Add app -> Import** and pasting in the importURLs for the
[manager](https://raw.githubusercontent.com/randalln/hubitat-mitsubishi-mqtt-remote-app/main/mitsubishi-remote-sensor-manager.groovy) and
[child](https://raw.githubusercontent.com/randalln/hubitat-mitsubishi-mqtt-remote-app/main/mitsubishi-remote-sensor-app.groovy) apps.
2. Add the manager app by going to **Apps -> Add user app ->** `Mitsubishi Remote Sensor Manager`.
3. Add a child app by going to **Apps -> `Mitsubishi Remote Sensor Manager` -> `New Remote Sensor`**.
   - (Optional) Allow the app to set the thermostat mode to **off** if it exceeds the setpoint by some degrees. For me, this mostly occurs when idle
     heads are passively soaking up excess heat on my multi-head condenser. The app will set the heat pump to the previous mode when the
     temperature gets closer to the setpoint again.

## Troubleshooting

Turn on logging in each app
