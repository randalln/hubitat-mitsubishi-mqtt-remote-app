<!--
SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>

SPDX-License-Identifier: MIT
-->

# Mitsubishi Heat Pump Hubitat Remote Sensor App

This is a companion app for sethkinast's fantastic [Hubitat driver](https://github.com/randalln/hubitat-mitsubishi-mqtt) for Mitsubishi heat pumps.

## Features

- Remote Sensor app can turn off the heat pump if it overshoots setpoints (because multi-head Mitsubishi HPs bleed to 
  all heads), and will turn it back on when the temp gets back into range
- Remote sensor timeout setting will revert heat pump to internal sensor
- Can adjust setpoint to avoid an immediate cycle when turning the heat pump on

## Prerequisites

1. Set up the [Hubitat driver](https://github.com/randalln/hubitat-mitsubishi-mqtt) for your heat pumps
2. Acknowledge the very real **DANGER** that relying on a remote sensor via smart hub exposes you to the risk of the heat pump running forever if 
   there is a network outage or another type of failure

## Installation
1. Add the apps (and driver, for now) code to Hubitat by either:
   1. Installing through HPM by adding a [custom repository](https://raw.githubusercontent.com/randalln/randalln-hubitat/main/repository.json) (for 
      now) and installing via **Browse by Tags -> Control -> Mitsubishi Heat Pump Remote Sensor**
   2. Going to **Apps Code -> Add app -> Import** and pasting in the importURLs for the
      [manager](https://raw.githubusercontent.com/randalln/hubitat-mitsubishi-mqtt-remote-app/main/src/main/groovy/mitsubishi-remote-sensor-manager.groovy) and
      [child](https://raw.githubusercontent.com/randalln/hubitat-mitsubishi-mqtt-remote-app/main/src/main/groovy/mitsubishi-remote-sensor-app.groovy) 
      apps
2. Add the manager app by going to **Apps -> Add user app ->** `Mitsubishi Remote Sensor Manager`
3. Add a child app by going to **Apps -> `Mitsubishi Remote Sensor Manager` -> `New Remote Sensor`**
   - (Optional) Allow the app to set the thermostat mode to **off** if it exceeds the setpoint by some degrees, then  set the heat pump
     to the previous mode when the temperature gets closer to the setpoint again
     - For me this mostly occurs when idle heads are passively soaking up excess heat on my multi-head condenser
   - (Optional, but recommended) Set sensor timeout to switch to internal sensor on the head
     - The temp sensors in my heads are wildly inaccurate, but it beats having the heat pump run until the end of time
   - (Optional) Adjust setpoint to avoid an immediate cycle when turning the heat pump on
       - It's not clear why the heat pump always initiates a cycle, so just turn the setpoint down a couple of degrees for a minute until it "settles"

## Troubleshooting and Notes

* Turn on logging in each child app and, possibly, the driver
* Because the heat pumps work in Celsius internally (and I've updated the driver to reflect that), I set my dashboard thermostats to increment by 0.9F