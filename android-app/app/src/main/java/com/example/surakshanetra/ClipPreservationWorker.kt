package com.example.surakshanetra

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Makes a private, trimmed copy for review and reports only its hash to the backend; the video is not uploaded here. */
class ClipPreservationWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        val dao = LocalStore.get(applicationContext).clips()
        val request = dao.request(requestId) ?: return Result.success()
        // A retry only resends the hash. Trimming again could produce different bytes and a different hash.
        if (!request.sha256.isNullOrBlank()) {
            return if (request.status == "PRESERVED_LOCAL_WAITING_API") reportHash(request) else Result.success()
        }
        return try {
            val recordingState = applicationContext.getSharedPreferences(LocationService.PREFS, android.content.Context.MODE_PRIVATE)
            // An open MP4 is not safe to trim until CameraX finalizes it when the user stops.
            if (recordingState.getBoolean(LocationService.KEY_RECORDING_ACTIVE, false)) return Result.retry()
            val clips = dao.overlapping(request.requestedFrom, request.requestedTo)
                .filter { File(it.path).isFile }
            if (clips.isEmpty()) {
                dao.markPreserved(requestId, "NO_LOCAL_CLIP", null, null, null)
                return Result.success()
            }
            val destinationFolder = File(applicationContext.filesDir, "footage_requests/${safePath(requestId)}")
            if (!destinationFolder.exists() && !destinationFolder.mkdirs()) error("Could not create private request folder")
            val destination = File(destinationFolder, "preserved.mp4")
            destination.delete()
            val recordingId = clips.first().recordingId.takeIf(String::isNotBlank)
            trimAndJoin(clips, request.requestedFrom, request.requestedTo, destination)
            val hash = sha256(destination)
            dao.markPreserved(requestId, "PRESERVED_LOCAL_WAITING_API", recordingId, destination.absolutePath, hash)
            reportHash(request.copy(recordingId = recordingId, preservedVideoPath = destination.absolutePath, sha256 = hash))
        } catch (error: Exception) {
            dao.markPreserved(requestId, "PRESERVATION_FAILED", null, null, null)
            Result.failure()
        }
    }

    /** The dashboard shows this hash before approval, so retry until the backend has it. Approval also resends it. */
    private fun reportHash(request: FootageRequest): Result {
        if (request.recordingId.isNullOrBlank()) return Result.success()
        val duration = ((request.requestedTo - request.requestedFrom) / 1000L).toInt()
        val sent = runCatching { DemoBackendApi.preserveEvidence(applicationContext, request, duration) }.getOrDefault(false)
        return if (sent || runAttemptCount >= MAX_HASH_REPORT_ATTEMPTS) Result.success() else Result.retry()
    }

    private fun trimAndJoin(clips: List<ClipRecord>, requestedFrom: Long, requestedTo: Long, output: File) {
        var muxer: MediaMuxer? = null
        var outputByKind: Map<String, Int> = emptyMap()
        var expectedFormats: Map<String, MediaFormat> = emptyMap()
        var globalBaseUs = 0L
        var samplesWritten = 0L
        val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
        try {
            for (clip in clips.sortedBy { it.startedAt }) {
                val fromMs = maxOf(requestedFrom, clip.startedAt)
                val toMs = minOf(requestedTo, clip.endedAt)
                if (toMs <= fromMs) continue
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(clip.path)
                    val trackIndices = mutableMapOf<String, Int>()
                    val formats = mutableMapOf<String, MediaFormat>()
                    for (track in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(track)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        val kind = when {
                            mime.startsWith("video/") -> "video"
                            mime.startsWith("audio/") -> "audio"
                            else -> continue
                        }
                        if (kind in trackIndices) continue
                        trackIndices[kind] = track
                        formats[kind] = format
                    }
                    require("video" in trackIndices) { "Recording clip has no video track" }
                    require("audio" in trackIndices) { "Recording clip has no microphone audio track" }
                    if (muxer == null) {
                        val created = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        outputByKind = formats.mapValues { (_, format) -> created.addTrack(format) }
                        expectedFormats = formats
                        created.start()
                        muxer = created
                    } else {
                        // Multiple historical clips (if any) must use compatible CameraX formats.
                        require(formats.keys == expectedFormats.keys) { "A saved clip is missing an audio/video track" }
                        for (kind in formats.keys) {
                            require(formats.getValue(kind).getString(MediaFormat.KEY_MIME) == expectedFormats.getValue(kind).getString(MediaFormat.KEY_MIME)) {
                                "Saved clips use incompatible media formats"
                            }
                        }
                    }

                    val fromUs = (fromMs - clip.startedAt).coerceAtLeast(0L) * 1000L
                    val toUs = (toMs - clip.startedAt).coerceAtLeast(0L) * 1000L
                    if (trackIndices.isEmpty()) continue
                    trackIndices.forEach { (_, track) -> extractor.selectTrack(track) }
                    // Move to a decodable key frame at or after the requested instant. This may
                    // omit a fraction of a second at the boundary, but never includes pre-request footage.
                    extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    while (true) {
                        if (isStopped) throw InterruptedException("Clip preservation was stopped")
                        val sampleTrack = extractor.sampleTrackIndex
                        if (sampleTrack < 0) break
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L || sampleTimeUs > toUs) break
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val kind = trackIndices.entries.firstOrNull { it.value == sampleTrack }?.key
                        val outputTrack = kind?.let(outputByKind::get)
                        if (outputTrack != null && size > 0 && sampleTimeUs >= fromUs) {
                            val info = MediaCodec.BufferInfo().apply {
                                set(0, size, globalBaseUs + sampleTimeUs - fromUs, extractor.sampleFlags)
                            }
                            muxer?.writeSampleData(outputTrack, buffer, info)
                            samplesWritten++
                        }
                        extractor.advance()
                    }
                    globalBaseUs += (toMs - fromMs) * 1000L
                } finally {
                    extractor.release()
                }
            }
            require(samplesWritten > 0 && output.length() > 0L) { "No video samples were available in the requested time range" }
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            runCatching { muxer?.stop() }
            muxer?.release()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safePath(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)

    companion object {
        const val KEY_REQUEST_ID = "footage_request_id"
        private const val MAX_HASH_REPORT_ATTEMPTS = 10
    }
}
