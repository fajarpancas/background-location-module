package com.backgroundlocation

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

class BackgroundLocationModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private var trackingActive: Boolean = false  // Flag to track if tracking is already active

    override fun getName(): String {
        return "BackgroundLocationModule"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @ReactMethod
    fun startTracking(baseURL: String, header: String) {
        // Prevent multiple calls to startTracking if it's already running

        val intent = Intent(reactContext, BackgroundLocationService::class.java).apply {
            putExtra("baseURL", baseURL)
            putExtra("header", header)
            // Add this to ensure service starts in foreground
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Start as foreground service to prevent system kills
        reactContext.startForegroundService(intent)
        
        trackingActive = true
        Log.d("BackgroundLocationModule", "Tracking started successfully.")
    }

    @ReactMethod
    fun stopTracking() {
        if (!trackingActive) {
            Log.d("BackgroundLocationModule", "Tracking is not active. Nothing to stop.")
            return
        }

        val intent = Intent(reactContext, BackgroundLocationService::class.java)
        reactContext.stopService(intent)

        // Reset the tracking active flag
        trackingActive = false
        Log.d("BackgroundLocationModule", "Tracking stopped successfully.")
    }

    // Method to send location updates to JavaScript
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        Log.d("BackgroundLocationModule", "send location:$latitude,$longitude")
        val params = mapOf("latitude" to latitude, "longitude" to longitude)
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("LocationUpdated", params)
    }
}
