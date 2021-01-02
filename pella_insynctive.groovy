/*
Pella Insynctive Telnet Driver

v1.0

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
		// attribute "lastMessageReceipt", "Date"
		attribute "numberOfDevices", "Number"
		attribute "rebootBridge", "ENUM", ["Yes","No"]
	}

    preferences {
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		input name: "ipAddress", type: "text", title: "Pella Bridge Local IP Address", description: "", required: true
		// input name: "retry", type: "number", title: "Connection Retry Interval", description: "Frequency (in minutes) to test and retry connection, if failed"
    }
}

def initialize()
{
	if (ipAddress == null || ipAddress == "") {
		displayDebugLog("Cannot Connect: IP Address is required")
		return false
	}
	def hub = location.hubs[0]
	displayDebugLog("Hub Uptime: ${hub.uptime}") /* (uptime may impact ability to connect to telnet if hubitat was just started up.  May be useful for setting a delay if attempts to connect to telnet too soon) */
	if (hub.uptime < 120)
		pauseExecution(120000) /* wait for 2 minutes if the hub has not been running for at least two minutes -- allows telnet services to load on the hub and also allows Pella bridge time to boot up e.g. if the restart was due to a power outage */
	telnetClose() /* closes telnet if it was already open */
	displayInfoLog("Opening telnet connection")
	telnetConnect("${ipAddress}", 23, null, null)
    displayInfoLog("Establishing connection")
	pauseExecution(3000)
	sendMessage('?POINTCOUNT', null) /* test connection by getting a simple count of the number of devices connected to the Pella Bridge */
	pauseExecution(1000)
	schedule("0 */4 * ? * * *", evalConnection) /* test connection every four minutes.  By default, any such previous schedule is overwritten */
	return true
}

def reconnect()
{
	// state.lastMessageSent = null
	displayInfoLog("Attempting reconnect")
	state.remove("lastMessageSent")
	initialize()
}

/* attempt to reconnect if haven't received any response from the Pella Bridge in more than 10 minutes.  Could also use rebootBridge in a Rule to toggle power to the Pella Bridge based on rebootBridge value, if for example plugged into a smart plug. */
def evalConnection()
{
	sendMessage('?POINTCOUNT', null) /* test connection by getting a simple count of the number of devices connected to the Pella Bridge */
	pauseExecution(1000)
	if (state.lastMessageReceipt) {
		duration = groovy.time.TimeCategory.minus(
			new Date(),
			Date.parse("yyyy-MM-dd'T'HH:mm:ssX", state.lastMessageReceipt)
		)
		// duration = groovy.time.TimeCategory.minus(
			// new Date(),
			// device.currentValue('lastMessageReceipt')
		// )
		displayDebugLog("Minutes since last message received: ${duration.minutes}")
		if (duration.minutes > 10 && duration.minutes <= 20) {
			displayInfoLog("Have not received a message from Pella Bridge in > 10 minutes.")
			reconnect()
		} else if (duration.minutes > 20) {
			displayInfoLog("Have not received a message from Pella Bridge in > 20 minutes.")
			sendEvent(name: "rebootBridge", value: "Yes")
			reconnect()
		}
	}
}

def sendMessage(String message, forPoint) 
{
	displayDebugLog("Sending Telnet message: " + message)	
	state.lastSentForPoint = forPoint
    state.lastMessageSent = message
	sendHubCommand(new hubitat.device.HubAction(message, hubitat.device.Protocol.TELNET))
	displayDebugLog("Telnet message sent")
}

def refresh() 
{
	displayDebugLog("Refreshing")
	def matches = (device.deviceNetworkId =~ /\d+$/) /* grabs digits at end of deviceNetworkId */
	def firstmatch = matches[0]
	if (parent) {
		parent.sendMessage("?POINTSTATUS-${firstmatch}", firstmatch)
		pauseExecution(500) /* wait 0.5 seconds before sending the next command */
		parent.sendMessage("?POINTBATTERYGET-${firstmatch}", firstmatch)
	} else {
		return
	}
}

def parse(String message) {
	displayDebugLog("Message received: ${message}")
	sendEvent(name: "rebootBridge", value: "No", displayed: false)
	// sendEvent(name: "lastMessageReceipt", value: new Date(), displayed: false)
	state.lastMessageReceipt = new Date()

	/* Send message data to appropriate parsing function based on the telnet message received */
	if (message.contains("POINTSTATUS-")) {
		parseUpdate(message)
	} else if (state.lastMessageSent) {
		if (state.lastMessageSent.contains("?POINTSTATUS") && (message =~ /^\W*\$[0-9A-F]{2}\W*$/)) {
			parseStatusResponse(state.lastMessageSent, message)
		} else if (state.lastMessageSent.contains("?POINTBATTERYGET") && (message =~ /^\W*\$[0-9A-F]{2}\W*$/)) {
			parseBatteryResponse(state.lastMessageSent, message)
		} else if (state.lastMessageSent == "?POINTCOUNT" && (message =~ /^\W*\d{3}\W*$/)) {
			parseDevices(message)
		} else if (message.contains("Insynctive Telnet Server")) {
			displayInfoLog("Connection established")
		} else {
			displayDebugLog("Unable to parse message.  lastMessageSent: ${state.lastMessageSent}; message: ${message}")
		}
	} else if (message.contains("Insynctive Telnet Server")) {
		displayInfoLog("Connection established")
	} else if (message =~ /^\W*\d{3}\W*$/) {
		parseDevices(message)
	} else {
		displayDebugLog("Unable to parse message.  message: ${message}")
	}
	state.remove("lastMessageSent")
}

def telnetStatus(String status){
	displayInfoLog("telnetStatus: ${status}")
	sendEvent(name: "rebootBridge", value: "Yes")
}

/* Obtain the number of Insynctive devices connected to the Pella Bridge */
def parseDevices(valueString) {
	displayDebugLog("Device count string to parse = '${valueString}'")
	def rawValue = valueString.toInteger()
	def descText = "Number of devices connected to Pella Bridge is: ${rawValue}"
	displayDebugLog(descText)
	sendEvent(name: 'numberOfDevices', value: rawValue, descriptionText: descText, displayed: false)
}

def parseUpdate(message) {
	displayDebugLog("Status string to parse = '${message}'")
	def matches = ("${message}" =~ /0\d{2}/) /* grabs integers of Pella device number */
	def firstmatch = matches[0]
	displayDebugLog("device to send state to = $device.deviceNetworkId-$firstmatch")
	def childDevice = childDevices.find{it.deviceNetworkId == "$device.deviceNetworkId-$firstmatch"}
	if (childDevice) {
		if (message.contains("\$00")) {
			// childDevice.sendEvent(name: "contact", value: "closed", descriptionText: "status is closed", displayed: true)
			childDevice.sendEvent(name: "contact", value: "closed", displayed: true)
		} else if (message.contains("\$01") || message.contains("\$02")) {
			// childDevice.sendEvent(name: "contact", value: "open", descriptionText: "status is open", displayed: true)
			childDevice.sendEvent(name: "contact", value: "open", displayed: true)
		}
	}
}

def parseStatusResponse(lastMessageSent, message) {
	displayDebugLog("Status string to parse = '${message}'")
	def matches = ("${lastMessageSent}" =~ /0\d{2}/) /* grabs integers of Pella device number */
	def firstmatch = matches[0]
	displayDebugLog("Device to send state to = $device.deviceNetworkId-$firstmatch")
	def childDevice = childDevices.find{it.deviceNetworkId == "$device.deviceNetworkId-$firstmatch"}
	if (childDevice) {
		if (message.contains("\$00")) {
			// childDevice.sendEvent(name: "contact", value: "closed", descriptionText: "status is closed", displayed: true)
			childDevice.sendEvent(name: "contact", value: "closed", displayed: true)
		} else if (message.contains("\$01") || message.contains("\$02")) {
			// childDevice.sendEvent(name: "contact", value: "open", descriptionText: "status is open", displayed: true)
			childDevice.sendEvent(name: "contact", value: "open", displayed: true)
		}
	}
}

def parseBatteryResponse(lastMessageSent, message) {
	displayDebugLog("Battery string to parse = '${message}'")
	def matches = ("${lastMessageSent}" =~ /0\d{2}/) /* grabs integers of Pella device number */
	def firstmatch = matches[0]
	def batterymatches = ("${message}" =~ /\w{2}/) /* grabs reported battery value */
	def firstbatterymatch = batterymatches[0]
	def rawValue = Integer.parseInt(firstbatterymatch,16)
	def descText = "Battery level is ${rawValue}%"
	displayDebugLog("Device to send state to = $device.deviceNetworkId-$firstmatch")
	def childDevice = childDevices.find{it.deviceNetworkId == "$device.deviceNetworkId-$firstmatch"}
	if (childDevice) {
		// childDevice.sendEvent(name: "battery", value: "${rawValue}", descriptionText: "battery is ${rawValue}", displayed: true)
		childDevice.sendEvent(name: "battery", value: "${rawValue}", displayed: true, unit: "%")
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
    log.debug "Rebuilding Child Devices"
    deleteChildren()
    install()
}

def install() {
	if (initialize() == false) {
		displayDebugLog("Cannot Connect")
		return
	}
    displayDebugLog("Creating Child Devices")
    for (i in 1..(device.currentValue('numberOfDevices'))) {
        addChildDevice('hubitat', "Pella Insynctive", "$device.deviceNetworkId-00$i", [name: "Pella Insynctive Device", isComponent: true])
	}
}

def deleteChildren() {
	displayDebugLog("Deleting Child Devices")
	def children = getChildDevices()
    children.each {child->
  		deleteChildDevice(child.deviceNetworkId)
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