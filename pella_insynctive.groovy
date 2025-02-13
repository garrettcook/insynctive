/*
Pella Insynctive Telnet Driver

v1.0 - initial release
v1.0.1 - fix issue with connectivity check
v1.0.2 - rebuildChildDevices without deleting and re-adding all -- preserve existing devices
v1.0.3 - fix issue with connectivity check, embed child device names in file (and write to device label on refresh)
v1.0.4 - miscellaneous bug fixes and changes to work with newer Hubitat firmwares.
v1.0.5 - fix issue with hub reset function not working in some scenarios.  Refresh pointcount when refreshing parent.
v1.0.6 - improve logging
v1.0.7 - refresh all child labels during parent refresh
v1.0.8 - various improvements
v1.1.0 - significant improvements to scheduling apparatus, error handling, and other elements
v1.1.1 - additional improvements to scheduling apparatus and error handling
v1.1.2 - minor improvements to timing of qualifying a received message as a response to a sent message
*/

metadata {
    definition (name: "Pella Insynctive", namespace: "hubitat", author: "Assembled by Garrett Cook") {
		capability "Refresh"
		capability "Battery"
        capability "ContactSensor"
		capability "Telnet"
		capability "Initialize"

		command "install"
		command "rebuildChildDevices"
		command "evalConnection"
		attribute "lastMessageReceipt", "Date"
		// attribute "numberOfDevices", "Number"
		attribute "rebootBridge", "ENUM", ["Yes","No"]

		// singleThreaded: true
	}

    preferences {
		input name: "txtEnable", type: "bool", title: "Enable telnet info logging", description: ""
		input name: "logEnable", type: "bool", title: "Enable debug logging", description: ""
		input name: "ipAddress", type: "text", title: "Pella Bridge Local IP Address", description: "", required: true
		input name: "active", type: "bool", title: "Service Active", description: "", defaultValue: true
		input name: "retry", type: "number", title: "Connection Retry Interval", description: "Frequency (in minutes) to test and retry connection, if failed", defaultValue: 5, range: "1..60", required: true
    }
}

def initialize()
{
	if (!parent) {
		unschedule()
		atomicState.remove("lastMessageSent")
		if(active) {
			if (ipAddress == null || ipAddress == "") {
				log.error("Cannot Connect: IP Address is required")
				return false
			}
			try {
				def hub = location.hubs[0]
				if (hub.uptime < 120) {
					pauseExecution(120000 - (hub.uptime * 1000)) /* wait if the hub has not been running for at least two minutes -- allows telnet services to load on the hub and also allows Pella bridge time to boot up if the restart was due to a power outage */
				} else {
					telnetClose() /* closes telnet if it was already open */
					displayInfoLog("Closed existing telnet connection (if any)")
					pauseExecution(20000)
				}
			} catch(Exception ex) {
				telnetClose() /* closes telnet if it was already open */
				displayInfoLog("Could not obtain location.hubs; closed existing telnet connection (if any)")
				pauseExecution(20000)
			}
			displayInfoLog("Opening telnet connection")
			try {
				telnetConnect("${ipAddress}", 23, null, null)
			} catch(Exception ex) {
				log.warn(ex.toString())
				if(ex.toString() =~ /java.net.NoRouteToHostException/) {
					displayInfoLog("Rebooting bridge")
					sendEvent(name: "rebootBridge", value: "Yes", displayed: false)
					displayInfoLog("Will attempt to re-initialize connection, after 120 second delay.")
					runIn(120, "initialize") /* wait a bit until attempting the reconnect -- gives bridge time to reboot. */
				} else {
					runIn(60 * retry, "initialize")
					displayInfoLog("Will try again in ${(60 * retry)} seconds") /* Note: usually takes 4-5 minutes to recover from a telnet error */
				}
				return false
			}
			// displayInfoLog("Connection established")
			// sendMsg('?POINTCOUNT') /* test connection by getting a simple count of the number of devices connected to the Pella Bridge */
			pauseExecution(2000) /* allows bridge to initialize before sending further requests, before returning successful 'true' result */
			runIn(60 * retry, "evalConnection")
			return true
		} else {
			telnetClose() /* closes telnet if it was already open */
			return false
		}
	}
}


/* attempt to reconnect if haven't received any response from the Pella Bridge in more than X minutes.  Could also use rebootBridge in a Rule to toggle power to the Pella Bridge based on rebootBridge value, if for example plugged into a smart plug. */
def evalConnection()
{
	if(!parent && active) {
		if(retry > 0) {
			unschedule("evalConnection")
			runIn(60 * retry, "evalConnection")
		}
		sendMsg('?POINTCOUNT') /* test connection by getting a simple count of the number of devices connected to the Pella Bridge */
		pauseExecution(2000)
		if (device.currentValue('lastMessageReceipt')) {
			duration = groovy.time.TimeCategory.minus(
				new Date(),
				Date.parse("EEE MMM dd HH:mm:ss zzz yyyy", device.currentValue('lastMessageReceipt'))
			)
			if(retry > 0) {
				displayDebugLog("Minutes since last message received: ${duration.minutes}")
				if (duration.minutes > (retry + 1)) {
					log.warn("Have not received a message from Pella Bridge in ${duration.minutes} minutes.")
					if (duration.minutes > ((retry * 3) + 1)) {
						displayInfoLog("Rebooting bridge")
						sendEvent(name: "rebootBridge", value: "Yes", displayed: false)
						displayInfoLog("Will attempt to re-initialize connection, after 120 second delay.")
						runIn(120, "initialize") /* wait a bit until attempting the reconnect -- gives bridge time to reboot. */
					} else {
						initialize()
					}
				}
			}
		}
	}
}

def sendMsg(String message) 
{
	// displayDebugLog("Sending Telnet message: " + message)	
    atomicState.lastMessageSent = message
	sendHubCommand(new hubitat.device.HubAction(message, hubitat.device.Protocol.TELNET))
	displayDebugLog("Telnet message sent: ${atomicState.lastMessageSent}")
	pauseExecution(800) /* wait 0.8 seconds before clearing lastMessageSent */
	atomicState.remove("lastMessageSent")
}

def refresh() 
{
	displayDebugLog("Refreshing")
	if (parent) {
		def matches = (device.deviceNetworkId =~ /\d+$/) /* grabs digits at end of deviceNetworkId */
		def firstmatch = matches[0]
		parent.sendMsg("?POINTSTATUS-${firstmatch}")
		pauseExecution(1000) /* wait before sending the next command */
		parent.sendMsg("?POINTBATTERYGET-${firstmatch}")
		pauseExecution(1000) /* wait before sending the next command */
		parent.sendMsg("?POINTID-${firstmatch}")
	} else {
		pauseExecution(1000) /* wait before sending the next command */
		for (i in 1..(atomicState.numberOfDevices)) {
			sendMsg("?POINTID-${i.toString().padLeft(3, "0")}")
			pauseExecution(1000) /* wait before sending the next command */
			sendMsg("?POINTBATTERYGET-${i.toString().padLeft(3, "0")}")
			pauseExecution(1000) /* wait before sending the next command */
		}
	}
}

def parse(String message) {
	displayDebugLog("Message received: ${message}")
	// displayDebugLog("lastMessageSent: ${atomicState.lastMessageSent}")

	sendEvent(name: "rebootBridge", value: "No", displayed: false)
	sendEvent(name: "lastMessageReceipt", value: new Date(), displayed: false)

	if(!parent && active && retry > 0) {
		unschedule("evalConnection")
		runIn(60 * retry, "evalConnection")
	}
	/* Send message data to appropriate parsing function based on the telnet message received */
	if (message.contains("POINTSTATUS-")) {
		parseSupervisorySignal(message)
	} else if (message.contains("Insynctive Telnet Server")) {
		displayInfoLog("Connection established")
	} else if (atomicState.lastMessageSent) {
		if (atomicState.lastMessageSent.contains("?POINTSTATUS") && (message =~ /^\W*\$[0-9A-F]{2}\W*$/)) {
			parseStatusResponse(atomicState.lastMessageSent, message)
		} else if (atomicState.lastMessageSent.contains("?POINTBATTERYGET") && (message =~ /^\W*\$[0-9A-F]{2}\W*$/)) {
			parseBatteryResponse(atomicState.lastMessageSent, message)
		} else if (atomicState.lastMessageSent.contains("?POINTID") && (message =~ /^\W*\$[0-9A-F]{6}\W*$/)) {
			parseIDResponse(atomicState.lastMessageSent, message)
		} else if (atomicState.lastMessageSent == "?POINTCOUNT" && (message =~ /^\W*\d{3}\W*$/)) {
			parseDevices(message)
		// } else if (message.contains("Insynctive Telnet Server")) {
			// displayInfoLog("Connection established")
		} else {
			log.warn("Unable to parse message.  Last message sent: ${atomicState.lastMessageSent}; message received: ${message}")
		}
	} else if (message =~ /^\W*\d{3}\W*$/) {
		parseDevices(message)
	} else {
		log.warn("Unable to parse message.  Message received: ${message}")
	}
}

def telnetStatus(String status) {
	log.warn("telnetStatus: ${status}")
	pauseExecution(2000)
	initialize()
}

/* Obtain the number of Insynctive devices connected to the Pella Bridge */
def parseDevices(valueString) {
	// displayDebugLog("Device count string to parse = '${valueString}'")
	// def rawValue = valueString.toInteger()
	// def descText = "Number of devices connected to Pella Bridge is: ${rawValue}"
	// displayDebugLog(descText)
	// sendEvent(name: 'numberOfDevices', value: rawValue, descriptionText: descText, displayed: false)
	atomicState.numberOfDevices = valueString.toInteger()
}

def parseSupervisorySignal(message) {
	displayDebugLog("Update string to parse = '${message}'")
	def matches = ("${message}" =~ /0\d{2}/) /* grabs integers of Pella device number */
	def firstmatch = matches[0]
	displayDebugLog("device to send atomicState to = $device.deviceNetworkId-$firstmatch")
	def childDevice = childDevices.find{it.deviceNetworkId == "$device.deviceNetworkId-$firstmatch"}
	if (childDevice) {
		if (message.contains("\$00")) {
			childDevice.sendEvent(name: "contact", value: "closed", displayed: true)
		} else if (message.contains("\$01") || message.contains("\$02")) {
			childDevice.sendEvent(name: "contact", value: "open", displayed: true)
		}
		childDevice.sendEvent(name: "lastStatusUpdate", value: new Date(), displayed: true)
	}
}

def parseStatusResponse(lastMessageSent, message) {
	displayDebugLog("Status Response string to parse = '${message}'")
	def matches = ("${lastMessageSent}" =~ /(?<=\-)\d{3}\W*$/) /* grabs integers of Pella device number */
	def firstmatch = matches[0]
	displayDebugLog("Device to send atomicState to = $device.deviceNetworkId-$firstmatch")
	def childDevice = childDevices.find{it.deviceNetworkId == "$device.deviceNetworkId-$firstmatch"}
	if (childDevice) {
		if (message.contains("\$00")) {
			childDevice.sendEvent(name: "contact", value: "closed", displayed: true)
		} else if (message.contains("\$01") || message.contains("\$02")) {
			childDevice.sendEvent(name: "contact", value: "open", displayed: true)
		}
	}
}

def parseBatteryResponse(lastMessageSent, message) {
	displayDebugLog("Battery string to parse = '${message}'")
	def matches = ("${lastMessageSent}" =~ /(?<=\-)\d{3}\W*$/) /* grabs integers of Pella device number */
	def firstmatch = matches[0]
	def batterymatches = ("${message}" =~ /\w{2}/) /* grabs reported battery value */
	def firstbatterymatch = batterymatches[0]
	def rawValue = Integer.parseInt(firstbatterymatch,16)
	def descText = "Battery level is ${rawValue}%"
	displayDebugLog("Device to send atomicState to = $device.deviceNetworkId-$firstmatch")
	def childDevice = childDevices.find{it.deviceNetworkId == "$device.deviceNetworkId-$firstmatch"}
	if (childDevice) {
		childDevice.sendEvent(name: "battery", value: "${rawValue}", displayed: true, unit: "%")
	}
}

def parseIDResponse(lastMessageSent, message) {
	displayDebugLog("ID string to parse = '${message}'")
	def matches = ("${lastMessageSent}" =~ /(?<=\-)\d{3}\W*$/) /* grabs integers of Pella device number */
	def firstmatch = matches[0]
	def IDmatches = ("${message}" =~ /[0-9A-F]+/) /* grabs reported serial number */
	def firstIDmatch = IDmatches[0]
	def descText = "ID is ${firstIDmatch}"
	displayDebugLog("Device to send atomicState to = $device.deviceNetworkId-$firstmatch")
	def childDevice = childDevices.find{it.deviceNetworkId == "$device.deviceNetworkId-$firstmatch"}
	if (childDevice) {
		childDevice.sendEvent(name: "ID", value: "${firstIDmatch}", displayed: true)
		displayDebugLog("Old Label: ${childDevice.label}")
		switch(firstIDmatch) {
			case "680925":
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Front Door Lock"); 
				break; 
			case "680957": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Deck Door Lock"); 
				break; 
			case "6808FE": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Garage People Door Lock"); 
				break; 
			case "6808EB": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("North Patio Door Lock"); 
				break; 
			case "680960": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("South Patio Door Lock"); 
				break; 
			case "082404": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("North Basement Window"); 
				break; 
			case "082452": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("South Basement Window"); 
				break; 
			case "0825C9": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Dining Room Window"); 
				break; 
			case "080C2C": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Crawlspace Door"); 
				break; 
			case "08233F": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("West Bedroom Window"); 
				break; 
			case "0823F0": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Office Window"); 
				break; 
			case "0822A0": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Master Bedroom Window"); 
				break; 
			case "082145": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Deck Door"); 
				break; 
			case "0828B1": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Garage People Door"); 
				break; 
			case "082175": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("North Patio Door"); 
				break; 
			case "082135": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("South Patio Door"); 
				break; 
			case "08214B": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Front Door"); 
				break; 
		}
		displayDebugLog("New Label: ${childDevice.label}")
	}
}

def installed() {
}

/* configure() runs after installed() */
def configure() {
}

/* updated() will run every time user saves preferences */
def updated() {
	// initialize()
}

def rebuildChildDevices() {
    displayDebugLog("Rebuilding Child Devices")
    deleteChildren()
    install()
}

def install() {
	if (!parent) {
		// unschedule("evalConnection")
		if (initialize() == false) {
			log.error("Cannot Connect")
			return
		}
		displayDebugLog("Creating new child devices")
		for (i in 1..(atomicState.numberOfDevices)) {
			try {
				addChildDevice('hubitat', "Pella Insynctive", "$device.deviceNetworkId-${i.toString().padLeft(3, "0")}", [name: "Pella Insynctive Device", isComponent: true])
			} catch(Exception ex) {
				log.error("Cannot create child device for ${i.toString().padLeft(3, "0")}")
			}
		}
	}
}

def deleteChildren() {
	displayDebugLog("Deleting unused child devices")
	def children = getChildDevices()
    children.each {
		child->displayDebugLog("Evaluating ${child.deviceNetworkId}")
		matches = ("${child.deviceNetworkId}" =~ /(?<=\-)\d{3}\W*$/) /* grabs integers of Pella device number */
		firstmatch = matches[0]
		childNum = firstmatch.toInteger()
		if (childNum > atomicState.numberOfDevices) {
			displayDebugLog("Deleting ${child.deviceNetworkId}")
			deleteChildDevice(child.deviceNetworkId)
		}
    }
}

private def displayDebugLog(message) {
	if (logEnable)
		log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (txtEnable)
		log.info "${device.displayName}: ${message}"
}
