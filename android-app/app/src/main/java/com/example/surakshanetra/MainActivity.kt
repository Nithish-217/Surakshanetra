package com.example.surakshanetra

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateFormat
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.surakshanetra.ui.theme.SurakshanetraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.UUID

private val Ink: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
private val Green: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val Pale: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val Muted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

private const val THEME_PREFERENCES = "surakshanetra_appearance"
private const val KEY_DARK_THEME = "dark_theme"

class MainActivity : ComponentActivity() {
    private var recordingService: RecordingService? = null
    private var previewView: PreviewView? = null
    private var serviceBound = false
    private val openedRequestId = mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recordingService = (binder as? RecordingService.LocalBinder)?.service()
            recordingService?.attachPreview(previewView)
        }
        override fun onServiceDisconnected(name: ComponentName?) { recordingService = null }
    }

    override fun onStart() {
        super.onStart()
        val active = getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(LocationService.KEY_RECORDING_ACTIVE, false)
        if (active) bindRecorder()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedRequestId.value = intent.getStringExtra(EXTRA_REQUEST_ID)
        val state = getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE)
        if (state.getBoolean(LocationService.KEY_RECORDING_ACTIVE, false) &&
            state.getInt(LocationService.KEY_RECORDING_PID, 0) != android.os.Process.myPid()) {
            state.edit().putBoolean(LocationService.KEY_RECORDING_ACTIVE, false).putInt(LocationService.KEY_RECORDING_PID, 0).apply()
        }
        if (state.getBoolean(LocationService.KEY_LOCATION_ACTIVE, false) &&
            state.getInt(LocationService.KEY_LOCATION_PID, 0) != android.os.Process.myPid()) {
            state.edit().putBoolean(LocationService.KEY_LOCATION_ACTIVE, false).putInt(LocationService.KEY_LOCATION_PID, 0).apply()
        }
        setContent {
            val appearance = getSharedPreferences(THEME_PREFERENCES, Context.MODE_PRIVATE)
            var darkTheme by remember { mutableStateOf(appearance.getBoolean(KEY_DARK_THEME, false)) }
            SurakshanetraTheme(darkTheme = darkTheme) {
                val systemBarColor = MaterialTheme.colorScheme.background.toArgb()
                val systemNavigationBarColor = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect {
                    window.statusBarColor = systemBarColor
                    window.navigationBarColor = systemNavigationBarColor
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SurakshanetraApp(
                        onStartRecording = ::startRecording,
                        onStopRecording = ::stopRecording,
                        onStartLocation = ::startLocation,
                        onStopLocation = ::stopLocation,
                        onPreviewReady = ::attachPreview,
                        darkTheme = darkTheme,
                        onDarkThemeChange = { enabled ->
                            darkTheme = enabled
                            appearance.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
                        },
                        openedRequestId = openedRequestId.value
                    )
                }
            }
        }
    }

    private fun startRecording() {
        val state = getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE)
        val recordingId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val vehicle = state.getString(KEY_VEHICLE, "").orEmpty()
        val folder = getExternalFilesDir("Surakshanetra/clips") ?: filesDir.resolve("clips")
        state.edit().putString(LocationService.KEY_ACTIVE_RECORDING_ID, recordingId)
            .putBoolean(LocationService.KEY_RECORDING_ACTIVE, true)
            .putInt(LocationService.KEY_RECORDING_PID, android.os.Process.myPid())
            .remove(LocationService.KEY_RECORDING_ERROR)
            .apply()
        lifecycleScope.launch(Dispatchers.IO) {
            LocalStore.get(applicationContext).clips().beginSession(
                RecordingSession(recordingId, vehicle, startedAt, 0L, 0L, folder.absolutePath)
            )
        }
        val serviceIntent = Intent(this, RecordingService::class.java)
            .putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindRecorder(serviceIntent)
        startLocation()
    }

    private fun bindRecorder(intent: Intent = Intent(this, RecordingService::class.java)) {
        if (!serviceBound) serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopRecording() {
        val service = recordingService
        if (service != null) service.stopRecording() else stopService(Intent(this, RecordingService::class.java))
    }

    private fun attachPreview(view: PreviewView) {
        previewView = view
        recordingService?.attachPreview(view)
    }

    private fun startLocation() {
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
    }

    private fun stopLocation() { stopService(Intent(this, LocationService::class.java)) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedRequestId.value = intent.getStringExtra(EXTRA_REQUEST_ID)
    }

    override fun onStop() {
        recordingService?.attachPreview(null)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            recordingService = null
        }
        super.onStop()
    }

    companion object { const val EXTRA_REQUEST_ID = "open_footage_request_id" }
}

@Composable
private fun SurakshanetraApp(
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onStartLocation: () -> Unit,
    onStopLocation: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    openedRequestId: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences(LocationService.PREFS, Context.MODE_PRIVATE) }
    val secureTokenStore = remember(context) { SecureTokenStore(context) }
    val clipDao = remember(context) { LocalStore.get(context).clips() }
    val locationPingDao = remember(context) { LocalStore.get(context).locationPings() }
    val requestDao = remember(context) { LocalStore.get(context).clips() }
    val clips by clipDao.observeClips().collectAsState(initial = emptyList())
    val footageRequests by requestDao.observeRequests().collectAsState(initial = emptyList())
    val pendingLocationPings by locationPingDao.observePendingCount().collectAsState(initial = 0)
    val listState = rememberLazyListState()
    var tab by remember(openedRequestId) { mutableIntStateOf(if (openedRequestId != null) 3 else 1) }
    var cameraGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var audioGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var registeredVehicle by remember { mutableStateOf(prefs.getString(KEY_VEHICLE, null)) }
    var registeredOwner by remember { mutableStateOf(prefs.getString(KEY_OWNER_NAME, null)) }
    var registeredPhone by remember { mutableStateOf(prefs.getString(KEY_PHONE, null)) }
    var registeredEmail by remember { mutableStateOf(prefs.getString(KEY_EMAIL, null)) }
    var fcmReady by remember { mutableStateOf(!secureTokenStore.readFcmToken().isNullOrBlank()) }
    var fineOrCoarse by remember { mutableStateOf(hasLocationPermission(context)) }
    var gpsPermissionFlowComplete by remember { mutableStateOf(hasLocationPermission(context)) }
    var gpsDefaultStartAttempted by remember { mutableStateOf(prefs.getBoolean(LocationService.KEY_LOCATION_USER_DISABLED, false)) }
    var backgroundGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    var recording by remember { mutableStateOf(prefs.getBoolean(LocationService.KEY_RECORDING_ACTIVE, false)) }
    var locationActive by remember { mutableStateOf(prefs.getBoolean(LocationService.KEY_LOCATION_ACTIVE, false)) }
    var locationQueueFull by remember { mutableStateOf(prefs.getBoolean(LocationService.KEY_LOCATION_QUEUE_FULL, false)) }
    var status by remember { mutableStateOf("Ready") }
    var showBackgroundRationale by remember { mutableStateOf(false) }
    var showBackendConnection by remember { mutableStateOf(false) }
    var declineTarget by remember { mutableStateOf<FootageRequest?>(null) }
    var declineReason by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf(prefs.getString(LocationService.KEY_LAT, null)) }
    var longitude by remember { mutableStateOf(prefs.getString(LocationService.KEY_LON, null)) }
    var locationTime by remember { mutableLongStateOf(prefs.getLong(LocationService.KEY_TIME, 0L)) }
    var locationAccuracy by remember { mutableFloatStateOf(prefs.getFloat(LocationService.KEY_ACCURACY, 0f)) }
    var formError by remember { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        cameraGranted = grants[Manifest.permission.CAMERA] == true
        audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        fineOrCoarse = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasLocationPermission(context)
        if (cameraGranted && audioGranted && fineOrCoarse) {
            status = "Starting camera…"
            onStartRecording()
        } else status = "Camera, microphone, and foreground location permissions are required for recording with GPS."
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        fineOrCoarse = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOrCoarse) {
            prefs.edit().putBoolean(LocationService.KEY_LOCATION_USER_DISABLED, false).putBoolean(LocationService.KEY_GPS_PERMISSION_REQUESTED, true).apply()
            onStartLocation()
            showBackgroundRationale = Build.VERSION.SDK_INT >= 29 && !backgroundGranted
            status = "Getting a GPS fix…"
        } else {
            prefs.edit().putBoolean(LocationService.KEY_GPS_PERMISSION_REQUESTED, true).apply()
            status = "Location permission was denied. Enable it to turn on GPS tracking."
        }
        gpsPermissionFlowComplete = true
    }
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        backgroundGranted = granted
        status = if (granted) "Background location access is enabled." else "Location works while its notification is active; background permission was not granted."
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Push notifications are enabled." else "Enable notifications in Android settings to see footage requests."
    }

    LaunchedEffect(openedRequestId, footageRequests, tab) {
        if (tab == 3 && openedRequestId != null) {
            val requestIndex = footageRequests.indexOfFirst { it.requestId == openedRequestId }
            if (requestIndex >= 0) listState.animateScrollToItem(2 + requestIndex)
        }
    }

    LaunchedEffect(registeredVehicle, registeredOwner, registeredPhone, registeredEmail) {
        if (registeredVehicle.isNullOrBlank() || registeredOwner.isNullOrBlank() || registeredPhone.isNullOrBlank() || registeredEmail.isNullOrBlank()) return@LaunchedEffect
        if (hasLocationPermission(context)) {
            fineOrCoarse = true
            gpsPermissionFlowComplete = true
            if (!prefs.getBoolean(LocationService.KEY_LOCATION_USER_DISABLED, false) && !prefs.getBoolean(LocationService.KEY_LOCATION_ACTIVE, false)) {
                onStartLocation()
                status = "GPS tracking started automatically."
            }
            gpsDefaultStartAttempted = true
        } else if (!prefs.getBoolean(LocationService.KEY_GPS_PERMISSION_REQUESTED, false) && !gpsDefaultStartAttempted) {
            gpsDefaultStartAttempted = true
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            gpsPermissionFlowComplete = true
        }
    }

    LaunchedEffect(registeredVehicle, registeredOwner, registeredPhone, registeredEmail, gpsPermissionFlowComplete, showBackgroundRationale) {
        if (Build.VERSION.SDK_INT >= 33 && !registeredVehicle.isNullOrBlank() && !registeredOwner.isNullOrBlank() &&
            !registeredPhone.isNullOrBlank() && !registeredEmail.isNullOrBlank() &&
            gpsPermissionFlowComplete && !showBackgroundRationale &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            recording = prefs.getBoolean(LocationService.KEY_RECORDING_ACTIVE, false)
            locationActive = prefs.getBoolean(LocationService.KEY_LOCATION_ACTIVE, false)
            locationQueueFull = prefs.getBoolean(LocationService.KEY_LOCATION_QUEUE_FULL, false)
            latitude = prefs.getString(LocationService.KEY_LAT, null)
            longitude = prefs.getString(LocationService.KEY_LON, null)
            locationTime = prefs.getLong(LocationService.KEY_TIME, 0L)
            locationAccuracy = prefs.getFloat(LocationService.KEY_ACCURACY, 0f)
            fcmReady = !secureTokenStore.readFcmToken().isNullOrBlank()
            val error = prefs.getString(LocationService.KEY_RECORDING_ERROR, null)
            if (locationQueueFull) status = "Offline location queue is full; new points are not being saved."
            else if (error != null) status = "Recording stopped: $error"
            else if (recording) status = "Recording and saving clips on this phone"
            else if (locationActive && latitude != null) status = "GPS fix captured"
            delay(750)
        }
    }

    if (registeredVehicle.isNullOrBlank() || registeredOwner.isNullOrBlank() || registeredPhone.isNullOrBlank() || registeredEmail.isNullOrBlank()) {
        RegistrationScreen(
            error = formError,
            existingVehicle = registeredVehicle,
            existingOwner = registeredOwner.orEmpty(),
            existingPhone = registeredPhone.orEmpty(),
            existingEmail = registeredEmail.orEmpty(),
            onRegister = { ownerInput, vehicleInput, vehicleConfirmation, phoneInput, emailInput ->
            val existingVehicle = prefs.getString(KEY_VEHICLE, null)?.takeIf(String::isNotBlank)
            val vehicle = normalizeVehicle(existingVehicle ?: vehicleInput)
            val confirmation = normalizeVehicle(vehicleConfirmation)
            val digits = phoneInput.filter(Char::isDigit)
            val email = emailInput.trim()
            formError = when {
                ownerInput.trim().length !in 2..80 -> "Enter the vehicle owner's name."
                vehicle.length !in 4..15 -> "Enter a valid vehicle registration number."
                existingVehicle == null && confirmation != vehicle -> "The vehicle numbers do not match. Please check both entries."
                digits.length !in 7..15 -> "Enter a valid phone number."
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email address."
                else -> null
            }
            if (formError == null) {
                val normalizedEmail = email.lowercase()
                secureTokenStore.deviceId()
                prefs.edit().putString(KEY_OWNER_NAME, ownerInput.trim()).putString(KEY_VEHICLE, vehicle).putString(KEY_PHONE, phoneInput.trim())
                    .putString(KEY_EMAIL, normalizedEmail).apply()
                registeredVehicle = vehicle
                registeredOwner = ownerInput.trim()
                registeredPhone = phoneInput.trim()
                registeredEmail = normalizedEmail
            }
        })
        return
    }

    Scaffold(containerColor = Pale, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            listOf("Profile", "Home", "Clips", "Requests", "Settings").forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = {
                        Box(Modifier.size(34.dp).background(if (tab == index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                            NavigationGlyph(index = index, tint = if (tab == index) Green else Muted)
                        }
                    },
                    label = { Text(label, fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedIconColor = Green, selectedTextColor = Green, unselectedTextColor = Muted))
            }
        }
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Header(registeredVehicle.orEmpty(), recording) }
            when (tab) {
                0 -> {
                    item { SectionTitle("Profile", "Your registered vehicle and contact details") }
                    item { ProfileCard(registeredOwner!!, registeredVehicle!!, registeredPhone!!, registeredEmail!!,
                        deviceId = secureTokenStore.deviceId(), fcmReady = fcmReady, onSave = { phone, email ->
                        val digits = phone.filter(Char::isDigit)
                        when {
                            digits.length !in 7..15 -> { formError = "Enter a valid phone number."; false }
                            !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> { formError = "Enter a valid email address."; false }
                            else -> {
                                registeredPhone = phone.trim()
                                registeredEmail = email.trim().lowercase()
                                prefs.edit().putString(KEY_PHONE, registeredPhone).putString(KEY_EMAIL, registeredEmail).apply()
                                formError = null
                                status = "Profile updated"
                                true
                            }
                        }
                    }, error = formError) }
                }
                1 -> {
                    item { StatusBanner(status, recording, locationActive) }
                    item { HomeSummary(recording, locationActive, pendingLocationPings) }
                    item {
                        RecordingCard(recording, cameraGranted && audioGranted, onToggle = {
                            if (recording) onStopRecording()
                            else if (cameraGranted && audioGranted && fineOrCoarse) onStartRecording()
                            else cameraPermissionLauncher.launch(buildList {
                                add(Manifest.permission.CAMERA)
                                add(Manifest.permission.RECORD_AUDIO)
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }.toTypedArray())
                        }, onPreviewReady = onPreviewReady)
                    }
                    item { SectionTitle("Live location", "GPS sampling target: every 30 seconds") }
                    item {
                        LocationCard(locationActive, latitude, longitude, locationTime, locationAccuracy, pendingLocationPings, locationQueueFull, recording, onToggle = {
                            if (locationActive) {
                                prefs.edit().putBoolean(LocationService.KEY_LOCATION_USER_DISABLED, true).apply()
                                onStopLocation()
                            }
                            else if (hasLocationPermission(context)) {
                                prefs.edit().putBoolean(LocationService.KEY_LOCATION_USER_DISABLED, false).apply()
                                onStartLocation()
                            }
                            else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        })
                    }
                    item {
                        Text(
                            if (locationQueueFull) "The offline queue is full. Configure sync and reconnect to upload points before more can be saved."
                            else "$pendingLocationPings location point${if (pendingLocationPings == 1) "" else "s"} waiting to sync. Set your secure backend URL and access token in Settings.",
                            color = if (locationQueueFull) MaterialTheme.colorScheme.error else Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                2 -> {
                    item { SectionTitle("Your clips", "Saved locally · ${clips.size} clip${if (clips.size == 1) "" else "s"}") }
                    if (clips.isEmpty()) item { EmptyClips(recording) }
                    items(clips, key = { it.path }) { clip ->
                        ClipCard(clip,
                            onPlay = {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(clip.path))
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }.onFailure { status = "No video player is available on this phone." }
                            }
                        )
                    }
                }
                3 -> {
                    item { SectionTitle("Footage requests", "Review before anything is shared") }
                    if (footageRequests.isEmpty()) item { InfoCard("◷", "No footage requests", "When the backend sends a footage request, it appears here with its time range and matching local preview.", "Waiting") }
                    items(footageRequests, key = { it.requestId }) { request ->
                        FootageRequestCard(
                            request = request,
                            highlighted = request.requestId == openedRequestId,
                            onPlay = { path -> playLocalVideo(context, path) },
                            onApprove = {
                                if (!request.preservedVideoPath.isNullOrBlank() && !request.sha256.isNullOrBlank()) {
                                    status = "Uploading the approved clip to the backend…"
                                    scope.launch {
                                        requestDao.markDecision(request.requestId, "APPROVED_WAITING_API", null)
                                        requestDao.recordConsent(ConsentRecord(request.requestId, System.currentTimeMillis(), "approve", request.requestedFrom, request.requestedTo))
                                        val uploaded = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val duration = ((request.requestedTo - request.requestedFrom) / 1000L).toInt().coerceAtLeast(1)
                                                DemoBackendApi.preserveEvidence(context, request, duration) &&
                                                    DemoBackendApi.approveAndUpload(context, request, File(request.preservedVideoPath))
                                            }.getOrDefault(false)
                                        }
                                        requestDao.markDecision(
                                            request.requestId,
                                            if (uploaded) "APPROVED" else "APPROVED_WAITING_API",
                                            null
                                        )
                                        status = if (uploaded) "Approved clip uploaded and verified by the backend." else "Consent is saved on this phone. The clip will retry upload when the backend is reachable."
                                    }
                                } else status = "The requested clip is not ready to approve yet."
                            },
                            onDecline = { declineTarget = request }
                        )
                    }
                }
                else -> {
                    item { SectionTitle("Settings", "Recording, location and storage preferences") }
                    item { ThemeSettingsCard(darkTheme = darkTheme, onThemeChange = onDarkThemeChange) }
                    item {
                        OutlinedButton(
                            onClick = { showBackendConnection = !showBackendConnection },
                            modifier = Modifier.fillMaxWidth().height(70.dp),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                Text("⌁", color = Green, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Backend connection", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (showBackendConnection) "Hide central location sync settings" else "Tap to configure central location sync",
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(if (showBackendConnection) "−" else "+", color = Green, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    if (showBackendConnection) {
                        item {
                            LocationSyncSettingsCard(
                                context = context,
                                pendingCount = pendingLocationPings,
                                onSaved = { status = "Sync settings saved. Pending GPS points will upload when the server is reachable." }
                            )
                        }
                    }
                    item { InfoCard("◉", "Audio recording", "Video clips include microphone audio when recording is enabled.", if (audioGranted) "Allowed" else "Allow on record") }
                    item {
                        InfoCard("⌖", "Background location", if (backgroundGranted) "Allowed. Location still runs only while its persistent notification is active." else "Foreground GPS is available. Android asks for background access separately.", if (backgroundGranted) "Allowed" else "Optional")
                        if (Build.VERSION.SDK_INT >= 29 && !backgroundGranted) TextButton(onClick = { showBackgroundRationale = true }) { Text("Set up background location", color = Green) }
                    }
                    item { InfoCard("⌁", "Clip retention", "Clips are kept on this phone and removed automatically after 30 days. Manual deletion is unavailable.", "30 days") }
                    if (showBackendConnection) item { InfoCard("⌖", "Location queue", "Keeps up to 120,000 GPS points (about 42 days at 30-second intervals) until the backend confirms them. Video clips are not part of this upload.", "$pendingLocationPings pending") }
                }
            }
        }
    }

    if (showBackgroundRationale) AlertDialog(
        onDismissRequest = { showBackgroundRationale = false },
        title = { Text("Allow location in the background?") },
        text = { Text("This is a separate Android permission. It lets the location notification keep receiving GPS updates while you use another app. Coordinates remain on this phone until a server is configured.") },
        confirmButton = { TextButton(onClick = {
            showBackgroundRationale = false
            if (Build.VERSION.SDK_INT >= 29) backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }) { Text("Continue", color = Green) } },
        dismissButton = { TextButton(onClick = { showBackgroundRationale = false }) { Text("Not now") } }
    )

    if (declineTarget != null) AlertDialog(
        onDismissRequest = { declineTarget = null; declineReason = "" },
        title = { Text("Decline footage request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A reason is required. It will be saved on this phone and sent to the backend.", color = Muted)
                OutlinedTextField(value = declineReason, onValueChange = { declineReason = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Reason for declining") }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(enabled = declineReason.isNotBlank(), onClick = {
                val declined = declineTarget ?: return@TextButton
                val reason = declineReason.trim()
                scope.launch {
                    requestDao.markDecision(declined.requestId, "DECLINED_WAITING_API", reason)
                    requestDao.recordConsent(ConsentRecord(declined.requestId, System.currentTimeMillis(), "decline", declined.requestedFrom, declined.requestedTo))
                    val sent = withContext(Dispatchers.IO) { runCatching { DemoBackendApi.decline(context, declined.requestId, reason) }.getOrDefault(false) }
                    if (sent) requestDao.markDecision(declined.requestId, "DECLINED", reason)
                    status = if (sent) "Decline and justification sent to the backend." else "Decline saved locally and will sync when the backend is reachable."
                }
                declineTarget = null
                declineReason = ""
            }) { Text("Submit decline") }
        },
        dismissButton = { TextButton(onClick = { declineTarget = null; declineReason = "" }) { Text("Cancel") } }
    )
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

internal fun normalizeVehicle(value: String): String = value.filter(Char::isLetterOrDigit).uppercase()

const val KEY_VEHICLE = "profile_registered_vehicle"
const val KEY_OWNER_NAME = "profile_owner_name"
private const val KEY_PHONE = "profile_phone"
private const val KEY_EMAIL = "profile_email"

@Composable private fun RegistrationScreen(
    error: String?, existingVehicle: String?, existingOwner: String, existingPhone: String, existingEmail: String,
    onRegister: (String, String, String, String, String) -> Unit
) {
    var ownerName by remember { mutableStateOf(existingOwner) }
    var vehicle by remember(existingVehicle) { mutableStateOf(existingVehicle.orEmpty()) }
    var confirmVehicle by remember(existingVehicle) { mutableStateOf(existingVehicle.orEmpty()) }
    var phone by remember { mutableStateOf(existingPhone) }
    var email by remember { mutableStateOf(existingEmail) }
    val vehicleIsLocked = !existingVehicle.isNullOrBlank()
    val registrationMuted = Muted
    Column(
        Modifier.fillMaxSize().background(Pale).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Color(0xFF2B8A68), Color(0xFF145640))), CircleShape), contentAlignment = Alignment.Center) {
                Text("S", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Surakshanetra", color = Ink, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                Text("PERSONAL SAFETY", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.1.sp)
            }
        }
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF17664C), Color(0xFF102F28))), RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("A calmer drive starts here", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (vehicleIsLocked) "Complete your profile to continue." else "Set up your profile and keep your vehicle details close at hand.", color = Color(0xFFD5E9DE), style = MaterialTheme.typography.bodyMedium)
        }
        val registrationFieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            cursorColor = Green,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedLabelColor = Green,
            unfocusedLabelColor = registrationMuted,
            focusedBorderColor = Green,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
        Column(
            Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f), RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Text("Your details", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(ownerName, { ownerName = it }, Modifier.fillMaxWidth(), label = { Text("Owner / user name") }, singleLine = true, shape = RoundedCornerShape(15.dp), colors = registrationFieldColors)
            OutlinedTextField(vehicle, { if (!vehicleIsLocked) vehicle = it.uppercase() }, Modifier.fillMaxWidth(), label = { Text(if (vehicleIsLocked) "Registered vehicle (locked)" else "Vehicle registration number") }, readOnly = vehicleIsLocked, enabled = !vehicleIsLocked, singleLine = true, shape = RoundedCornerShape(15.dp), colors = registrationFieldColors)
            if (!vehicleIsLocked) OutlinedTextField(confirmVehicle, { confirmVehicle = it.uppercase() }, Modifier.fillMaxWidth(), label = { Text("Confirm vehicle registration number") }, singleLine = true, shape = RoundedCornerShape(15.dp), colors = registrationFieldColors)
            Text(if (vehicleIsLocked) "This registered vehicle number cannot be changed." else "You can’t change this number after registration, so check it carefully.", color = registrationMuted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Phone number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, shape = RoundedCornerShape(15.dp), colors = registrationFieldColors)
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, shape = RoundedCornerShape(15.dp), colors = registrationFieldColors)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onRegister(ownerName, vehicle, confirmVehicle, phone, email) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White)) {
                Text("Register and continue", fontWeight = FontWeight.Bold)
            }
        }
        Text("Your profile is stored on this phone. It is not verified by an online registration service.", color = registrationMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable private fun ProfileCard(owner: String, vehicle: String, phoneValue: String, emailValue: String, deviceId: String, fcmReady: Boolean, onSave: (String, String) -> Boolean, error: String?) {
    var phone by remember(phoneValue) { mutableStateOf(phoneValue) }
    var email by remember(emailValue) { mutableStateOf(emailValue) }
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(25.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(25.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(owner.take(1).uppercase(), color = Green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(owner, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("LOCAL PROFILE", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = .9.sp)
            }
            Box(Modifier.background(if (fcmReady) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(if (fcmReady) "PUSH READY" else "OFFLINE", color = if (fcmReady) Green else Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF174B3A), Color(0xFF102F28))), RoundedCornerShape(19.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("REGISTERED VEHICLE", color = Color(0xFFB7DDC9), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = .9.sp)
            Text(vehicle, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall, letterSpacing = 1.sp)
            Text("Locked after registration", color = Color(0xFFC2D9CE), style = MaterialTheme.typography.bodySmall)
        }
        if (editing) {
            OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Phone number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, shape = RoundedCornerShape(15.dp))
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, shape = RoundedCornerShape(15.dp))
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (onSave(phone, email)) editing = false }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Save contact details", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { phone = phoneValue; email = emailValue; editing = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        } else {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(18.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                ProfileDetail("PHONE NUMBER", phoneValue)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                ProfileDetail("EMAIL ADDRESS", emailValue)
            }
            Text("DEVICE ID  ·  ${deviceId.take(8)}…${deviceId.takeLast(4)}", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = .4.sp)
            Text(if (fcmReady) "Push notifications are ready on this device." else "Push notifications are not available yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { editing = true }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) { Text("Edit contact details", color = Green, fontWeight = FontWeight.Bold) }
        }
        if (editing) {
            OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Phone number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (onSave(phone, email)) editing = false }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Save contact details") }
            TextButton(onClick = {
                phone = phoneValue
                email = emailValue
                editing = false
            }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        } else {
            Text("Phone number", color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(phoneValue, color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text("Email address", color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(emailValue, color = Ink, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { editing = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Edit profile", color = Green) }
        }
    }
}

@Composable private fun ProfileDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
        Text(value, color = Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun ThemeSettingsCard(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(if (darkTheme) "☾" else "☼", color = Green, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("App appearance", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(if (darkTheme) "Dark theme" else "Light theme", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = darkTheme, onCheckedChange = onThemeChange)
    }
}

@Composable private fun LocationSyncSettingsCard(context: Context, pendingCount: Int, onSaved: () -> Unit) {
    val settings = remember(context) { LocationSyncSettings(context) }
    val tokenStore = remember(context) { SecureTokenStore(context) }
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(settings.baseUrlDraft() ?: settings.baseUrl().orEmpty()) }
    var accessToken by remember { mutableStateOf(tokenStore.readAccessTokenDraft() ?: tokenStore.readAccessToken().orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var checkingConnection by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp)).padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Central location sync", color = Ink, fontWeight = FontWeight.SemiBold)
        Text("GPS points stay queued on this phone until the backend acknowledges them. Video clips are not sent with location sync.", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = {
                baseUrl = it
                settings.saveBaseUrlDraft(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Backend API base URL") },
            placeholder = { Text("https://api.example.com or http://192.168.1.20:8000") },
            singleLine = true,
            shape = RoundedCornerShape(15.dp)
        )
        OutlinedTextField(
            value = accessToken,
            onValueChange = {
                accessToken = it
                tokenStore.saveAccessTokenDraft(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Backend demo API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(15.dp)
        )
        Button(
            onClick = {
                when {
                    !LocationSyncSettings.isValidBackendBaseUrl(baseUrl) -> { message = "Use an HTTPS backend URL, or HTTP with a private-network IP address for a local demo."; connectionError = true }
                    accessToken.trim().length < 24 -> { message = "Enter the long access token issued for this vehicle."; connectionError = true }
                    else -> {
                        checkingConnection = true
                        message = "Checking backend URL and vehicle token…"
                        connectionError = false
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { DemoBackendApi.checkLocationConnection(baseUrl, accessToken.trim()) }
                            }
                            checkingConnection = false
                            result.onSuccess { confirmation ->
                                settings.saveBaseUrl(baseUrl)
                                tokenStore.saveAccessToken(accessToken.trim())
                                settings.clearBaseUrlDraft()
                                tokenStore.clearAccessTokenDraft()
                                LocationUploadScheduler.syncNow(context)
                                DemoBackendApi.registerDeviceFromSavedProfile(context)
                                message = "$confirmation Location sync saved; $pendingCount queued point${if (pendingCount == 1) "" else "s"} will upload."
                                connectionError = false
                                onSaved()
                            }.onFailure { error ->
                                message = error.message ?: "Could not connect to the backend. Check the URL, server, and network."
                                connectionError = true
                            }
                        }
                    }
                }
            },
            enabled = !checkingConnection,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) { Text(if (checkingConnection) "Checking backend…" else "Test connection, save and sync") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Waiting on this phone", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("$pendingCount points", modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp), color = Green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        if (message != null) Text(message!!, color = if (connectionError) MaterialTheme.colorScheme.error else Green, style = MaterialTheme.typography.bodySmall)
        Text("Use the API URL and the same DEMO_API_KEY configured in the dashboard backend/.env. For HTTP, use a private LAN IP; never enter the MongoDB connection string here.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun Header(vehicle: String, recording: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).background(
                    Brush.linearGradient(listOf(Color(0xFF2B8A68), Color(0xFF145640))), CircleShape
                ), contentAlignment = Alignment.Center
            ) {
                Text("S", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.align(Alignment.BottomEnd).padding(1.dp).size(12.dp).background(if (recording) Color(0xFFFF6B62) else Color(0xFF9CE6B9), CircleShape))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Surakshanetra", color = Ink, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                Text("PERSONAL SAFETY", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp)
            }
        }
        if (vehicle.isNotBlank()) {
            Column(Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(horizontal = 11.dp, vertical = 7.dp), horizontalAlignment = Alignment.End) {
                Text("YOUR VEHICLE", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 0.7.sp)
                Text(vehicle, color = Green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable private fun StatusBanner(text: String, recording: Boolean, locationActive: Boolean) {
    Column(
        Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(Color(0xFF17664C), Color(0xFF102F28))), RoundedCornerShape(26.dp)
        ).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR SAFETY DASHBOARD", color = Color(0xFFB7DDC9), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            val isActive = recording || locationActive
            Row(Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(if (isActive) Color(0xFF72E3A4) else Color(0xFFD6C07C), CircleShape))
                Text(if (recording) "RECORDING" else if (locationActive) "MONITORING" else "READY", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            when { recording -> "Video and location are being saved on this phone."; locationActive -> "Live location is running. Your clips stay private."; else -> "Start monitoring when you're ready to head out." },
            color = Color(0xFFC2D9CE), style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable private fun HomeSummary(recording: Boolean, locationActive: Boolean, pendingCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        val items = listOf(
            Triple("CAMERA", if (recording) "Recording" else "Ready", if (recording) Green else Muted),
            Triple("GPS", if (locationActive) "Live" else "Off", if (locationActive) Green else Muted),
            Triple("SYNC QUEUE", pendingCount.toString(), if (pendingCount > 0) Color(0xFFB56B22) else Green)
        )
        items.forEach { (label, value, valueColor) ->
            Column(
                Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(17.dp)).padding(horizontal = 11.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(label, color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
                Text(value, color = valueColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable private fun NavigationGlyph(index: Int, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val w = size.width
        val h = size.height
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1, y1), Offset(x2, y2), stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (index) {
            0 -> {
                drawCircle(tint, radius = w * .16f, center = Offset(w * .5f, h * .31f), style = stroke)
                val shoulders = Path().apply { moveTo(w * .16f, h * .88f); quadraticTo(w * .18f, h * .58f, w * .5f, h * .58f); quadraticTo(w * .82f, h * .58f, w * .84f, h * .88f) }
                drawPath(shoulders, tint, style = stroke)
            }
            1 -> {
                val roof = Path().apply { moveTo(w * .12f, h * .47f); lineTo(w * .5f, h * .14f); lineTo(w * .88f, h * .47f) }
                drawPath(roof, tint, style = stroke)
                drawRoundRect(tint, Offset(w * .23f, h * .44f), androidx.compose.ui.geometry.Size(w * .54f, h * .45f), androidx.compose.ui.geometry.CornerRadius(w * .06f), style = stroke)
                line(w * .46f, h * .89f, w * .46f, h * .62f)
            }
            2 -> {
                drawRoundRect(tint, Offset(w * .15f, h * .13f), androidx.compose.ui.geometry.Size(w * .7f, h * .74f), androidx.compose.ui.geometry.CornerRadius(w * .13f), style = stroke)
                val play = Path().apply { moveTo(w * .43f, h * .37f); lineTo(w * .66f, h * .5f); lineTo(w * .43f, h * .63f); close() }
                drawPath(play, tint)
            }
            3 -> {
                drawRoundRect(tint, Offset(w * .19f, h * .1f), androidx.compose.ui.geometry.Size(w * .62f, h * .8f), androidx.compose.ui.geometry.CornerRadius(w * .1f), style = stroke)
                line(w * .34f, h * .38f, w * .66f, h * .38f)
                line(w * .34f, h * .55f, w * .62f, h * .55f)
                line(w * .34f, h * .72f, w * .54f, h * .72f)
            }
            else -> {
                drawCircle(tint, radius = w * .23f, style = stroke)
                drawCircle(tint, radius = w * .08f)
                for (angle in 0 until 8) {
                    val radians = Math.toRadians(angle * 45.0)
                    val inner = w * .31f
                    val outer = w * .43f
                    line(w * .5f + (inner * kotlin.math.cos(radians)).toFloat(), h * .5f + (inner * kotlin.math.sin(radians)).toFloat(), w * .5f + (outer * kotlin.math.cos(radians)).toFloat(), h * .5f + (outer * kotlin.math.sin(radians)).toFloat())
                }
            }
        }
    }
}

@Composable private fun RecordingCard(recording: Boolean, hasCamera: Boolean, onToggle: () -> Unit, onPreviewReady: (PreviewView) -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("DASHCAM", color = Green, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(if (recording) "Recording active" else "Ready when you are", color = Ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (recording) "Saving one continuous video with audio" else "One continuous clip · audio enabled", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.size(44.dp).background(if (recording) Color(0xFFFFECE9) else MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) { Text(if (recording) "●" else "◉", color = if (recording) Color(0xFFC44439) else Green, style = MaterialTheme.typography.titleLarge) }
        }
        if (hasCamera) {
            AndroidView(
                factory = { viewContext -> PreviewView(viewContext).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER; onPreviewReady(it) } },
                update = onPreviewReady,
                modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF17241F))
            )
            if (!recording) Text("Live preview and microphone audio start together when you record.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFF26352F) else Green, contentColor = if (recording) Color.White else MaterialTheme.colorScheme.onPrimary)) {
            Text(if (recording) "Stop and save clip" else if (hasCamera) "Start live recording" else "Allow camera and microphone", fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
        }
        Text("The notification stays visible while recording. Clips are stored in app storage and never upload automatically.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun LocationCard(active: Boolean, lat: String?, lon: String?, time: Long, accuracy: Float, pendingCount: Int, queueFull: Boolean, recording: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(22.dp))
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(9.dp).background(if (active) Color(0xFF27A86B) else Muted, CircleShape))
                Text(if (active) "GPS is active" else "GPS is off", color = Ink, fontWeight = FontWeight.Bold)
            }
            Text(if (queueFull) "QUEUE FULL" else "$pendingCount PENDING", modifier = Modifier.background(if (queueFull) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp), color = if (queueFull) MaterialTheme.colorScheme.error else Green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        if (lat != null && lon != null) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(15.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("LATEST GPS FIX", color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
                Text("$lat, $lon", color = Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("Accuracy ±${accuracy.toInt()} m · ${DateFormat.format("h:mm:ss a", Date(time))}", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        } else Text(if (active) "Waiting for a GPS fix… Check that Location is turned on." else "Enable GPS to save current coordinates on this phone.", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (recording) Text("GPS points continue saving on this phone every 30 seconds and sync when the backend is reachable.", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (queueFull) Text("Connect to the configured backend to free queue space; new points are paused until then.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onToggle, enabled = !recording || !active, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text(
                if (recording && active) "GPS stays active during recording"
                else if (recording) "Enable GPS during recording"
                else if (active) "Stop location"
                else "Enable live location",
                color = Green
            )
        }
    }
}

@Composable private fun EmptyClips(recording: Boolean) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(23.dp))
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(23.dp)).padding(21.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            NavigationGlyph(2, Green)
        }
        Text(if (recording) "Your first clip is in progress" else "Your clips will live here", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(if (recording) "Video and GPS keep saving while you record. Your clip is finalized when you stop." else "Start a recording to save a private clip on this phone.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun ClipCard(clip: ClipRecord, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(21.dp))
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(21.dp)).padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.size(54.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
            NavigationGlyph(2, Green)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(DateFormat.format("EEE, d MMM · h:mm a", Date(clip.startedAt)).toString(), color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text("${((clip.endedAt - clip.startedAt) / 1000).coerceAtLeast(1)} sec · ${android.text.format.Formatter.formatShortFileSize(LocalContext.current, clip.sizeBytes)}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        FilledTonalButton(onClick = onPlay, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)) { Text("Play", fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, color = Ink, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium) }
}

@Composable private fun InfoCard(icon: String, title: String, description: String, badge: String) {
    Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .48f), RoundedCornerShape(21.dp))
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(21.dp)).padding(16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(43.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Text(icon, color = Green, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(badge, modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp), color = Green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun FootageRequestCard(
    request: FootageRequest,
    highlighted: Boolean,
    onPlay: (String) -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit
) {
    val path = request.preservedVideoPath
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = path) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                android.media.MediaMetadataRetriever().run {
                    try {
                        setDataSource(path)
                        getFrameAtTime(-1, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally { release() }
                }
            }.getOrNull()
        }
    }
    val alreadyDecided = request.status.startsWith("APPROVED") || request.status.startsWith("DECLINED")
    val canApprove = request.status == "PRESERVED_LOCAL_WAITING_API" && !path.isNullOrBlank() && !request.sha256.isNullOrBlank()
    val statusColor = when {
        request.status.startsWith("APPROVED") -> Green
        request.status.startsWith("DECLINED") -> Muted
        request.status.contains("FAILED") || request.status == "NO_LOCAL_CLIP" -> MaterialTheme.colorScheme.error
        else -> Color(0xFFB56B22)
    }
    Column(
        Modifier.fillMaxWidth().border(1.dp, if (highlighted) Green.copy(alpha = .55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("INCIDENT REQUEST", color = Green, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            Text(request.status.replace('_', ' '), modifier = Modifier.background(statusColor.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp), color = statusColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Text("Vehicle ${request.vehicleNumber}", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("ID  ${request.requestId}", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(17.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("REQUESTED TIME", color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = .65.sp)
                Text("${DateFormat.format("dd MMM yyyy · HH:mm", Date(request.requestedFrom))} – ${DateFormat.format("HH:mm", Date(request.requestedTo))}", color = Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("INCIDENT LOCATION", color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = .65.sp)
                Text(if (request.latitude != null && request.longitude != null) "${request.latitude}, ${request.longitude}" else "Not provided", color = if (request.latitude != null && request.longitude != null) Ink else Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!request.recordingId.isNullOrBlank()) Text("Recording ID: ${request.recordingId}", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), "Preview of the exact preserved clip", Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
            Text("Preview of the trimmed clip that would be uploaded", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else if (request.status == "PRESERVING") {
            Text("Finding and preserving matching local video…", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (!request.sha256.isNullOrBlank()) Text("SHA-256: ${request.sha256}", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        if (!request.declineReason.isNullOrBlank()) Text("Decline reason: ${request.declineReason}", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (!request.preservedVideoPath.isNullOrBlank() && File(request.preservedVideoPath).isFile) {
            OutlinedButton(onClick = { onPlay(request.preservedVideoPath) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) { Text("Play preserved preview") }
        }
        if (!alreadyDecided) {
            Text("Nothing is sent automatically. Review the time range, location and preview before approval.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(15.dp)) { Text("Decline", fontWeight = FontWeight.SemiBold) }
                Button(onClick = onApprove, enabled = canApprove, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Approve", fontWeight = FontWeight.Bold) }
            }
            if (request.status == "PRESERVATION_FAILED") Text("The matching clip could not be prepared. It was not uploaded.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (request.status == "NO_LOCAL_CLIP") Text("No local clip covers this time range, so approval is unavailable.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (request.status == "APPROVED_WAITING_API") {
            Text("Approval is saved on this phone. The upload waits for the backend's exact endpoint and authentication contract.", color = Green, style = MaterialTheme.typography.bodySmall)
        } else if (request.status == "DECLINED_WAITING_API") {
            Text("Decline is saved on this phone and syncs when the backend is reachable.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun playLocalVideo(context: Context, path: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
