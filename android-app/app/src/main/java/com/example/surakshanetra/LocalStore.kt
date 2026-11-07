package com.example.surakshanetra

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

@Entity(tableName = "clips")
data class ClipRecord(
    @PrimaryKey val path: String,
    val startedAt: Long,
    val endedAt: Long,
    val sizeBytes: Long,
    val recordingId: String = ""
)

@Entity(tableName = "recording_sessions")
data class RecordingSession(
    @PrimaryKey val recordingId: String,
    val vehicleNumber: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMillis: Long,
    val localFileReference: String
)

/** Local mirror of a backend footage request. Videos stay on-device until approval. */
@Entity(tableName = "footage_requests")
data class FootageRequest(
    @PrimaryKey val requestId: String,
    val vehicleNumber: String,
    val requestedFrom: Long,
    val requestedTo: Long,
    val latitude: Double?,
    val longitude: Double?,
    val status: String,
    val recordingId: String?,
    val preservedVideoPath: String?,
    val sha256: String?,
    val declineReason: String?,
    val receivedAt: Long
)

@Entity(tableName = "consent_log")
data class ConsentRecord(
    @PrimaryKey val requestId: String,
    val decidedAt: Long,
    val decision: String,
    val requestedFrom: Long,
    val requestedTo: Long
)

/** A GPS sample waiting to be acknowledged by the configured backend. */
@Entity(
    tableName = "location_pings",
    indices = [Index(value = ["vehicleNumber", "capturedAt"])]
)
data class LocationPing(
    @PrimaryKey val pingId: String,
    val vehicleNumber: String,
    val latitude: Double,
    val longitude: Double,
    val capturedAt: Long,
    val accuracyMeters: Float,
    val recordingId: String? = null
)

@Dao
interface ClipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addClip(clip: ClipRecord)
    @Query("SELECT * FROM clips WHERE startedAt <= :to AND endedAt >= :from ORDER BY startedAt") suspend fun overlapping(from: Long, to: Long): List<ClipRecord>
    @Query("SELECT * FROM clips ORDER BY startedAt DESC") suspend fun allClips(): List<ClipRecord>
    @Query("SELECT * FROM clips ORDER BY startedAt DESC") fun observeClips(): Flow<List<ClipRecord>>
    @Query("DELETE FROM clips WHERE endedAt < :before") suspend fun deleteOlderThan(before: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun recordConsent(record: ConsentRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun beginSession(session: RecordingSession)
    @Query("UPDATE recording_sessions SET endedAt = :endedAt, durationMillis = MAX(0, :endedAt - startedAt) WHERE recordingId = :recordingId")
    suspend fun finishSession(recordingId: String, endedAt: Long)
    @Query("SELECT * FROM recording_sessions WHERE recordingId = :recordingId LIMIT 1")
    suspend fun session(recordingId: String): RecordingSession?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRequest(request: FootageRequest)
    @Query("SELECT * FROM footage_requests ORDER BY receivedAt DESC") fun observeRequests(): Flow<List<FootageRequest>>
    @Query("SELECT * FROM footage_requests WHERE requestId = :requestId LIMIT 1") suspend fun request(requestId: String): FootageRequest?
    @Query("SELECT * FROM footage_requests WHERE status IN ('APPROVED_WAITING_API', 'DECLINED_WAITING_API') ORDER BY receivedAt ASC")
    suspend fun pendingDecisionSync(): List<FootageRequest>
    @Query("UPDATE footage_requests SET status = :status, recordingId = :recordingId, preservedVideoPath = :path, sha256 = :sha256 WHERE requestId = :requestId")
    suspend fun markPreserved(requestId: String, status: String, recordingId: String?, path: String?, sha256: String?)
    @Query("UPDATE footage_requests SET status = :status, declineReason = :reason WHERE requestId = :requestId")
    suspend fun markDecision(requestId: String, status: String, reason: String?)
    @Query("SELECT * FROM footage_requests WHERE receivedAt < :before") suspend fun requestsOlderThan(before: Long): List<FootageRequest>
    @Query("DELETE FROM footage_requests WHERE receivedAt < :before") suspend fun deleteRequestsOlderThan(before: Long)
}

@Dao
interface LocationPingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(ping: LocationPing): Long

    @androidx.room.Transaction
    suspend fun enqueueIfBelowLimit(ping: LocationPing, maxPending: Int): Boolean {
        if (pendingCount() >= maxPending) return false
        return enqueue(ping) != -1L
    }

    @Query("SELECT * FROM location_pings ORDER BY capturedAt ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<LocationPing>

    @Query("SELECT COUNT(*) FROM location_pings")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_pings")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM location_pings WHERE pingId IN (:pingIds)")
    suspend fun deleteAcknowledged(pingIds: List<String>)
}

@Database(entities = [ClipRecord::class, RecordingSession::class, FootageRequest::class, ConsentRecord::class, LocationPing::class], version = 3, exportSchema = false)
abstract class LocalStore : RoomDatabase() {
    abstract fun clips(): ClipDao
    abstract fun locationPings(): LocationPingDao

    companion object {
        @Volatile private var instance: LocalStore? = null
        fun get(context: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalStore::class.java, "surakshanetra.db")
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `location_pings` (
                        `pingId` TEXT NOT NULL,
                        `vehicleNumber` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `capturedAt` INTEGER NOT NULL,
                        `accuracyMeters` REAL NOT NULL,
                        PRIMARY KEY(`pingId`)
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_location_pings_vehicleNumber_capturedAt` " +
                        "ON `location_pings` (`vehicleNumber`, `capturedAt`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clips` ADD COLUMN `recordingId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `location_pings` ADD COLUMN `recordingId` TEXT")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `recording_sessions` (`recordingId` TEXT NOT NULL, `vehicleNumber` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, `localFileReference` TEXT NOT NULL, PRIMARY KEY(`recordingId`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `footage_requests` (`requestId` TEXT NOT NULL, `vehicleNumber` TEXT NOT NULL, `requestedFrom` INTEGER NOT NULL, `requestedTo` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, `status` TEXT NOT NULL, `recordingId` TEXT, `preservedVideoPath` TEXT, `sha256` TEXT, `declineReason` TEXT, `receivedAt` INTEGER NOT NULL, PRIMARY KEY(`requestId`))""")
            }
        }
    }
}

/** Periodic cleanup enforces the 30-day local clip retention window. */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val dao = LocalStore.get(applicationContext).clips()
        dao.allClips().filter { it.endedAt < cutoff }.forEach { File(it.path).delete() }
        dao.deleteOlderThan(cutoff)
        val store = LocalStore.get(applicationContext)
        store.clips().requestsOlderThan(cutoff).forEach { request -> request.preservedVideoPath?.let { File(it).delete() } }
        store.clips().deleteRequestsOlderThan(cutoff)
        return Result.success()
    }
    companion object { const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000 }
}

class SurakshanetraApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        val cleanup = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("clip-retention", ExistingPeriodicWorkPolicy.KEEP, cleanup)
        LocationUploadScheduler.ensurePeriodicRecovery(this)
        // The FCM SDK is active only after the real project config is added. Keep local
        // recording and GPS independent of Firebase setup while that integration is pending.
        runCatching { com.google.firebase.FirebaseApp.initializeApp(this) }.getOrNull()?.let {
            runCatching {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        SecureTokenStore(this).saveFcmToken(token)
                        DemoBackendApi.registerDeviceFromSavedProfile(this)
                    }
            }
        }
    }
}
