/*
 * XBee Presence
 *
 *  Copyright 2019 Daniel Terryn
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:
 *
 *    Date        Who            What
 *    ----        ---            ----
 *    2019-05-21  Daniel Terryn  Original Creation
 *    2020-01-01  Jason Bottjen  Added options for debug and descriptionText logging
 *    2020-01-08  N0W0n          added 10 minute departure option and battery replace date
 *    2020-03-09  N0W0n          added 15 and 30 minute departure time
 *    2020-06-22  N0W0n          general house cleaning
 *    2020-06-26  rsjaffe        added temperature monitoring, requires updated main.py
 */
metadata {
    definition (name: "XBee3 Presence V/T", namespace: "dan.t", author: "Daniel Terryn") {
        capability "Sensor"
        capability "Configuration"
        capability "Battery"
        capability "Presence Sensor"
        capability "Temperature Measurement"

        command "resetBatteryReplacedDate"
        
        attribute "batteryLastReplaced", "String"
    }
    
    preferences {
        input "fullVoltageValue", "enum", title:"Battery 100% mV:", required:true, defaultValue:3300, options:[3000:"3000 mV",3300:"3300 mV",3600:"3600 mV"]
        input "checkInterval", "enum", title:"Minutes elapsed until sensor is not present", required:true, defaultValue:3, options:[1:"1 Minute",2:"2 Minutes",3:"3 Minutes", 4:"4 Minutes",5:"5 Minutes",10:"10 Minutes",15:"15 Minutes",30:"30 Minutes"]
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "logDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
    def newlyPaired = paired ? " for newly paired sensor" : ""
    sendEvent(name: "batteryLastReplaced", value: new Date())
    if (logDesc) log.info "Setting Battery Last Replaced to current date${newlyPaired}"
}

def updated() {
    stopTimer()
    startTimer()
    if (logEnable) runIn(1800,logsOff)
}

def installed() {
}

def configure() {
    log.warn "configure..."
    return []
}

def parse(String description) {
    state.lastCheckin = now()  
    handlePresenceEvent(true)
    if (description?.startsWith('catchall')) {
        parseCatchAllMessage(description)
    }
    return []
}

private Map parseCatchAllMessage(String description) {
    Map resultMap = [:]
    def cluster = zigbee.parse(description)
    if (cluster.clusterId == 0x0011 && cluster.command == 0x01){
        handleBatteryEvent(cluster)
    } else if (cluster.clusterId == 0x0011 && cluster.command == 0x02){
        handleTemperatureEvent(cluster)
    } 
    return resultMap
}

private handleTemperatureEvent(cluster) {
    def descriptionText   
    def temperature_string = ""
    for (element in cluster.data) {
        temperature_string = temperature_string + Integer.toString(element,16).padLeft(2, '0')
    }
    temperature_c = Integer.parseInt(temperature_string) / 100
    if (logDesc) log.info "Temperature: ${temperature_c}"
    def linkText = getLinkText(device)
    descriptionText = "${linkText} XBee chip temperature is ${temperature_c}C"
    def eventMap = [
        name: 'temperature',
        value: temperature_c,
        unit: "C",
        descriptionText: descriptionText,
        translatable: true,
        type: "digital"
    ]
    if (logEnable) log.debug "Creating temperature event for temperature=${temperature_c}C: ${linkText} ${eventMap.name} is ${eventMap.value}C"
    sendEvent(eventMap)      
}
/**
 * Create battery event from reported battery voltage.
 *
 * @param volts Battery voltage in mV
 */
private handleBatteryEvent(cluster) {
    def descriptionText   
    def battery_string = ""
    for (element in cluster.data) {
        battery_string = battery_string + Integer.toString(element,16).padLeft(2, '0')
    }
    battery_mV = Integer.parseInt(battery_string)
    // log.debug "Battery mV: ${battery_mV}"
    if (logDesc) log.info "Battery mV: ${battery_mV}"
    def value = 100
    if (battery_mV <= 2100) {
        value = 0
    }
    else {
        /* Formula
            Minimum Voltage = 2100mV
            Divider = (100% Voltage in mV - 2100) (max and default is 3600)
        */
        def offset = battery_mV - 2100
        value = Math.round((offset / (Integer.parseInt(fullVoltageValue)-2100)) * 100)
        if (value > 100)
        value = 100
    }
    def linkText = getLinkText(device)
    def currentPercentage = device.currentState("battery")?.value
    if (currentPercentage && (Integer.parseInt(currentPercentage, 10) == value)) {
        return
    }
    descriptionText = "${linkText} battery is ${value}%"
    def eventMap = [
        name: 'battery',
        value: value,
        unit: "%",
        descriptionText: descriptionText,
        translatable: true,
        type: "digital"
    ]
    if (logEnable) log.debug "Creating battery event for voltage=${battery_mV/1000}V: ${linkText} ${eventMap.name} is ${eventMap.value}%"
    sendEvent(eventMap)  
}

private handlePresenceEvent(present) {
    def wasPresent = device.currentState("presence")?.value == "present"
    if (!wasPresent && present) {
        if (logDesc) log.info "Sensor is present"
        startTimer()
    } else if (!present) {
        if (logDesc) log.info "Sensor is not present"
        stopTimer()
    } else if (wasPresent && present) {
        if (logEnable) log.debug "Sensor already present"
        return
    }
    def linkText = getLinkText(device)
    def descriptionText
    if ( present )
        descriptionText = "${linkText} has arrived"
    else
        descriptionText = "${linkText} has left"
    def eventMap = [
        name: "presence",
        value: present ? "present" : "not present",
        linkText: linkText,
        descriptionText: descriptionText,
        translatable: true,
        type: "digital"
    ]
    if (logEnable) log.debug "Creating presence event: ${device.displayName} ${eventMap.name} is ${eventMap.value}"
    sendEvent(eventMap)
}

private startTimer() {
    if (logEnable) log.debug "Scheduling periodic timer"
    runEvery1Minute("checkPresenceCallback")
}

private stopTimer() {
    if (logEnable) log.debug "Stopping periodic timer"
    // Always unschedule to handle the case where the DTH was running in the cloud and is now running locally
    unschedule("checkPresenceCallback")
}

def checkPresenceCallback() {
    def timeSinceLastCheckin = (now() - state.lastCheckin ?: 0) / 1000
    def theCheckInterval = Integer.parseInt(checkInterval) * 60
    if (logEnable) log.debug "Sensor checked in ${timeSinceLastCheckin} seconds ago"
    if (timeSinceLastCheckin >= theCheckInterval) {
        handlePresenceEvent(false)
    }
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}
