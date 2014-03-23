package org.ohmage.wifigpslocation;

oneway interface ILocationChangedCallback {

	/**
	 * Is called when the service detects that location has changed.
	 *
	 */
	void locationChanged ();
}
