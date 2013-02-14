/**
  * WiFiGPSLocationService
  *
  * Copyright (C) 2010 Center for Embedded Networked Sensing
  */
package org.ohmage.wifigpslocation;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.database.SQLException;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;

import edu.ucla.cens.systemsens.IAdaptiveApplication;
import edu.ucla.cens.systemsens.IPowerMonitor;

import org.joda.time.DateTimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ohmage.logprobe.Log;
import org.ohmage.logprobe.LogProbe;
import org.ohmage.logprobe.LogProbe.Loglevel;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
//import java.util.TimeZone;


/**
  * WiFiGPSLocation is an Android service to simplify duty-cycling of
  * the GPS receiver when a user is not mobile. The WiFiGPSLocation
  * application runs as an Android Service on the phone. It defines a
  * simple interface using the Android Interface Definition Language
  * (AIDL). All other applications get the last location of the user
  * through this interface from WiFiGPSLocation. Unlike the default
  * Android location API, the location API provided by WiFiGPSLocation
  * is synchronous (i.e., a call to getLocation() is guaranteed to
  * return immediately with the last location of the user.
  *
  * The WiFiGPSLocation constantly queries the GPS receiver to track
  * the location of the user. Upon a getLocation() request, it returns
  * the latest known location of the user. However, it tries to
  * duty-cycle the GPS receiver when it detects the user is not
  * mobile.  WiFiGPSLocation uses the WiFi RF fingerprint to detect
  * when a user is not moving to turn off GPS, and when the user
  * starts moving to turn it back on. This document outlines the
  * design and implementation of WiFiGPSLocation, and provides
  * instructions on how to use it in other applications.
  *
  * @author Hossein Falaki
 */
public class WiFiGPSLocationService 
    extends Service 
    implements LocationListener 
{
    /** Name of the service used logging tag */
    private static final String TAG = "WiFiGPSLocationService";

    /** Version of this service */
    public static final String VER = "2.0";


    /** Name of this application */
    public static final String APP_NAME = "WiFiGPSLocation";


    /** Work unit names */
    public static final String GPS_UNIT_NAME = "gpstime";
    public static final String WIFISCAN_UNIT_NAME = "wifiscan";


    /** Types of messages used by this service */
    private static final int LOC_UPDATE_MSG = 1;


    /** Action strings for alarm events */
    private static final String WIFISCAN_ALARM_ACTION =
        "wifiscan_alarm";
    private static final String CLEANUP_ALARM_ACTION =
        "cleanup_alarm";
    private static final String NETSCAN_ALARM_ACTION =
        "netscan_alarm";


    /** Time unit constants */
    public static final int  ONE_SECOND = 1000;
    public static final int  ONE_MINUTE = 60 * ONE_SECOND;
    public static final int  ONE_HOUR = 60 * ONE_MINUTE; 
    public static final int  ONE_DAY = 24 * ONE_HOUR;

    /** Default timers in milliseconds*/
    private static final int  DEFAULT_WIFI_SCANNING_INTERVAL = 2 * ONE_MINUTE; 
    private static final int  DEFAULT_GPS_SCANNING_INTERVAL = 60 * ONE_SECOND; 
    private static final int  CLEANUP_INTERVAL = ONE_HOUR; 
    private static final int  DEFAULT_POWERCYCLE_HORIZON = ONE_HOUR;


    private static final int  LOC_UPDATE_TIMEOUT = 5 * ONE_SECOND;
    private static final int  CACHE_TIMEOUT = 10 * ONE_DAY; 
    private static final int  EXTENTION_TIME = ONE_HOUR;;


    /** Threshold values */
    private static final int SIGNAL_THRESHOLD = -80;
    private static final double GPS_ACCURACY_THRESHOLD = 10.0;
    private static final int SIGNIFICANCE_THRESHOLD = 3;
    private static final int CRITICAL_THRESHOLD = 15;


    /** Set this to false to stop using network location */
    private boolean USE_NETWORK_LOCATION = true;

    /** Decides if Network-based location should be prefered to GPS */
    private static final boolean PRIORITIZE_NETLOCATION = true;

    /** Provider strings */
    private static final String WIFIGPS_PROVIDER =
        "WiFiGPSLocation:GPS";
    private static final String CACHED_PROVIDER =
        "WiFiGPSLocation:Cached";
    private static final String FAKE_PROVIDER =
        "WiFiGPSLocation:Fake";
    private static final String APPROX_PROVIDER =
        "WiFiGPSLocation:Approx";
    private static final String NET_PROVIDER =
        "WiFiGPSLocation:Network";



    /** Table of connected clients */
    private Hashtable<String, Integer> mClientsTable;


    /** State variable indicating if the services is running or not */
    private boolean mRun;

    /** DB Adaptor */
    private DbAdaptor mDbAdaptor;

    /** State variable indicating if the GPS location is being used */
    private boolean mGPSRunning;

    /** List of callback objects */
    private RemoteCallbackList<ILocationChangedCallback> mCallbacks;

    /** Counter for the callbacks */
    private final int mCallbackCount = 0;

    /** AccelService object */
    /*
    private IAccelService mAccelService;
    private boolean mAccelConnected;
    */


    /** PowerMonitor object */
    private IPowerMonitor mPowerMonitor;
    private boolean mPowerMonitorConnected = false;



    /** WiFi object used for scanning */
    private WifiManager mWifi;


    /** Boolean flag to only look at my own WiFi scans */
    private boolean mWaitingForScan = false;

    /** Boolean flag indicating waiting for network location update */
    private boolean mWaitingForNetLocation = false;

    /** Flag set when there is no visible WiFi AP */
    private boolean mZeroWifi = false;


    /** WiFi wake lock object */
    private WifiLock mWifiLock;


    /** CPU wake lock */
    private PowerManager.WakeLock mCpuLock;

    /** Alarm Manager object */
    private AlarmManager mAlarmManager;


    /** Pending Intent objects */
    private PendingIntent mScanSender;
    private PendingIntent mCleanupSender;
    private PendingIntent mNetScanSender;


    /** Location manager object to receive location objects */
    private LocationManager mLocManager;


    /** GPS manager object */
    private GPSManager mGPSManager;
    private CircularQueue mGPSHistory;

    /** Scan manager object */
    private ScanManager mScanManager;
    private CircularQueue mScanHistory;

    /** MessageDigest object to compute MD5 hash */
    private MessageDigest mDigest;

    /** Map of WiFi scan results to to GPS availability */ 
    private HashMap<String, GPSInfo> mScanCache;

    /** The last known location object */
    private Location mLastKnownLoc;

    /** Temporary location object that is not accurate enough */
    private Location mTempKnownLoc;

    /** Digetst String of the last seen WiFi set*/
    private String mLastWifiSet;

    /** Set of the last scan result  */
    private List<ScanResult> mScanResults;
    private Calendar mWifiScanTime;

    /** Fake location object */
    private Location mFakeLocation;

    /** Scanning interval variable */
    private int mWifiScanInterval;
    private int mGpsScanInterval;






    private final IAdaptiveApplication mAdaptiveControl
        = new IAdaptiveApplication.Stub()
    {
        @Override
        public String getName()
        {
            return APP_NAME;
        }

        @Override
        public List<String> identifyList()
        {
            ArrayList<String> unitNames = new ArrayList(2);

            unitNames.add(GPS_UNIT_NAME);
            unitNames.add(WIFISCAN_UNIT_NAME);

            return unitNames;
        }

        @Override
        public List<Double> getWork()
        {
            ArrayList<Double> totalWork = new ArrayList<Double>();

            double workDone = mGPSManager.getMinutes();
            mGPSHistory.add(workDone);
            totalWork.add(workDone);
            Log.v(TAG, "Added " + workDone + " to GPS history queue: " 
                    + mGPSHistory.getSum());

            workDone = mScanManager.getWork();
            mScanHistory.add(workDone);
            totalWork.add(workDone);
            Log.v(TAG, "Added " + workDone + " to Scan history queue: " 
                    + mScanHistory.getSum());


            return totalWork;
        }

        @Override
        public void setWorkLimit(List  workLimit)
        {
            double gpsLimit = (Double) workLimit.get(0);
            //double scanLimit = (Double) workLimit.get(1);

            mGPSManager.setLimit(gpsLimit);
            //mScanManager.setLimit(scanLimit);
        }


    };
    

    private final IWiFiGPSLocationService.Stub mBinder = new IWiFiGPSLocationService.Stub()
    {

        public static final String FAKE_PROVIDER =  
            WiFiGPSLocationService.FAKE_PROVIDER; 

        public static final String WIFIGPS_PROVIDER =
            WiFiGPSLocationService.WIFIGPS_PROVIDER;

        public static final String APPROX_PROVIDER=
            WiFiGPSLocationService.APPROX_PROVIDER;

        public static final String NET_PROVIDER=
            WiFiGPSLocationService.NET_PROVIDER;


        /**
         *  Returns true if the service is already running.
         *
         * @return      state of the service
         */
        @Override
        public boolean isRunning()
        {
            return mRun;
        }


        /**
         * Returns the current location. 
         * If the service is not active, this call will activate it.
         *
         * @return		the last known location
         */
        @Override
        public synchronized Location getLocation()
        {
            if (!mRun)
            {
                Log.v(TAG, "Reporting null to client.");
                return null;
            }

            if (mLastKnownLoc != null)
            {
                Log.v(TAG, "Reporting " + mLastKnownLoc.getProvider() 
                        + " to client.");
                return mLastKnownLoc;
            }
            else
            {
                Log.v(TAG, "Reporting " + mFakeLocation.getProvider() 
                        + " to client.");
                return mFakeLocation;
            }
        }


        /**
         * Returns a String dump of last visible WiFi access points. 
         * The returned String can be interpreted as a JSON object. Each
         * key is the BSSID of a WiFi AP and the corresponding value is 
         * the signal strength in dBm.
         *
         * @return              JSON object containing visible WiFi APs
         */
        @Override
        public String getWiFiScan()
        {
            JSONObject scanJson, scanResult = new JSONObject();
            JSONArray scanList = new JSONArray();
            SimpleDateFormat sdf = 
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);


            if (mScanResults != null)
            {
                try
                {
                    for (ScanResult result: mScanResults)
                    {
                        scanJson = new JSONObject();
                        scanJson.put("ssid", result.BSSID);
                        scanJson.put("strength", result.level);
                        scanList.put(scanJson);
                    }
                    scanResult.put("scan", scanList);
                    /*
                    scanResult.put("time", 
                            sdf.format(mWifiScanTime.getTime()));
                    */
                    scanResult.put("time",
                            mWifiScanTime.getTimeInMillis());

                    scanResult.put("timezone",
                            DateTimeZone.getDefault().getID());
                            //TimeZone.getDefault().getID());

                }
                catch (JSONException je)
                {
                    Log.e(TAG, "Could not write to JSONObject", je);
                }
            } else {
                Log.w(TAG, "No wifi scan results");
            }

            return scanResult.toString();

        }


        /**
         * Change the GPS sampling interval. 
         * 
         * @param		interval	GPS sampling interval in milliseconds
         * @param       callerName  String name identifying the client
         */
        @Override
        public int suggestInterval(String callerName, int interval)
        {
            if (callerName == null)
                return -1;

            if (interval < 0)
                return -1;
            
            Log.i(TAG, callerName + " suggested " + interval 
                    + " as interval");

            mClientsTable.put(callerName, interval);

            return adjustGPSInterval();
        }


        /**
         * Registers a callback to be called when location changes.
         * Clients are expected to call start() to gaurantee that
         * the service starts operation. However, we add the
         * callerName to the clients table. 
         * If a client needs to get callbacks only when the service
         * runs because of other clients (like the TriggerService),
         * then the client needs to call top right after unregister.
         *
         * @param       callerName  String name identifying the client
         * @param       callback    callback object
         */
        @Override
        public void registerCallback(String callerName, 
                ILocationChangedCallback callback)
        {
            if ((callerName == null) || (callback == null))
                return;


            if (!mClientsTable.containsKey(callerName))
                mClientsTable.put(callerName, 
                        DEFAULT_GPS_SCANNING_INTERVAL);

            mCallbacks.register(callback);
        }

        /**
         * Unregisters the callback.
         *
         * @param       callerName  String name identifying the client
         * @param       callback    callback object
         */
        @Override
        public void unregisterCallback(String callerName,
                ILocationChangedCallback callback)
        {
            if (callerName == null)
                return;


            if (mClientsTable.containsKey(callerName))
                if (callback != null)
                    mCallbacks.unregister(callback);

        }

        /**
         * Puts the WiFiGPSLocationService in an "inactive" mode.
         * Cancels are pending scans and sets the Run flag to stop.
         * The Android service is still running and can receive calls,
         * but it does not perform any energy consuming tasks.
         * 
         * @param       callerName  String name identifying the client
         */
        @Override
        public void stop(String callerName)
        {
            if (callerName == null)
                return;

            if (mClientsTable.containsKey(callerName))
                mClientsTable.remove(callerName);
            else
                return;

            Log.v(TAG, "Received a stop() call from " + callerName);

            int clientCount = mClientsTable.size();

            if ((clientCount == 0) && (mRun))
            {
                Log.v(TAG, "Stoping operations");
                // Cancel pending alarms
                mAlarmManager.cancel(mScanSender);
                mAlarmManager.cancel(mCleanupSender);
                mAlarmManager.cancel(mNetScanSender);
                cleanCache();

 
                if (mWifiLock.isHeld())
                    mWifiLock.release();
                mRun = false; 
            }
            else
            {
                Log.v(TAG, "Continuing operations");
                adjustGPSInterval();

            }

        }

        /**
         * Starts the WiFiGPSLocationService.
         * Schedules a new scan message and sets the Run flag to true.
         *
         * @param       callerName  String name identifying the client
         */
        @Override
        public void start(String callerName)
        {			
            if (callerName == null)
                return;

            Log.v(TAG, "Received a start() call from " + callerName);

            if (!mClientsTable.containsKey(callerName))
                mClientsTable.put(callerName, 
                        DEFAULT_GPS_SCANNING_INTERVAL);

            if (!mRun)
            {
                mRun = true;
                setupWiFi();
                long now = SystemClock.elapsedRealtime();

                if (PRIORITIZE_NETLOCATION)
                {
                    // Fire the netloc scanning alarm
                    mAlarmManager.setRepeating(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            now, mGpsScanInterval, mNetScanSender);
                }

                // Fire the WiFi scanning alarm
                mAlarmManager.setRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        now, mWifiScanInterval, mScanSender);


                // Fire the cleanup alarm
                mAlarmManager.setRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        now + CLEANUP_INTERVAL, 
                        CLEANUP_INTERVAL, mCleanupSender);


                // Start running GPS to get current location ASAP
                if (!PRIORITIZE_NETLOCATION)
                    mGPSManager.start();
            }
        }


    };



	
    /**
     * Broadcast receiver for WiFi scan updates.
     * An object of this class has been passed to the system through 
     * registerReceiver. 
     *
     */
    private final BroadcastReceiver mWifiScanReceiver = new BroadcastReceiver() 
    {
        @Override
        public void onReceive(Context context, Intent intent) 
        {

            String action = intent.getAction();


            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            {
                if (!mWaitingForScan)
                    return;

                mWaitingForScan = false;
                mScanResults = mWifi.getScanResults();
                mWifiScanTime = Calendar.getInstance();
                
                Log.v(TAG, "WiFi scan found " + mScanResults.size() 
                        + " APs");

                List<String> sResult = new ArrayList<String>();
                double levelSum = 0.0;

                for (ScanResult result : mScanResults)
                {
                    //It seems APs with higher signal strengths are
                    //more stable. Iterate over all APs and find the
                    //mean signal strength. Then ignore 'weak' ones.
                    Log.v(TAG, result.BSSID + " (" 
                            + result.level + "dBm)");

                    levelSum += result.level;
                }

                if (mScanResults.size() > 0)
                {
                    mZeroWifi = false;
                    double newThreshold = levelSum/mScanResults.size();
                    Log.v(TAG, " Using " + newThreshold
                            + " as signal strength threshold.");
                    
                    for (ScanResult result : mScanResults)
                        if (result.level >= newThreshold)
                            sResult.add(result.BSSID);

                }
                else
                {
                    Log.v(TAG, "No visible WiFi AP");
                    mZeroWifi = true;

                }

                Log.v(TAG, "Filtered " 
                    + (mScanResults.size() - sResult.size()) 
                    + " APs.");



                Collections.sort(sResult);
                updateLocation(sResult);
            }
            else if (action.equals( 
                        WifiManager.WIFI_STATE_CHANGED_ACTION)) 
            {
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 0);
                if (wifiState == WifiManager.WIFI_STATE_DISABLED)
                {
                    Log.v(TAG, "User disabeled Wifi." +
                        " Setting up WiFi again");
                        setupWiFi();
                }
            }


            if (mCpuLock.isHeld())
                mCpuLock.release();

        }
    };


    private final LocationListener mNetLocListener = new LocationListener()
    {
        @Override
        public synchronized void onLocationChanged(Location location)
        {

           if (mWaitingForNetLocation)
            {
                Log.v(TAG, "Got a network loc update while waiting" 
                        + " for it.");
                mLastKnownLoc = location;
                mLastKnownLoc.setProvider(NET_PROVIDER);
                mWaitingForNetLocation = false;
            }
            else
            {
                Log.v(TAG, "Did not expect a network loc update!");
            }

            mLocManager.removeUpdates(mNetLocListener);

        }

        @Override
        public void onProviderDisabled(String provider)
        {
            Log.v(TAG, provider + " was disabled.");
            USE_NETWORK_LOCATION = false;
        }

        @Override
        public void onProviderEnabled(String provider)
        {
            Log.v(TAG, provider + " was enabled.");
            USE_NETWORK_LOCATION = true;
        }

        @Override
        public void onStatusChanged(String provider, int status,
                Bundle extra)
        {

            Log.v(TAG, provider + "status changed to " + status);

        }


    };


    @Override
    public synchronized void onLocationChanged(Location location) 
    {
        double accuracy =  location.getAccuracy();
        Log.v(TAG, "Received location update. Accuracy: " + accuracy);

        String provider = location.getProvider();

        if ( accuracy < GPS_ACCURACY_THRESHOLD)
        {
            mHandler.removeMessages(LOC_UPDATE_MSG);
            mLastKnownLoc = location;

            if (mScanCache.containsKey(mLastWifiSet))
            {
                if (!mScanCache.get(mLastWifiSet).known)
                {
                    Log.v(TAG, "Updating the record: " + 
                    cacheEntry(mLastWifiSet));
                    mScanCache.get(mLastWifiSet).known = true;
                    mScanCache.get(mLastWifiSet).loc = location;
                    mScanCache.get(mLastWifiSet).loc.setProvider(
                            CACHED_PROVIDER);
                    mLastKnownLoc = location;
                    mLastKnownLoc.setProvider(WIFIGPS_PROVIDER);
                }
                else
                {
                    Log.v(TAG, "There is a valid record. "  
                        + "but still updating " 
                        + cacheEntry(mLastWifiSet) );
                    mScanCache.get(mLastWifiSet).loc = location;
                    mScanCache.get(mLastWifiSet).loc.setProvider(
                            CACHED_PROVIDER);

                    mLastKnownLoc = location;				
                    mLastKnownLoc.setProvider(WIFIGPS_PROVIDER);
                }
            }
            else
            {
                mLastKnownLoc = location;				
                mLastKnownLoc.setProvider(WIFIGPS_PROVIDER);
            }
        }
        else
        {
            mTempKnownLoc = location;
            mTempKnownLoc.setProvider(WIFIGPS_PROVIDER);
            mHandler.removeMessages(LOC_UPDATE_MSG);
            mHandler.sendMessageAtTime(
                mHandler.obtainMessage(LOC_UPDATE_MSG),
                SystemClock.uptimeMillis() + LOC_UPDATE_TIMEOUT);

        }
    }
	
    @Override
    public void onStatusChanged(String provider, 
            int status, Bundle extras) 
    {
        Log.v(TAG, provider + "status changed to " + status);

    }
        
    @Override
    public void onProviderEnabled(String provider) 
    {
        Log.v(TAG, provider + " was enabled.");

    }

    @Override
    public void onProviderDisabled(String provider) 
    {
        Log.v(TAG, provider + " was disabled.");

    }
    
    private synchronized void updateLocation(List<String> wifiSet)
    {
        // If the system is configrued to prioritize Network-based
        // location to GPS, we will turn GPS on when there is no WiFi
        // AP and return
        if (PRIORITIZE_NETLOCATION)
        {
            if (mZeroWifi)
                mGPSManager.start();
            else
                mGPSManager.stop();

            return;
        }

        // Otherwise (we prefer GPS to Network-based location) we
        // continue with the following logic

        long curTime = System.currentTimeMillis();
        GPSInfo record;
        byte[] byteKey = mDigest.digest(wifiSet.toString().getBytes());
        String key = new String(byteKey);


        Log.v(TAG, "Updating cache for: " + wifiSet.toString());




        // First check if the current WiFi signature is different
        // from the last visited Wifi set. If they are different,
        // call each registered client
        if ((!mLastWifiSet.equals(key)) || (wifiSet.size() == 0) )
        {
            final int N = mCallbacks.beginBroadcast();
            if (N > 0)
            {
                Log.v(TAG, "Calling registered clients.");

                ILocationChangedCallback callBack;

                for (int i = 0; i < N; i++)
                {
                    callBack = mCallbacks.getBroadcastItem(i);

                    try
                    {
                        callBack.locationChanged();
                    }
                    catch (RemoteException re)
                    {
                        Log.e(TAG, "Exception when calling AccelService", re);
                    }
                }

                }
                mCallbacks.finishBroadcast();

            }


            if (mScanCache.containsKey(mLastWifiSet))
            {
                if (!mScanCache.get(mLastWifiSet).known)
                {
                    Log.v(TAG, "Concluded no lock for last WiFi set");
                    mScanCache.get(mLastWifiSet).known = true;
                }
            }


            // First thing, if the set is "empty", I am at a location with
            // no WiFi coverage. We default to GPS scanning in such
            // situations. So turn on GPS and return
            if (wifiSet.size() == 0)
            {
                Log.v(TAG, "No WiFi AP found.");
                mGPSManager.start();
                return;
            }


            Log.v(TAG, "Current cache has " 
                    + mScanCache.size() 
                    + " entries.");

            if (mScanCache.containsKey(key))
            {
                record = mScanCache.get(key);
                record.increment();
                record.resetTime(curTime);
                Log.v(TAG, "Found a record: " 
                        + record.toString());

                if (record.count <= SIGNIFICANCE_THRESHOLD)
                {
                    Log.v(TAG, "Not significant yet. "
                           + "Still need to run GPS.");

                    mGPSManager.start();

                }
                else if (record.count > SIGNIFICANCE_THRESHOLD)
                {

                    Log.v(TAG, "Significant record.");

                    // update the time stamp of the 
                    // last known GPS location
                    if (record.loc != null)
                    {
                        // If the matching record has a 
                        // location object use that
                        Log.v(TAG, "Using known location.");
                        mLastKnownLoc = record.loc;
                    }
                    // We will use network location and if
                    // that is not available or disabled 
                    // last observed location 
                    // as an approximation if there is one
                    else
                    { 
                        if (USE_NETWORK_LOCATION)
                        {
                            Log.v(TAG, "Getting network location");
                            try
                            {
                                mLocManager.requestLocationUpdates(
                                    LocationManager.NETWORK_PROVIDER, 
                                    0L, 0.0f,
                                    mNetLocListener);
                                mWaitingForNetLocation = true;
                            }
                            catch (Exception e)
                            {
                                Log.e(TAG, "Could not register" 
                                        + "for network locatin update",
                                        e);
                            }


                        }

                        if (mLastKnownLoc != null && 
                                !mLastKnownLoc.getProvider().equals(
                                    FAKE_PROVIDER))
                        {
                            Log.v(TAG, "Using approx location.");
                            mLastKnownLoc.setProvider(APPROX_PROVIDER);
                            mLastKnownLoc.setSpeed(0);
                        }
                        else
                        {
                            Log.w(TAG, "Using fake location.");
                            mLastKnownLoc = mFakeLocation;
                        }
                    }

                    // We do not update the 'fix' time 
                    // of the location to allow the client
                    // application have a measure of how stale
                    // the location object is.
                    /* mLastKnownLoc.setTime(curTime); */


                    mGPSManager.stop();
                }
            }
            else // The cache does not contain the wifi signature
            {
                Log.v(TAG, "New WiFi set.");

                // Schedule a GPS scan
                record = new GPSInfo(false, curTime);
                mScanCache.put(key, record);
                Log.v(TAG, "Created new cache entry: " 
                        + record.toString());

                mGPSManager.start();

            }

        mLastWifiSet = key;

    }
    
    /**
     * Message handler object.
     */
    private final Handler mHandler = new Handler()
    {
        @Override
        public void handleMessage(Message msg)
        {
            if (msg.what == LOC_UPDATE_MSG)
            {
                Log.v(TAG, "Dealing with inaccurate location. "
                        + "Accuracy: " 
                        + mTempKnownLoc.getAccuracy());

                mLastKnownLoc = mTempKnownLoc;

                if (mScanCache.containsKey(mLastWifiSet))
                {
                    if (!mScanCache.get(mLastWifiSet).known)
                    {
                        Log.v(TAG, "Updating the record: " + 
                                cacheEntry(mLastWifiSet));
                        mScanCache.get(mLastWifiSet).known = true;
                        mScanCache.get(mLastWifiSet).loc =
                        mTempKnownLoc;
                        mScanCache.get(mLastWifiSet).loc.setProvider(
                                    CACHED_PROVIDER);

                    }
                }
                else
                {
                    Log.v(TAG, "No familar WiFi signature");
                }
            }

        }
    };

    
    /** 
     * Finds records in the cache that have been timed out.
     */
    private synchronized void cleanCache()
    {
    	long curTime = System.currentTimeMillis();
    	GPSInfo record, removed;
    	long cacheTime;
    	int count;
    	long timeout;
    	
    	HashSet<String> toBeDeleted = new HashSet<String>();
    	
    	Log.v(TAG, "Cleaning up the cache.");
    	Log.v(TAG, "Current cache has " + mScanCache.size() + " entries.");
    	
    	
    	for (String key: mScanCache.keySet())
    	{
    		record = mScanCache.get(key);
    		cacheTime = record.time;
    		count = record.count;
    		timeout = curTime - (cacheTime + count*EXTENTION_TIME);
    		Log.v(TAG, "Checking " + cacheEntry(key));

    		if (count < SIGNIFICANCE_THRESHOLD)
    		{
    			if (curTime - cacheTime > ONE_HOUR)
    			{
    				Log.v(TAG, "Marking transient record for deletion: " 
                            + record.toString());
    				toBeDeleted.add(key);
    			}
    		} 
    		else if ((count < CRITICAL_THRESHOLD) 
                    &&(timeout > CACHE_TIMEOUT ))
			{
				Log.v(TAG, "Marking stale record for deletion: " + 
                        record.toString());
				// The cache entry has timed out. Remove it!
				toBeDeleted.add(key);
			}
		}

    	try
    	{
	    	for (String delKey : toBeDeleted)
	    	{
	    		Log.v(TAG, "Deleting " + cacheEntry(delKey));
	    		removed = mScanCache.remove(delKey);
	    	}
    	}
    	catch (ConcurrentModificationException cme)
    	{
    		Log.e(TAG, "Exception while cleaning cache.", cme);
    	}


        try
        {
            mDbAdaptor.open();
            mDbAdaptor.syncDb(mScanCache);
            mDbAdaptor.close();
        }
        catch(SQLException e)
        {
            Log.e(TAG, "Could not syncronize database.", e);
        }


        if (mCpuLock.isHeld())
            mCpuLock.release();

    }


    /*
    private ServiceConnection mAccelServiceConnection 
            = new ServiceConnection() 
    {
        public void onServiceConnected(ComponentName className,
                IBinder service)
        {
            mAccelService = IAccelService.Stub.asInterface(service);
            mAccelConnected = true;

        }

        public void onServiceDisconnected(ComponentName className)
        {
            mAccelService = null;
            mAccelConnected = false;
        }

    };
    */
    

    private final ServiceConnection mPowerMonitorConnection 
            = new ServiceConnection() 
    {

        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service)
        {
            mPowerMonitor = IPowerMonitor.Stub.asInterface(service);
            try
            {
              mPowerMonitor.register(mAdaptiveControl, 
                      DEFAULT_POWERCYCLE_HORIZON);
            }
            catch (RemoteException re)
            {
                Log.e(TAG, "Could not register AdaptivePower object.",
                        re);
            }

            mPowerMonitorConnected = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName className)
        {

            try
            {
                mPowerMonitor.unregister(mAdaptiveControl);
            }
            catch (RemoteException re)
            {
                Log.e(TAG, "Could not unregister AdaptivePower object.",
                        re);
            }

            mPowerMonitor = null;
            mPowerMonitorConnected = false;

        }



    };



	
    @Override
    public IBinder onBind(Intent intent)
    {
        if (IWiFiGPSLocationService.class.getName().equals( 
                    intent.getAction())) 
        {
            return mBinder;
        }

        return null;		
    }

    @Override
    public void onStart(Intent intent, int startId)
    {
        if (!mPowerMonitorConnected)
        {
            Log.v(TAG, "Rebinding to PowerMonitor");
            bindService(new Intent(IPowerMonitor.class.getName()),
                    mPowerMonitorConnection, Context.BIND_AUTO_CREATE);
        }


        LogProbe.setLevel(true, Loglevel.VERBOSE);
        LogProbe.get(this);


        if (intent != null)
        {
            String action = intent.getAction();

            if (action != null)
            {

                Log.v(TAG, "Received action: " + action);
                if (action.equals(WIFISCAN_ALARM_ACTION))
                {
                    if (!mCpuLock.isHeld())
                        mCpuLock.acquire(); // Released by WiFi receiver
                    mScanManager.scan();
                }
                else if (action.equals(CLEANUP_ALARM_ACTION))
                {
                    if (!mCpuLock.isHeld())
                        mCpuLock.acquire(); // Released by cleanCache()
                    cleanCache();
                }
                /* For PRIORITIZE_NETLOCATION version */
                else if (action.equals(NETSCAN_ALARM_ACTION) 
                        && PRIORITIZE_NETLOCATION)
                {
                    try
                    {
                        mLocManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, 
                            0L, 0.0f,
                            mNetLocListener);
                        mWaitingForNetLocation = true;
                    }
                    catch (Exception e)
                    {
                        Log.e(TAG, "Could not register" 
                                + "for network locatin update",
                                e);
                    }
                }

            }

        }

        //super.onStart(intent, startId);
        //Log.i(TAG, "onStart");

    }
	
    @Override
    public void onCreate() 
    {
        super.onCreate();

        LogProbe.setLevel(true, Loglevel.VERBOSE);
        LogProbe.get(this);

        bindService(new Intent(IPowerMonitor.class.getName()),
                mPowerMonitorConnection, Context.BIND_AUTO_CREATE);

        // Hacking the interval of SystemSens. This needs to be fixed
        mGPSHistory = new
            CircularQueue(DEFAULT_POWERCYCLE_HORIZON/(2*ONE_MINUTE));

        mScanHistory = new
            CircularQueue(DEFAULT_POWERCYCLE_HORIZON/(2*ONE_MINUTE));




        mClientsTable = new Hashtable<String, Integer>();

        mCallbacks = new RemoteCallbackList<ILocationChangedCallback>();

        mDbAdaptor = new DbAdaptor(this);

        mGPSManager = new GPSManager();
        mScanManager = new ScanManager();


		//Initialize the scan cache 
		mScanCache = new HashMap<String, GPSInfo>();


        Log.v(TAG, "Reading last saved cache");
        readDb();


        
        mFakeLocation = new Location(FAKE_PROVIDER);
        mFakeLocation.setLatitude(Double.NaN);
        mFakeLocation.setLongitude(Double.NaN);
        mFakeLocation.setSpeed(Float.NaN);
        mFakeLocation.setTime(System.currentTimeMillis());

        mLastKnownLoc = mFakeLocation;
        
        mLastWifiSet = " ";
        
        resetToDefault();
        
        try
        {
     	   mDigest = java.security.MessageDigest.getInstance("MD5");
        }
        catch (NoSuchAlgorithmException nae)
        {
     	   Log.e(TAG, "Exception", nae);
        }


        PowerManager pm = (PowerManager) this.getSystemService(
                Context.POWER_SERVICE);
        mCpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                APP_NAME);
        mCpuLock.setReferenceCounted(false);


        mWifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mLocManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        USE_NETWORK_LOCATION = mLocManager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER);
        
        setupWiFi();
        
        //Register to receive WiFi scans 
        registerReceiver(mWifiScanReceiver, new IntentFilter( 
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        //Register to receive WiFi state changes
        registerReceiver(mWifiScanReceiver, new IntentFilter( 
                    WifiManager.WIFI_STATE_CHANGED_ACTION));


        // Set up alarms for repeating events.
        mAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        // WiFi scan Intent objects
        Intent scanAlarmIntent = new Intent(WiFiGPSLocationService.this,
                WiFiGPSLocationService.class);
        scanAlarmIntent.setAction(WIFISCAN_ALARM_ACTION);
        mScanSender = PendingIntent.getService(
                WiFiGPSLocationService.this, 0, scanAlarmIntent, 0);


        // Cache cleanup Intent objects
        Intent cleanupAlarmIntent = new Intent(WiFiGPSLocationService.this,
                WiFiGPSLocationService.class);
        cleanupAlarmIntent.setAction(CLEANUP_ALARM_ACTION);
        mCleanupSender = PendingIntent.getService(
                WiFiGPSLocationService.this, 0, cleanupAlarmIntent, 0);


        /* For PRIORITIZE_NETLOCATION version */
        Intent netScanAlarmIntent = new Intent(WiFiGPSLocationService.this,
                WiFiGPSLocationService.class);
        netScanAlarmIntent.setAction(NETSCAN_ALARM_ACTION);
        mNetScanSender = PendingIntent.getService(
                WiFiGPSLocationService.this, 0, netScanAlarmIntent, 0);




		
    }
    
    @Override
    public void onDestroy()
    {
        try
        {
            mDbAdaptor.open();
            mDbAdaptor.syncDb(mScanCache);
            mDbAdaptor.close();
        }
        catch(SQLException e)
        {
            Log.e(TAG, "Could not syncronize database.", e);
        }


    	// Remove pending WiFi scan messages
    	mHandler.removeMessages(LOC_UPDATE_MSG);


        // Cancel the pending alarms
        mAlarmManager.cancel(mScanSender);
        mAlarmManager.cancel(mCleanupSender);
        mAlarmManager.cancel(mNetScanSender);
    	
    	// Cancel location update registration 
		mLocManager.removeUpdates(this);
		mGPSRunning = false;
        mGPSManager.stop();
		
		// Cancel WiFi scan registration
		unregisterReceiver(mWifiScanReceiver);
        LogProbe.close(this);
    }

    private boolean readDb()
    {
        String sign;
        int count, hasloc;
        double lat, lon, acc, loctime;
        long time;
        GPSInfo gpsInfo;
        Location curLoc;
        String provider;


        try
        {
            mDbAdaptor.open();
        }
        catch(SQLException e)
        {
            Log.e(TAG, "Could not open database connection", e);
        }



        Cursor c = mDbAdaptor.fetchAllEntries();

        int timeIndex = c.getColumnIndex(DbAdaptor.KEY_TIME);
        int countIndex = c.getColumnIndex(DbAdaptor.KEY_COUNT);
        int signIndex = c.getColumnIndex(DbAdaptor.KEY_SIGNATURE);
        int latIndex = c.getColumnIndex(DbAdaptor.KEY_LAT);
        int lonIndex = c.getColumnIndex(DbAdaptor.KEY_LON);
        int accIndex = c.getColumnIndex(DbAdaptor.KEY_ACC);
        int locTimeIndex = c.getColumnIndex(DbAdaptor.KEY_LOCTIME);
        int providerIndex = c.getColumnIndex(DbAdaptor.KEY_PROVIDER);
        int haslocIndex = c.getColumnIndex(DbAdaptor.KEY_HASLOC);

        int dbSize = c.getCount();

        Log.v(TAG, "Found " + dbSize + " entries in database.");

        c.moveToFirst();

        for (int i = 0; i < dbSize; i++)
        {

            try
            {
                time = c.getInt(timeIndex);
                count = c.getInt(countIndex);
                sign = c.getString(signIndex);
                hasloc = c.getInt(haslocIndex);




                gpsInfo = new GPSInfo(true, time);


                if (hasloc == DbAdaptor.YES)
                {
                    lat = c.getDouble(latIndex);
                    lon = c.getDouble(lonIndex);
                    acc = c.getDouble(accIndex);
                    loctime = c.getDouble(locTimeIndex);
                    provider = c.getString(providerIndex);

                    curLoc = new Location(provider);
                    curLoc.setLatitude(lat);
                    curLoc.setLongitude(lon);
                    curLoc.setTime((long)loctime);
                    curLoc.setAccuracy((float)acc);
                    gpsInfo.loc = curLoc;
                }
                else
                {
                    Log.v(TAG, "Entry with no location.");
                }

                gpsInfo.count = count;
                mScanCache.put(sign, gpsInfo);
                Log.v(TAG, "Synced " + gpsInfo.toString());

            }
            catch (Exception dbe)
            {
                Log.e(TAG, "Error reading a db entry", dbe);
            }
            c.moveToNext();
        }
        c.close();

        try
        {
            mDbAdaptor.close();
        }
        catch(SQLException e)
        {
            Log.e(TAG, "Could not close database connection", e);
        }



        return true;
    }
    

    
    private void setupWiFi()
    {
        // Check if WiFi is enabled
        if (!mWifi.isWifiEnabled())
            mWifi.setWifiEnabled(true);
        
        if (mWifiLock == null)
        {
            mWifiLock = mWifi.createWifiLock(
                    WifiManager.WIFI_MODE_SCAN_ONLY, TAG);
            mWifiLock.setReferenceCounted(false);

            if (!mWifiLock.isHeld())
                mWifiLock.acquire();
        }
        else
        {
            if (!mWifiLock.isHeld())
                mWifiLock.acquire();
        }
    }
    
    /*
     * Sets all operational parameters to their default values
     */
    private void resetToDefault()
    {
        mGpsScanInterval = DEFAULT_GPS_SCANNING_INTERVAL;
        mWifiScanInterval = DEFAULT_WIFI_SCANNING_INTERVAL;

    }



    private int adjustGPSInterval()
    {
        int curInterval = Integer.MAX_VALUE;

        for (Integer interval : mClientsTable.values())
        {
            if (interval < curInterval)
                curInterval = interval;
        }

         if (curInterval != Integer.MAX_VALUE)
             mGpsScanInterval = curInterval;
         else
            mGpsScanInterval = DEFAULT_GPS_SCANNING_INTERVAL;

         Log.v(TAG, "Scanning interval adjusted to " +
                 mGpsScanInterval);

         return mGpsScanInterval;
    }


    private String cacheEntry(String wifiSet)
    {
        String res = "";

        if (mScanCache.containsKey(wifiSet))
        {
            res +=  mScanCache.get(wifiSet);
        }
        else
        {
            res += "null";
        }

        return res;
    }

    /*
    class GPSManager
    {
        private double mLimit = Double.NaN;

        private double mTotal;
        private double mCurTotal;
        private double mStart;
        private double mCount;


        public GPSManager()
        {
            mTotal = 0.0;
            mCurTotal = 0.0;
            mCount = 0.0;
        }

        public void setLimit(double workLimit)
        {
            mLimit = workLimit * ONE_MINUTE;

            //double used = mCurTotal * DEFAULT_POWERCYCLE_HORIZON/ONE_MINUTE;
            double used = mGPSHistory.getSum();
            Log.i(TAG, "Estimated usage per horizon: " + used);
            Log.i(TAG, "Current limit per horizon: " + workLimit);
            mCurTotal = 0.0;
        }


        public boolean start()
        {
            Log.i(TAG, "curTotal:" + mCurTotal + ", mLimit: " + mLimit);
            if (!mGPSRunning)
            {
                if ( Double.isNaN(mLimit) || (mCurTotal < mLimit) )
                {
                    mStart = SystemClock.elapsedRealtime();
                    Log.i(TAG, "Starting GPS.");
                    mLocManager.requestLocationUpdates( 
                            LocationManager.GPS_PROVIDER, 
                            mGpsScanInterval, 0,
                            WiFiGPSLocationService.this);

                    WiFiGPSLocationService.this.mHandler.sendMessageAtTime( 
                            mHandler.obtainMessage(LOC_UPDATE_MSG), 
                            SystemClock.uptimeMillis() 
                            + WiFiGPSLocationService.this.GPS_LOCK_TIMEOUT); 


                    mGPSRunning = true;
                    mCount += 1;
                    return mGPSRunning;
                }
                else 
                {
                    Log.i(TAG, "No budget to start GPS.");
                    return mGPSRunning;
                }
            }
            else
            {
                if ( !Double.isNaN(mLimit) && (mCurTotal > mLimit) )
                {

                    Log.i(TAG, "Ran out of GPS budget.");
                    mLocManager.removeUpdates(WiFiGPSLocationService.this);
                    Log.i(TAG, "Stopping GPS.");
                    mGPSRunning = false;
                    return mGPSRunning;
                }
                else
                {
                    Log.i(TAG, "Continue scanning GPS.");
                    return mGPSRunning;
                }
            }


        }

        public void stop()
        {
            if (mGPSRunning)
            {
                mLocManager.removeUpdates(WiFiGPSLocationService.this);
                Log.i(TAG, "Stopping GPS.");

                double current =  SystemClock.elapsedRealtime();
                mTotal += (current - mStart);
                mCurTotal += (current - mStart);
                mGPSRunning = false;


                Log.i(TAG, "Updated mCurTotal to " + mCurTotal);
            }

        }

        public double getMinutes()
        {
            double res;

            if (mGPSRunning)
            {
                double current =  SystemClock.elapsedRealtime();
                mTotal += (current - mStart);
                mCurTotal += (current - mStart);
                mStart = current;

            }

            return  mTotal/ONE_MINUTE;
        }

        public double getCount()
        {
            return mCount;

        }
    }
    */




    /** Old GPSManager */
    class GPSManager
    {
        private double mLimit = Double.NaN;

        private double mTotal;
        private double mCurTotal;
        private double mStart;


        public GPSManager()
        {
            mTotal = 0.0;
            mCurTotal = 0.0;
        }

        public void setLimit(double workLimit)
        {
            mLimit = workLimit * ONE_MINUTE;
            mCurTotal = 0.0;
            double used = mGPSHistory.getSum();
            Log.v(TAG, "Estimated usage per horizon: " + used);
            Log.v(TAG, "Current limit per horizon: " + workLimit);
        }


        public boolean start()
        {
            Log.v(TAG, "curTotal:" + mCurTotal 
                    + ", mLimit: " + mLimit);

            if (!mGPSRunning)
            {
                if ( Double.isNaN(mLimit) || (mCurTotal < mLimit) )
                {
                    mStart = SystemClock.elapsedRealtime();
                    Log.v(TAG, "Starting GPS.");
                    mLocManager.requestLocationUpdates( 
                            LocationManager.GPS_PROVIDER, 
                            mGpsScanInterval, 0,
                            WiFiGPSLocationService.this);
                    mGPSRunning = true;
                    return mGPSRunning;
                }
                else 
                {
                    Log.v(TAG, "No budget to start GPS.");
                    mGPSRunning = false;
                    return mGPSRunning;
                }
            }
            else
            {
                double current =  SystemClock.elapsedRealtime();
                mTotal += (current - mStart);
                mCurTotal += (current - mStart);
                mStart = current;


                if ( !Double.isNaN(mLimit) && (mCurTotal > mLimit) )
                {

                    Log.v(TAG, "Ran out of GPS budget.");
                    mLocManager.removeUpdates(WiFiGPSLocationService.this);
                    Log.v(TAG, "Stopping GPS.");
                    mGPSRunning = false;
                    return mGPSRunning;
                }
                else
                {
                    Log.v(TAG, "Continue scanning GPS.");
                    mGPSRunning = true;
                    return mGPSRunning;
                }
            }


        }

        public void stop()
        {
            if (mGPSRunning)
            {
                mLocManager.removeUpdates(WiFiGPSLocationService.this);
                Log.v(TAG, "Stopping GPS.");

                double current =  SystemClock.elapsedRealtime();
                mTotal += (current - mStart);
                mCurTotal += (current - mStart);
                mGPSRunning = false;

                Log.v(TAG, "Updated mCurTotal to " + mCurTotal);
            }

        }

        public double getMinutes()
        {
            double res;

            if (mGPSRunning)
            {
                double current =  SystemClock.elapsedRealtime();
                mTotal += (current - mStart);
                mCurTotal += (current - mStart);
                mStart = current;
            }

            return mTotal/ONE_MINUTE;
        }
    }
    


    class ScanManager
    {
        double mTotal = 0.0;
        double mCurTotal = 0.0;

        double mLimit = Double.NaN;

        public boolean scan()
        {

            if ( Double.isNaN(mLimit) || (mCurTotal < mLimit) )
            {
                mWifi.startScan();
                mWaitingForScan = true;

                Log.v(TAG, "Starting WiFi scan.");
                mTotal += 1.0;
                mCurTotal += 1.0;
                return true;
            }

            if ( !Double.isNaN(mLimit) && (mCurTotal >= mLimit) )
            {
                Log.v(TAG, "No budget to scan WiFi.");
                return false;
            }
            
            return false;

        }

        public void setLimit(double workLimit)
        {
            mLimit = workLimit;
            mCurTotal = 0.0;
        }

        public double getWork()
        {
            return mTotal;
        }
    }

        
    
}


