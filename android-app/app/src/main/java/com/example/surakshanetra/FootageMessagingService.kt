package com.example.surakshanetra

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FCM adapter for the demo's canonical data keys: requestId, vehicleNumber,
 * startTimestamp, endTimestamp, latitude and longitude. Common snake_case aliases
 * are accepted until the backend notification contract is finalized.
 */
class FootageMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SecureTokenStore(this).saveFcmToken(token)
        DemoBackendApi.registerDeviceFromSavedProfile(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val requestId = data.firstNonBlank("requestId", "request_id", "id") ?: return
        val prefs = getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE)
        val registeredVehicle = prefs.getString(KEY_VEHICLE, "").orEmpty()
        val vehicle = data.firstNonBlank("vehicleNumber", "vehicle_number", "vehicle") ?: registeredVehicle
        if (vehicle.isNotBlank() && registeredVehicle.isNotBlank() && normalizeVehicle(vehicle) != normalizeVehicle(registeredVehicle)) return
        val from = data.timestamp("startTimestamp", "start_timestamp", "startTime", "start_time", "t_from") ?: return
        val to = data.timestamp("endTimestamp", "end_timestamp", "endTime", "end_time", "t_to") ?: return
        if (to <= from) return
        val request = FootageRequest(
            requestId = requestId,
            vehicleNumber = normalizeVehicle(vehicle),
            requestedFrom = from,
            requestedTo = to,
            latitude = data.decimal("latitude", "lat"),
            longitude = data.decimal("longitude", "lon", "lng"),
            status = "PRESERVING",
            recordingId = null,
            preservedVideoPath = null,
            sha256 = null,
            declineReason = null,
            receivedAt = System.currentTimeMillis()
        )
        // FCM invokes this service on a worker thread. Room's small local write is completed
        // before the OS can reclaim the service; the actual clip work is handed to WorkManager.
        runBlocking {
            val dao = LocalStore.get(this@FootageMessagingService).clips()
            if (dao.request(requestId) == null) dao.saveRequest(request)
        }
        val input = Data.Builder().putString(ClipPreservationWorker.KEY_REQUEST_ID, requestId).build()
        val preserve = OneTimeWorkRequestBuilder<ClipPreservationWorker>().setInputData(input).build()
        WorkManager.getInstance(this).enqueueUniqueWork("preserve-$requestId", ExistingWorkPolicy.KEEP, preserve)
        showNotification(requestId, message.notification?.title, message.notification?.body, from, to)
    }

    private fun showNotification(requestId: String, title: String?, body: String?, from: Long, to: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Footage requests", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REQUEST_ID, requestId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, requestId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val range = "${Date(from)} – ${Date(to)}"
        manager.notify(
            requestId.hashCode(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title?.takeIf(String::isNotBlank) ?: "Footage request for ${getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE).getString(KEY_VEHICLE, "your vehicle")}")
                .setContentText(body?.takeIf(String::isNotBlank) ?: "Requested time: $range. Tap to review; nothing is uploaded automatically.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body?.takeIf(String::isNotBlank) ?: "Requested time: $range. Review the request and approve or decline in Surakshanetra."))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
        )
    }

    private fun Map<String, String>.firstNonBlank(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.trim()?.takeIf(String::isNotEmpty) }

    private fun Map<String, String>.decimal(vararg keys: String): Double? =
        firstNonBlank(*keys)?.toDoubleOrNull()?.takeIf(Double::isFinite)

    private fun Map<String, String>.timestamp(vararg keys: String): Long? {
        val raw = firstNonBlank(*keys) ?: return null
        raw.toLongOrNull()?.let { return if (it in 1..99_999_999_999L) it * 1000L else it }
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (format in formats) {
            val parsed = runCatching { SimpleDateFormat(format, Locale.US).parse(raw)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private companion object { const val CHANNEL_ID = "footage-requests" }
}
