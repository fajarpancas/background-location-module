package com.backgroundlocation

import com.weappagent.app.R 
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException
import android.os.Looper
import android.os.PowerManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat

class BackgroundLocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private val client = OkHttpClient()
    private var apiBaseUrl: String? = null
    private var additionalParams: Map<String, Any>? = null
    private lateinit var header: String
    private val failedRequests: MutableList<JSONObject> = mutableListOf() // List to hold failed requests
    private val NOTIFICATION_ID = 12345
    private val CHANNEL_ID = "location_service_channel"
    private lateinit var wakeLock: PowerManager.WakeLock

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isNetworkAvailable()) {
                Log.d("BackgroundLocationService", "Network connected. Retrying failed requests.")
                retryFailedRequests()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Acquire wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BackgroundLocationService::LocationWakeLock"
        )
        wakeLock.acquire()

        Log.d("BackgroundLocationService", "onCreate BLS 1")

        // Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize LocationRequest
        locationRequest = LocationRequest.create().apply {
            interval = 5000 // 5 seconds
            fastestInterval = 5000 // 5 seconds
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY // High accuracy
            smallestDisplacement = 3f // 3 meters
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create and show notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Tracking Active")
            .setContentText("Tracking your location in background")
            .setSmallIcon(R.drawable.ic_notification) // Make sure to create this icon
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        intent?.let {
            apiBaseUrl = it.getStringExtra("baseURL")
            header = it.getStringExtra("header") ?: ""

            val paramsString = it.getStringExtra("params")
            if (paramsString != null) {
                additionalParams = parseParams(paramsString)
            }
        }
        Log.d("BackgroundLocationService", "onStartCommand BLS")

        // Register the network receiver dynamically when the service starts
        registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e("BackgroundLocationService", "Permission error: ${e.message}")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                Log.d("BackgroundLocationService", "Updated location: ${location.latitude}, ${location.longitude}")
                sendLocationToAPI(location.latitude, location.longitude)
            }
        }
    }

    private fun sendLocationToAPI(latitude: Double, longitude: Double) {
        val url = "$apiBaseUrl"

        // Get the current timestamp in seconds (10 digits)
        val timestamp = System.currentTimeMillis() / 1000 // Convert milliseconds to seconds

        // Create the new JSON structure
        val json = JSONObject().apply {
            put("job_tracking", JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("timestamp", timestamp)  // Use 10-digit timestamp (in seconds)
                additionalParams?.forEach { (key, value) ->
                    put(key, value)
                }
            })
        }

        // Check network connectivity
        if (!isNetworkAvailable()) {
            Log.e("BackgroundLocationService", "No internet connection. Storing request for later.")
            failedRequests.add(json) // Save failed request in the list, including timestamp
            return
        }

        // Proceed with making the API request if network is available
        val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), json.toString())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", header) // Set the header for the API request
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("BackgroundLocationService", "Error making API request: ${e.message}")
                // If request fails, save it to the failedRequests list, including timestamp
                failedRequests.add(json)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("BackgroundLocationService", "Successfully sent location to API.")
                } else {
                    Log.e("BackgroundLocationService", "Failed to send location to API. Response: ${response.message}")
                    // If the response is unsuccessful, save the request for later, including timestamp
                    failedRequests.add(json)
                }
            }
        })
    }

    // Check if the network is available
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnected
    }

    // Retry sending failed requests when the network comes back online
    private fun retryFailedRequests() {
        // Iterate over failedRequests and retry each
        for (json in failedRequests) {
            val jobTracking = json.getJSONObject("job_tracking")
            val latitude = jobTracking.getDouble("latitude")
            val longitude = jobTracking.getDouble("longitude")
            val timestamp = jobTracking.getLong("timestamp") // Retain timestamp (in seconds)

            Log.d("BackgroundLocationService", "Retrying request: Latitude=$latitude, Longitude=$longitude, Timestamp=$timestamp")

            // Push each failed request with its timestamp
            sendLocationToAPIWithTimestamp(latitude, longitude, timestamp)
        }

        // After retrying all failed requests, clear the list
        failedRequests.clear()
    }

    // A helper function to send a request with timestamp explicitly
    private fun sendLocationToAPIWithTimestamp(latitude: Double, longitude: Double, timestamp: Long) {
        val url = "$apiBaseUrl"

        // Create the new JSON structure for retrying failed requests
        val json = JSONObject().apply {
            put("job_tracking", JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("timestamp", timestamp)  // Retain timestamp (10-digit) from the failed request
                additionalParams?.forEach { (key, value) ->
                    put(key, value)
                }
            })
        }

        // Make the request again
        val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), json.toString())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", header) // Set the header for the API request
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("BackgroundLocationService", "Error retrying API request: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("BackgroundLocationService", "Successfully retried failed request with timestamp.")
                } else {
                    Log.e("BackgroundLocationService", "Failed to retry request. Response: ${response.message}")
                }
            }
        })
    }

    private fun parseParams(paramsString: String?): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        if (!paramsString.isNullOrEmpty()) {
            try {
                val jsonObject = JSONObject(paramsString)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObject.get(key)
                    map[key] = value
                }
            } catch (e: Exception) {
                Log.e("BackgroundLocationService", "Error parsing params: ${e.message}")
            }
        }
        return map
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
