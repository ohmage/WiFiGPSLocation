WiFiGPSLocation
===============

WiFiGPSLocation is an Android service to simplify duty-cycling of the
GPS receiver when a user is not mobile. The WiFiGPSLocation
application runs as an Android Service on the phone. It defines a
simple interface using the Android Interface Definition Language
(AIDL). All other applications get the last location of the user
through this interface from WiFiGPSLocation. Unlike the default
Android location API, the location API provided by WiFiGPSLocation is
synchronous (i.e., a call to getLocation() is guaranteed to return
immediately with the last location of the user.

The WiFiGPSLocation constantly queries the GPS receiver to track the
location of the user. Upon a getLocation() request, it returns the
latest known location of the user. However, it tries to duty-cycle the
GPS receiver when it detects the user is not mobile. WiFiGPSLocation
uses the WiFi RF fingerprint to detect when a user is not moving to
turn off GPS, and when the user starts moving to turn it back on. This
document outlines the design and implementation of WiFiGPSLocation,
and provides instructions on how to use it in other applications.


Dependencies
============

* [AccelService](https://github.com/ohmage/AccelService)
* [LogProbe](https://github.com/cens/LogProbe)
* [SystemSensLib](https://github.com/ohmage/SystemSensLib)
