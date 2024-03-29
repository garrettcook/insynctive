# insynctive
Hubitat Driver for Pella Insynctive Bridge

Uses telnet to connect to the Pella Insynctive Bridge.  At this time it only reads sensor status (contact sensors and deadbolt sensors); no control is offered for Pella blinds (I don't have any to test).

To use, first load the driver onto your Hubitat.  Then create a virtual device, give it a name (I call mine "Pella Insynctive Bridge"), and select Pella Insynctive as the Driver, and Save.  In the Device Preferences enter the IP address of the Pella Bridge on your network (is highly recommended to assign a static IP to the bridge on your router) and Save, then click the Install button.  The driver will then attempt to connect to the Bridge, obtain a count of the number of Insynctive devices connected to the Bridge, and create a Child Device for each which you can give a unique identifying Label.

As your Insynctive devices send open/close updates to the Bridge these should update the state variables in the child devices.  In my experience the network accessibility of the Bridge has a tendency to become unresponsive after a period of time and needs to be reset.  The driver will attempt to check connection status to the bridge by sending a command to the Bridge just to get a response (sort of like a 'ping').  If more than 10 minutes go by without receiving a command back from the Bridge, the driver will attempt to reset the Hubitat side of the telnet connection.  If 20 minutes pass still without receiving a command back from the Bridge, the driver will continue attempts to reset the connection and will also set a "rebootBridge" attribute to "Yes" to indicate you should reboot the Bridge and/or other network devices.  You could use this attribute as a trigger in Rule Manager to for example toggle a smart plug power source to restart the bridge.

Sometimes when adding new devices, the Bridge reorders the Point IDs.  You should set the serial numbers and labels for your devices into the parseIDResponse function so that after refresh it will re-label them properly.  (Mine remain in the file as an example.)

Future enhancements: obtain battery level from Bridge upon sensor status change, and select sensor type ("contact sensor", "deadbolt sensor", etc)
