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

*/

metadata {
    definition (name: "Pella Insynctive", namespace: "hubitat", author: "Assembled by Garrett Cook") {
		capability "Refresh"
		capability "Battery"
        capability "ContactSensor"
		capability "Telnet"
		capability "Initialize"

		command "install"
		command "reconnect"
		command "rebuildChildDevices"
		// command "evalConnection"
        // command "sendMessage", [[name: "message*", type: "STRING", description: "Message to send", defaultValue: "?POINTCOUNT"]]
        // command "sendMessageToParent", [[name: "message*", type: "STRING", description: "Message to send", defaultValue: "?POINTCOUNT"]]
		attribute "lastMessageReceipt", "Date"
		attribute "numberOfDevices", "Number"
		attribute "rebootBridge", "ENUM", ["Yes","No"]

		singleThreaded: true
	}

    preferences {
		input name: "infoLogging", type: "bool", title: "Enable telnet info logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug logging", description: ""
		input name: "ipAddress", type: "text", title: "Pella Bridge Local IP Address", description: "", required: true
		input name: "active", type: "bool", title: "Service Active", description: "", defaultValue: true
		// input name: "retry", type: "number", title: "Connection Retry Interval", description: "Frequency (in minutes) to test and retry connection, if failed"
    }
}

def initialize()
{
	if (!parent) {
		if(active) {
			if (ipAddress == null || ipAddress == "") {
				log.error("Cannot Connect: IP Address is required")
				return false
			}
			def hub = location.hubs[0]
			displayDebugLog("Hub Uptime: ${hub.uptime}") /* (uptime may impact ability to connect to telnet if hubitat was just started up.  May be useful for setting a delay if attempts to connect to telnet too soon) */
			if (hub.uptime < 120)
				pauseExecution(120000) /* wait if the hub has not been running for at least two minutes -- allows telnet services to load on the hub and also allows Pella bridge time to boot up if the restart was due to a power outage */
			displayInfoLog("Closing existing telnet connection")
			telnetClose() /* closes telnet if it was already open */
			pauseExecution(2000)
			displayInfoLog("Opening telnet connection")
			try {
				telnetConnect("${ipAddress}", 23, null, null)
			} catch(Exception ex) {
				log.warn(ex.toString() + ex.getMessage())
    			// displayInfoLog("Could not open telnet connection")
				return false
			}
			displayInfoLog("Establishing connection")
			pauseExecution(3000)
			sendMessage('?POINTCOUNT') /* test connection by getting a simple count of the number of devices connected to the Pella Bridge */
			pauseExecution(2000)
			schedule("0 1/4 * ? * * *", evalConnection) /* test connection every four minutes.  By default, any such previous schedule is overwritten */
			return true
		} else {
			unschedule()
			telnetClose() /* closes telnet if it was already open */
			return false
		}
	} else {
		unschedule()
	}
}

def reconnect()
{
	// atomicState.lastMessageSent = null
	displayInfoLog("Attempting reconnect")
	atomicState.remove("lastMessageSent")
	pauseExecution(10000) /* wait a bit until attempting the reconnect */
	initialize()
}

/* attempt to reconnect if haven't received any response from the Pella Bridge in more than X minutes.  Could also use rebootBridge in a Rule to toggle power to the Pella Bridge based on rebootBridge value, if for example plugged into a smart plug. */
def evalConnection()
{
	sendMessage('?POINTCOUNT') /* test connection by getting a simple count of the number of devices connected to the Pella Bridge */
	pauseExecution(2000)
	if (device.currentValue('lastMessageReceipt')) {
		// duration = groovy.time.TimeCategory.minus(
			// new Date(),
			// Date.parse("yyyy-MM-dd'T'HH:mm:ssX", atomicState.lastMessageReceipt)
		// )
		// duration = groovy.time.TimeCategory.minus(
			// new Date(),
			// device.currentValue('lastMessageReceipt')
		// )
		duration = groovy.time.TimeCategory.minus(
			new Date(),
			Date.parse("EEE MMM dd HH:mm:ss zzz yyyy", device.currentValue('lastMessageReceipt'))
		)
		displayDebugLog("Minutes since last message received: ${duration.minutes}")
		if (duration.minutes > 10) {
			log.warn("Have not received a message from Pella Bridge in ${duration.minutes} minutes.")
			if (duration.minutes > 30) {
				displayInfoLog("Rebooting bridge.")
				sendEvent(name: "rebootBridge", value: "Yes", displayed: false)
			}
			reconnect()
		}
	}
}

// def sendMessage(String message, forPoint) 
def sendMessage(String message) 
{
	displayDebugLog("Sending Telnet message: " + message)	
	// atomicState.lastSentForPoint = forPoint
    atomicState.lastMessageSent = message
	sendHubCommand(new hubitat.device.HubAction(message, hubitat.device.Protocol.TELNET))
	displayDebugLog("Telnet message sent: ${atomicState.lastMessageSent}")
}

def refresh() 
{
	displayDebugLog("Refreshing")
	if (parent) {
		def matches = (device.deviceNetworkId =~ /\d+$/) /* grabs digits at end of deviceNetworkId */
		def firstmatch = matches[0]
		device.deleteCurrentState("lastMessageReceipt")
		device.deleteCurrentState("numberOfDevices")
		device.deleteCurrentState("rebootBridge")
		parent.sendMessage("?POINTSTATUS-${firstmatch}")
		pauseExecution(1000) /* wait before sending the next command */
		parent.sendMessage("?POINTBATTERYGET-${firstmatch}")
		pauseExecution(1000) /* wait before sending the next command */
		// parent.sendMessage("?POINTID-${firstmatch}", firstmatch)
		parent.sendMessage("?POINTID-${firstmatch}")
	} else {
		evalConnection()
		pauseExecution(1000) /* wait before sending the next command */
		for (i in 1..(device.currentValue('numberOfDevices'))) {
			// sendMessage("?POINTID-${i.toString().padLeft(3, "0")}", "${i.toString().padLeft(3, "0")}")
			sendMessage("?POINTID-${i.toString().padLeft(3, "0")}")
			pauseExecution(1000) /* wait before sending the next command */
			sendMessage("?POINTBATTERYGET-${i.toString().padLeft(3, "0")}")
			pauseExecution(1000) /* wait before sending the next command */
			// try {
				// addChildDevice('hubitat', "Pella Insynctive", "$device.deviceNetworkId-${i.toString().padLeft(3, "0")}", [name: "Pella Insynctive Device", isComponent: true])
			// } catch(Exception ex) {
			// }
		}
	}
}

def parse(String message) {
	displayDebugLog("Message received: ${message}")
	displayDebugLog("lastMessageSent: ${atomicState.lastMessageSent}")

	sendEvent(name: "rebootBridge", value: "No", displayed: false)
	sendEvent(name: "lastMessageReceipt", value: new Date(), displayed: false)
	// atomicState.lastMessageReceipt = new Date()

	/* Send message data to appropriate parsing function based on the telnet message received */
	if (message.contains("POINTSTATUS-")) {
		parseSupervisorySignal(message)
	} else if (atomicState.lastMessageSent) {
		if (atomicState.lastMessageSent.contains("?POINTSTATUS") && (message =~ /^\W*\$[0-9A-F]{2}\W*$/)) {
			parseStatusResponse(atomicState.lastMessageSent, message)
		} else if (atomicState.lastMessageSent.contains("?POINTBATTERYGET") && (message =~ /^\W*\$[0-9A-F]{2}\W*$/)) {
			parseBatteryResponse(atomicState.lastMessageSent, message)
		} else if (atomicState.lastMessageSent.contains("?POINTID") && (message =~ /^\W*\$[0-9A-F]{6}\W*$/)) {
			parseIDResponse(atomicState.lastMessageSent, message)
		} else if (atomicState.lastMessageSent == "?POINTCOUNT" && (message =~ /^\W*\d{3}\W*$/)) {
			parseDevices(message)
		} else if (message.contains("Insynctive Telnet Server")) {
			displayInfoLog("Connection established")
		} else {
			log.warn("Unable to parse message.  lastMessageSent: ${atomicState.lastMessageSent}; message: ${message}")
		}
	} else if (message.contains("Insynctive Telnet Server")) {
		displayInfoLog("Connection established")
	} else if (message =~ /^\W*\d{3}\W*$/) {
		parseDevices(message)
	} else {
		log.warn("Unable to parse message.  message: ${message}")
	}
	pauseExecution(500) /* wait 0.5 seconds before sending the next command */
	atomicState.remove("lastMessageSent")
	// pauseExecution(1000) /* wait 0.5 seconds before sending the next command */
}

def telnetStatus(String status) {
	// displayInfoLog("telnetStatus: ${status}")
	log.warn("telnetStatus: ${status}")
	// sendEvent(name: "rebootBridge", value: "Yes")
	reconnect()
}

/* Obtain the number of Insynctive devices connected to the Pella Bridge */
def parseDevices(valueString) {
	displayDebugLog("Device count string to parse = '${valueString}'")
	def rawValue = valueString.toInteger()
	def descText = "Number of devices connected to Pella Bridge is: ${rawValue}"
	displayDebugLog(descText)
	sendEvent(name: 'numberOfDevices', value: rawValue, descriptionText: descText, displayed: false)
}

def parseSupervisorySignal(message) {
	displayDebugLog("Update string to parse = '${message}'")
	def matches = ("${message}" =~ /0\d{2}/) /* grabs integers of Pella device number */
	def firstmatch = matches[0]
	displayDebugLog("device to send atomicState to = $device.deviceNetworkId-$firstmatch")
	def childDevice = childDevices.find{it.deviceNetworkId == "$device.deviceNetworkId-$firstmatch"}
	if (childDevice) {
		if (message.contains("\$00")) {
			// childDevice.sendEvent(name: "contact", value: "closed", descriptionText: "status is closed", displayed: true)
			childDevice.sendEvent(name: "contact", value: "closed", displayed: true)
			// childDevice.sendEvent(name: "lastStatusUpdate", value: new Date(), displayed: true)
		} else if (message.contains("\$01") || message.contains("\$02")) {
			// childDevice.sendEvent(name: "contact", value: "open", descriptionText: "status is open", displayed: true)
			childDevice.sendEvent(name: "contact", value: "open", displayed: true)
			// childDevice.sendEvent(name: "lastStatusUpdate", value: new Date(), displayed: true)
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
			// childDevice.sendEvent(name: "contact", value: "closed", descriptionText: "status is closed", displayed: true)
			childDevice.sendEvent(name: "contact", value: "closed", displayed: true)
		} else if (message.contains("\$01") || message.contains("\$02")) {
			// childDevice.sendEvent(name: "contact", value: "open", descriptionText: "status is open", displayed: true)
			childDevice.sendEvent(name: "contact", value: "open", displayed: true)
			// childDevice.sendEvent(name: "lastStatusUpdate", value: new Date(), displayed: true)
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
		// childDevice.sendEvent(name: "battery", value: "${rawValue}", descriptionText: "battery is ${rawValue}", displayed: true)
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
				childDevice.setLabel("Front Door"); 
				break; 
			case "680957": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Deck Door"); 
				break; 
			case "6808FE": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("Garage Door"); 
				break; 
			case "6808EB": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("North Patio Door"); 
				break; 
			case "680960": 
				displayDebugLog("Setting Label for: ${firstIDmatch}")
				childDevice.setLabel("South Patio Door"); 
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
		}
		displayDebugLog("New Label: ${childDevice.label}")
	}
}

def installed() {
	// displayDebugLog("Installing")
    // createChildDevices()
    // log.debug "Parent installed"
}

/* configure() runs after installed() */
def configure() {
	// displayInfoLog("Configuring")
	// refresh()
}

/* updated() will run every time user saves preferences */
def updated() {
//	displayInfoLog("Updating preference settings")
}

def rebuildChildDevices() {
    displayDebugLog("Rebuilding Child Devices")
    deleteChildren()
    install()
}

def install() {
	if (!parent) {
		if (initialize() == false) {
			log.error("Cannot Connect")
			return
		}
		displayDebugLog("Creating new child devices")
		for (i in 1..(device.currentValue('numberOfDevices'))) {
			try {
				addChildDevice('hubitat', "Pella Insynctive", "$device.deviceNetworkId-${i.toString().padLeft(3, "0")}", [name: "Pella Insynctive Device", isComponent: true])
			} catch(Exception ex) {
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
		if (childNum > device.currentValue('numberOfDevices')) {
			displayDebugLog("Deleting ${child.deviceNetworkId}")
			deleteChildDevice(child.deviceNetworkId)
		}
    }
}

private def displayDebugLog(message) {
	if (debugLogging)
		log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging)
		log.info "${device.displayName}: ${message}"
}
