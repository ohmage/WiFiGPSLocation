package org.ohmage.wifigpslocation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.ohmage.logprobe.Log;



/**
 * Starts the  Service at boot time.
 *
 * @author      Hossein Falaki
 */
public class ServiceStarter extends BroadcastReceiver 
{

    private static final String TAG = "ServiceStartup";

    @Override
    public void onReceive(Context context, Intent intent)
    {

        context.startService(new Intent(context, 
        		WiFiGPSLocationService.class));
        Log.v(TAG, "Started WiFiGPSLocationService");

    }

}

