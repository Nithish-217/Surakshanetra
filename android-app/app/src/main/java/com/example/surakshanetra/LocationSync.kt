package com.example.surakshanetra

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Non-secret endpoint settings. The access token is stored separately using encrypted preferences. */
class LocationSyncSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun baseUrl(): String? = preferences.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)

    fun baseUrlDraft(): String? = preferences.getString(KEY_BASE_URL_DRAFT, null)

    fun saveBaseUrlDraft(value: String) {
        preferences.edit().putString(KEY_BASE_URL_DRAFT, value).apply()
    }

    fun clearBaseUrlDraft() {
        preferences.edit().remove(KEY_BASE_URL_DRAFT).apply()
    }

    fun saveBaseUrl(value: String) {
        preferences.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
    }

    companion object {
        private const val PREFERENCES = "location_sync_settings"
        private const val KEY_BASE_URL = "api_base_url"
        private const val KEY_BASE_URL_DRAFT = "api_base_url_draft"

        fun isValidBackendBaseUrl(value: String): Boolean {
            val uri = Uri.parse(value.trim())
            val host = uri.host?.lowercase().orEmpty()
            if (host.isBlank()) return false
            if (uri.scheme.equals("https", ignoreCase = true)) return true
            if (!uri.scheme.equals("http", ignoreCase = true)) return false
            if (host == "localhost" || host == "10.0.2.2") return true
            val octets = host.split('.').mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 169 && octets[1] == 254)
        }
    }
}

object LocationQueuePolicy {
    // At one fix per 30 seconds this holds about 41 days of continuous offline tracking.
    const val MAX_PENDING_PINGS = 120_000
}

object LocationUploadScheduler {
    private const val UNIQUE_WORK_NAME = "surakshanetra-location-upload"
    private const val RECOVERY_WORK_NAME = "surakshanetra-location-upload-recovery"

    /** Keep one connected worker pending. It drains the queue in bounded batches. */
    fun enqueue(context: Context) {
        val request = request()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueIfConfigured(context: Context) {
        val endpoint = LocationSyncSettings(context).baseUrl()
        val token = SecureTokenStore(context).readAccessToken()
        if (!endpoint.isNullOrBlank() && !token.isNullOrBlank()) enqueue(context)
    }

    fun syncNow(context: Context) {
        val request = request()
        WorkManager.getInstance(context.applicationContext)
            // A server outage can leave the unique upload work in exponential backoff.
            // A manual save/sync should replace that stale retry so it tries immediately;
            // unacknowledged Room rows remain queued until the backend confirms them.
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodicRecovery(context: Context) {
        val request = PeriodicWorkRequestBuilder<LocationUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(RECOVERY_WORK_NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun enqueueNextBatch(context: Context) {
        val request = request()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun request() = OneTimeWorkRequestBuilder<LocationUploadWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
}

/** Uploads queued location-only pings. Clips are never read by this worker. */
class LocationUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val baseUrl = LocationSyncSettings(applicationContext).baseUrl()
            ?: return Result.success() // Keep the queue until an endpoint is configured.
        val accessToken = SecureTokenStore(applicationContext).readAccessToken()
            ?.takeIf(String::isNotBlank)
            ?: return Result.success() // Keep the queue until a backend token is configured.
        if (!LocationSyncSettings.isValidBackendBaseUrl(baseUrl)) return Result.failure()

        val dao = LocalStore.get(applicationContext).locationPings()
        return try {
            repeat(MAX_BATCHES_PER_RUN) {
                if (isStopped) return Result.retry()
                val batch = dao.pending(BATCH_SIZE)
                if (batch.isEmpty()) return Result.success()

                val acceptedIds = postBatch(baseUrl, accessToken, batch)
                if (acceptedIds.isEmpty()) return Result.retry()
                dao.deleteAcknowledged(acceptedIds.toList())
                if (dao.pendingCount() < LocationQueuePolicy.MAX_PENDING_PINGS) {
                    applicationContext.getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(LocationService.KEY_LOCATION_QUEUE_FULL, false).apply()
                }

                // A partial acknowledgement is safe: acknowledged IDs are deleted and the
                // remaining rows are retried with the same idempotency keys.
                if (acceptedIds.size < batch.size) return Result.retry()
            }

            if (dao.pendingCount() > 0) LocationUploadScheduler.enqueueNextBatch(applicationContext)
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun postBatch(baseUrl: String, accessToken: String, pings: List<LocationPing>): Set<String> {
        val endpoint = URL("${baseUrl.trimEnd('/')}/v1/location-pings/batch")
        val ownerName = applicationContext.getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OWNER_NAME, "Vehicle owner").orEmpty()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val payload = JSONObject().put("pings", JSONArray().apply {
                pings.forEach { ping ->
                    put(
                        JSONObject()
                            .put("pingId", ping.pingId)
                            .put("deviceId", SecureTokenStore(applicationContext).deviceId())
                            .put("vehicleNumber", ping.vehicleNumber)
                            .put("ownerName", ownerName)
                            .put("recordingId", ping.recordingId)
                            .put("recordedAt", formatUtc(ping.capturedAt))
                            .put(
                                "location",
                                JSONObject()
                                    .put("type", "Point")
                                    .put("coordinates", JSONArray().put(ping.longitude).put(ping.latitude))
                            )
                            .put("accuracyMeters", ping.accuracyMeters.toDouble())
                    )
                }
            })

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("Location upload failed with HTTP $responseCode")
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val accepted = JSONObject(responseText).optJSONArray("acceptedPingIds")
                ?: throw IOException("Backend response did not include acceptedPingIds")
            return buildSet {
                for (index in 0 until accepted.length()) {
                    val pingId = accepted.optString(index)
                    if (pingId.isNotBlank()) add(pingId)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun formatUtc(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(timestamp))

    private companion object {
        const val BATCH_SIZE = 500
        const val MAX_BATCHES_PER_RUN = 10
    }
}
