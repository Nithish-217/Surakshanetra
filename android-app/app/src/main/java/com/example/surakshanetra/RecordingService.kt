package com.example.surakshanetra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color as GraphicsColor
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Binder
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.CameraEffect
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import androidx.arch.core.util.Function
import androidx.camera.effects.Frame
import androidx.core.util.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** CameraX dashcam recorder. One user-selected recording is saved as a single file with audio and a burned-in clock. */
class RecordingService : LifecycleService() {
    inner class LocalBinder : Binder() { fun service(): RecordingService = this@RecordingService }
    private val binder = LocalBinder()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewUseCase: Preview? = null
    private var previewView: PreviewView? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var timestampEffect: OverlayEffect? = null
    private var recordingId: String = ""
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = Executor { command -> main.post(command) }
    private val prefs by lazy { getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun attachPreview(view: PreviewView?) {
        previewView = view
        previewUseCase?.setSurfaceProvider(view?.surfaceProvider)
    }

    fun stopRecording() {
        val recording = activeRecording
        if (recording == null) finishStop() else recording.stop()
    }

    private fun finishStop() {
        val endedAt = System.currentTimeMillis()
        val finishedId = recordingId.ifBlank { prefs.getString(LocationService.KEY_ACTIVE_RECORDING_ID, "").orEmpty() }
        prefs.edit().putBoolean(LocationService.KEY_RECORDING_ACTIVE, false)
            .putInt(LocationService.KEY_RECORDING_PID, 0)
            .remove(LocationService.KEY_ACTIVE_RECORDING_ID)
            .apply()
        if (finishedId.isNotBlank()) ioScope.launch {
            LocalStore.get(applicationContext).clips().finishSession(finishedId, endedAt)
        }
        cameraProvider?.unbindAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        recordingId = intent?.getStringExtra(EXTRA_RECORDING_ID)
            ?: prefs.getString(LocationService.KEY_ACTIVE_RECORDING_ID, "").orEmpty()
        ensureChannel()
        val notification = recordingNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(
            NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        else startForeground(NOTIFICATION_ID, notification)
        startCamera()
        return START_NOT_STICKY
    }

    private fun startCamera(attempt: Int = 0) {
        if (previewView == null && attempt < 15) {
            main.postDelayed({ startCamera(attempt + 1) }, 200)
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                // Camera hardware (including emulator virtual cameras) may not advertise SD.
                // Ask for SD, then let CameraX choose the closest supported quality.
                val quality = QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))
                val recorder = Recorder.Builder().setQualitySelector(quality).build()
                val capture = VideoCapture.withOutput(recorder)
                val preview = Preview.Builder().build()
                previewUseCase = preview
                previewView?.let { preview.surfaceProvider = it.surfaceProvider }
                videoCapture = capture
                provider.unbindAll()
                val effect = OverlayEffect(
                    CameraEffect.VIDEO_CAPTURE, 4, main,
                    Consumer<Throwable> { error ->
                        prefs.edit().putString(LocationService.KEY_RECORDING_ERROR, "Timestamp overlay failed: ${error.message}").apply()
                    }
                )
                effect.setOnDrawListener(object : Function<Frame, Boolean> {
                    override fun apply(frame: Frame): Boolean {
                    val canvas = frame.overlayCanvas
                    val width = canvas.width.toFloat()
                    val height = canvas.height.toFloat()
                    val stamp = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()).toString()
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = GraphicsColor.WHITE
                        textSize = (width * 0.035f).coerceIn(28f, 64f)
                        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    }
                    val pad = textPaint.textSize * 0.48f
                    val boxHeight = textPaint.textSize + pad * 1.4f
                    val boxWidth = textPaint.measureText(stamp) + pad * 2
                    val left = width * 0.035f
                    val top = height - boxHeight - height * 0.04f
                    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
                    canvas.drawRoundRect(RectF(left, top, left + boxWidth, top + boxHeight), 8f, 8f, background)
                    canvas.drawText(stamp, left + pad, top + boxHeight - pad * 0.48f, textPaint)
                    return true
                    }
                })
                timestampEffect = effect
                val group = UseCaseGroup.Builder().addUseCase(capture).addEffect(effect).apply {
                    if (previewView != null) addUseCase(preview)
                }.build()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                startFileRecording()
            } catch (error: Exception) {
                prefs.edit().putBoolean(LocationService.KEY_RECORDING_ACTIVE, false)
                    .putInt(LocationService.KEY_RECORDING_PID, 0)
                    .putString(LocationService.KEY_RECORDING_ERROR, error.message ?: error.javaClass.simpleName).apply()
                stopSelf()
            }
        }, mainExecutor)
    }

    private fun startFileRecording() {
        val capture = videoCapture ?: return
        val folder = getExternalFilesDir("Surakshanetra/clips") ?: filesDir.resolve("clips")
        if (!folder.exists()) folder.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(folder, "clip_$stamp.mp4")
        val startedAt = System.currentTimeMillis()
        val options = FileOutputOptions.Builder(output).build()
        activeRecording = capture.output.prepareRecording(this, options)
            .withAudioEnabled()
            .start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Start) {
                prefs.edit().putBoolean(LocationService.KEY_RECORDING_ACTIVE, true)
                    .putInt(LocationService.KEY_RECORDING_PID, android.os.Process.myPid())
                    .remove(LocationService.KEY_RECORDING_ERROR).apply()
            }
            if (event is VideoRecordEvent.Finalize) {
                activeRecording = null
                if (!event.hasError()) ioScope.launch {
                    LocalStore.get(applicationContext).clips().addClip(
                        ClipRecord(output.absolutePath, startedAt, System.currentTimeMillis(), output.length(), recordingId)
                    )
                }
                if (event.hasError()) {
                    prefs.edit().putBoolean(LocationService.KEY_RECORDING_ACTIVE, false)
                        .putString(LocationService.KEY_RECORDING_ERROR, "Camera recording failed (code ${event.error})").apply()
                }
                // CameraX finalizes this file when the user stops or when a recording error occurs.
                finishStop()
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Dashcam recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun recordingNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.presence_video_online)
        .setContentTitle("Surakshanetra recording active")
        .setContentText("Video and microphone audio are saved on this device")
        .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()

    override fun onDestroy() {
        val unfinishedId = prefs.getString(LocationService.KEY_ACTIVE_RECORDING_ID, "").orEmpty()
        prefs.edit().putBoolean(LocationService.KEY_RECORDING_ACTIVE, false).putInt(LocationService.KEY_RECORDING_PID, 0)
            .remove(LocationService.KEY_ACTIVE_RECORDING_ID).apply()
        if (unfinishedId.isNotBlank()) ioScope.launch {
            LocalStore.get(applicationContext).clips().finishSession(unfinishedId, System.currentTimeMillis())
        }
        activeRecording?.stop()
        cameraProvider?.unbindAll()
        timestampEffect?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RECORDING_ID = "recording_id"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 21
    }
}
