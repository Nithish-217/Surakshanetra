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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared HTTP adapter for the demo FastAPI service. MongoDB credentials never enter the APK. */
object DemoBackendApi {
    private data class Config(val baseUrl: String, val apiKey: String)

    private fun config(context: Context): Config? {
        val baseUrl = LocationSyncSettings(context).baseUrl()?.trimEnd('/') ?: return null
        val apiKey = SecureTokenStore(context).readAccessToken()?.takeIf(String::isNotBlank) ?: return null
        if (!LocationSyncSettings.isValidBackendBaseUrl(baseUrl)) return null
        return Config(baseUrl, apiKey)
    }

    fun isConfigured(context: Context): Boolean = config(context) != null

    /** Confirms the configured URL exposes the location API and accepts this vehicle token. */
    fun checkLocationConnection(baseUrl: String, accessToken: String): String {
        val endpoint = URL("${baseUrl.trimEnd('/')}/v1/location-pings/batch")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write("{\"pings\":[]}".toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                // FastAPI/Pydantic rejects this intentionally empty batch with 422; the
                // Node starter returns 400. Both responses prove the route and key worked.
                (code == 400 && response.contains("pings must contain", ignoreCase = true)) ||
                    (code == 422 && response.contains("pings", ignoreCase = true)) ->
                    return "Backend reachable; vehicle token accepted."
                code == 401 -> throw IOException("Backend reachable, but this access token is not recognized for a vehicle.")
                code == 404 -> throw IOException("Server reachable, but it does not expose /v1/location-pings/batch.")
                else -> throw IOException("Backend check failed with HTTP $code: ${response.take(180)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun getJson(context: Context, path: String): JSONObject? = requestJson(context, "GET", path, null)

    fun postJson(context: Context, path: String, body: JSONObject): JSONObject? = requestJson(context, "POST", path, body)

    /** Register the current install and its FCM destination when both are configured. */
    fun registerDeviceFromSavedProfile(context: Context) {
        val appContext = context.applicationContext
        val fcmToken = SecureTokenStore(appContext).readFcmToken()?.takeIf(String::isNotBlank) ?: return
        if (config(appContext) == null) return
        val prefs = appContext.getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE)
        val vehicle = prefs.getString(KEY_VEHICLE, null)?.trim()?.takeIf(String::isNotBlank) ?: return
        val owner = prefs.getString(KEY_OWNER_NAME, null)?.trim()?.takeIf(String::isNotBlank) ?: "Vehicle owner"
        val body = JSONObject()
            .put("device_id", SecureTokenStore(appContext).deviceId())
            .put("vehicle_reg_number", vehicle)
            .put("owner_name", owner)
            .put("fcm_token", fcmToken)
        Thread {
            runCatching { postJson(appContext, "/api/v1/devices/register", body) }
        }.start()
    }

    private fun requestJson(context: Context, method: String, path: String, body: JSONObject?): JSONObject? {
        val config = config(context) ?: return null
        val connection = (URL(config.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Backend returned HTTP $code: ${response.take(250)}")
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    fun preserveEvidence(
        context: Context,
        request: FootageRequest,
        durationSeconds: Int
    ): Boolean {
        val recordingId = request.recordingId?.takeIf(String::isNotBlank) ?: return false
        val body = JSONObject()
            .put("recording_id", recordingId)
            .put("sha256", request.sha256 ?: return false)
            .put("duration_seconds", durationSeconds.coerceAtLeast(1))
            .put("trimmed_at", utcNow())
        return postJson(context, "/api/v1/footage-requests/${request.requestId}/preserve", body) != null
    }

    fun decline(context: Context, requestId: String, reason: String): Boolean {
        val body = JSONObject().put("justification", reason)
        return postJson(context, "/api/v1/footage-requests/$requestId/decline", body) != null
    }

    fun approveAndUpload(context: Context, request: FootageRequest, video: File): Boolean {
        val config = config(context) ?: return false
        val boundary = "SurakshaNethra-${System.currentTimeMillis()}"
        val connection = (URL("${config.baseUrl}/api/v1/footage-requests/${request.requestId}/approve").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.buffered().use { output ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"video_file\"; filename=\"${video.name}\"\r\nContent-Type: video/mp4\r\n\r\n".toByteArray(Charsets.UTF_8))
                video.inputStream().use { input -> input.copyTo(output) }
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Backend upload failed with HTTP $code: ${response.take(250)}")
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())
}

/** Polls pending requests while the persistent location service is running, as an FCM-free demo fallback. */
object FootageRequestPolling {
    suspend fun poll(context: Context, vehicleNumber: String) {
        if (!DemoBackendApi.isConfigured(context) || vehicleNumber.isBlank()) return
        val dao = LocalStore.get(context).clips()
        syncDecisions(context, dao)
        val encodedVehicle = URLEncoder.encode(vehicleNumber, Charsets.UTF_8.name())
        val payload = runCatching {
            DemoBackendApi.getJson(context, "/api/v1/footage-requests/pending?vehicle_reg_number=$encodedVehicle")
        }.getOrNull() ?: return
        val incoming = payload.optJSONArray("requests") ?: return
        for (index in 0 until incoming.length()) {
            val item = incoming.optJSONObject(index) ?: continue
            val requestId = item.optString("requestId").takeIf(String::isNotBlank) ?: continue
            if (dao.request(requestId) != null) continue
            val from = parseTimestamp(item.opt("startTimestamp")) ?: continue
            val to = parseTimestamp(item.opt("endTimestamp")) ?: continue
            if (to <= from) continue
            val latitude = item.optionalDouble("latitude")
            val longitude = item.optionalDouble("longitude")
            val request = FootageRequest(
                requestId = requestId,
                vehicleNumber = item.optString("vehicleNumber", vehicleNumber),
                requestedFrom = from,
                requestedTo = to,
                latitude = latitude,
                longitude = longitude,
                status = "PRESERVING",
                recordingId = null,
                preservedVideoPath = null,
                sha256 = null,
                declineReason = null,
                receivedAt = parseTimestamp(item.opt("receivedAt")) ?: System.currentTimeMillis()
            )
            dao.saveRequest(request)
            val input = Data.Builder().putString(ClipPreservationWorker.KEY_REQUEST_ID, requestId).build()
            val work = OneTimeWorkRequestBuilder<ClipPreservationWorker>().setInputData(input).build()
            WorkManager.getInstance(context).enqueueUniqueWork("preserve-$requestId", ExistingWorkPolicy.KEEP, work)
            showRequestNotification(context, request)
        }
    }

    private suspend fun syncDecisions(context: Context, dao: ClipDao) {
        for (request in dao.pendingDecisionSync()) {
            runCatching {
                when (request.status) {
                    "DECLINED_WAITING_API" -> {
                        if (DemoBackendApi.decline(context, request.requestId, request.declineReason.orEmpty())) {
                            dao.markDecision(request.requestId, "DECLINED", request.declineReason)
                        }
                    }
                    "APPROVED_WAITING_API" -> {
                        val video = request.preservedVideoPath?.let(::File)
                        if (video != null && video.isFile) {
                            val duration = ((request.requestedTo - request.requestedFrom) / 1000L).toInt().coerceAtLeast(1)
                            if (DemoBackendApi.preserveEvidence(context, request, duration) && DemoBackendApi.approveAndUpload(context, request, video)) {
                                dao.markDecision(request.requestId, "APPROVED", null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showRequestNotification(context: Context, request: FootageRequest) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("footage-requests", "Footage requests", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REQUEST_ID, request.requestId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, request.requestId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            request.requestId.hashCode(),
            NotificationCompat.Builder(context, "footage-requests")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Footage request for ${request.vehicleNumber}")
                .setContentText("A nearby incident request is ready for your review.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
        )
    }

    private fun JSONObject.optionalDouble(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf(Double::isFinite)

    private fun parseTimestamp(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is Number) return value.toLong().let { if (it in 1..99_999_999_999L) it * 1000L else it }
        val text = value.toString()
        text.toLongOrNull()?.let { return if (it in 1..99_999_999_999L) it * 1000L else it }
        // FastAPI serializes UTC datetimes with a colonized offset (+00:00).
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX"
        )
        for (format in formats) {
            val parsed = runCatching {
                SimpleDateFormat(format, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
