package com.example.surakshanetra

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/** Explicitly enabled location foreground service. Each GPS fix is queued for backend sync. */
class LocationService : LifecycleService() {
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val vehicleNumber = prefs.getString(KEY_VEHICLE, null)?.takeIf(String::isNotBlank) ?: return
            val capturedAt = location.time
            prefs.edit()
                .putString(KEY_LAT, location.latitude.toString())
                .putString(KEY_LON, location.longitude.toString())
                .putLong(KEY_TIME, capturedAt)
                .putFloat(KEY_ACCURACY, location.accuracy)
                .apply()
            val ping = LocationPing(
                pingId = UUID.randomUUID().toString(),
                vehicleNumber = vehicleNumber,
                latitude = location.latitude,
                longitude = location.longitude,
                capturedAt = capturedAt,
                accuracyMeters = location.accuracy,
                recordingId = prefs.getString(KEY_ACTIVE_RECORDING_ID, null)
            )
            ioScope.launch {
                val queued = LocalStore.get(this@LocationService).locationPings()
                    .enqueueIfBelowLimit(ping, LocationQueuePolicy.MAX_PENDING_PINGS)
                prefs.edit().putBoolean(KEY_LOCATION_QUEUE_FULL, !queued).apply()
                if (queued) LocationUploadScheduler.enqueueIfConfigured(this@LocationService)
                // A pending request is picked up on the same 30-second cadence as GPS, even
                // when Firebase project credentials are not present in this demo build.
                FootageRequestPolling.poll(this@LocationService, vehicleNumber)
            }
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!hasLocationPermission()) { stopSelf(); return START_NOT_STICKY }
        createChannel()
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification)
        prefs.edit().putBoolean(KEY_LOCATION_ACTIVE, true).putInt(KEY_LOCATION_PID, android.os.Process.myPid()).apply()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(30_000L)
            .build()
        try {
            locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val hasServer = !LocationSyncSettings(this).baseUrl().isNullOrBlank() &&
            !SecureTokenStore(this).readAccessToken().isNullOrBlank()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Surakshanetra location is active")
            .setContentText(if (hasServer) "GPS points sync when internet is available." else "GPS points are queued on this phone until sync is configured.")
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    override fun onDestroy() {
        locationClient.removeLocationUpdates(callback)
        prefs.edit().putBoolean(KEY_LOCATION_ACTIVE, false).putInt(KEY_LOCATION_PID, 0).apply()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "surakshanetra_state"
        const val KEY_LOCATION_ACTIVE = "location_active"
        const val KEY_LOCATION_PID = "location_pid"
        const val KEY_LAT = "last_latitude"
        const val KEY_LON = "last_longitude"
        const val KEY_TIME = "last_location_time"
        const val KEY_ACCURACY = "last_location_accuracy"
        const val KEY_LOCATION_QUEUE_FULL = "location_queue_full"
        const val KEY_LOCATION_USER_DISABLED = "location_user_disabled"
        const val KEY_GPS_PERMISSION_REQUESTED = "gps_permission_requested"
        const val KEY_RECORDING_ACTIVE = "recording_active"
        const val KEY_ACTIVE_RECORDING_ID = "active_recording_id"
        const val KEY_RECORDING_PID = "recording_pid"
        const val KEY_RECORDING_ERROR = "recording_error"
        private const val CHANNEL_ID = "location"
        private const val NOTIFICATION_ID = 22
    }
}
