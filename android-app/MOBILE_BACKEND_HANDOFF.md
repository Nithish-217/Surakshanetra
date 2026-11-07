# Surakshanetra mobile/backend handoff

For production, the Android app must use the deployed backend's HTTPS base URL. For local testing on a physical phone, it can use the development computer's private Wi-Fi IPv4 address and backend port (for example, `http://192.168.1.20:8000`); the phone and computer must be on the same network and the server/firewall must allow that port. `localhost` and `10.0.2.2` are not the computer's address on a physical phone. MongoDB Atlas credentials belong on the backend server and must never be placed in the APK.

## Prepared on mobile

- Registration stores owner name, immutable vehicle registration number (entered twice), phone, and email. A random persistent device UUID is created and held in encrypted preferences.
- FCM token retrieval and the message service are wired. Incoming data messages accept `requestId`, `vehicleNumber`, `startTimestamp`, `endTimestamp`, `latitude`, and `longitude`, with common snake_case aliases. The final backend payload should be mapped here after it is supplied.
- Start Recording creates a recording/session ID and saves one continuous CameraX video file with microphone audio and a timestamp overlay. It runs until the user taps Stop; there is no three-minute rotation. GPS runs independently at 30-second intervals, including during video recording, and local location rows include the session ID.
- GPS pings are queued locally and WorkManager syncs them when connected to the demo FastAPI service. `POST /v1/location-pings/batch` accepts the app's bearer demo key and returns acknowledged ping IDs; pings may have a null recording ID so tracking continues while not recording.
- A footage request is saved locally, shows a notification, and schedules local trim + SHA-256 preparation. The protected copy is kept under app-private internal storage. The worker never sends video.
- The Requests tab previews the prepared clip and shows the request ID, vehicle, requested time range, location, and hash. Approval and a mandatory-reason decline are saved in the local consent log.
- Original clips and preserved request clips are automatically removed after 30 days.

## Dashboard/backend integration status

The paired FastAPI dashboard/backend referenced by the original handoff is in `D:\SurakshaNethra\SurakshaNethra`. Its deployed URL and private `DEMO_API_KEY` are not stored in this Android workspace. It implements Android device registration, GPS batch upload, footage request polling, preservation metadata, owner decisions, approved-video upload, and the dashboard/evidence-library page. The Node starter in this Android workspace is a separate GPS-only service and does not provide the dashboard/evidence routes. End-to-end footage and dashboard flows require that FastAPI service to be running and reachable.

The Android Settings connection check probes `/v1/location-pings/batch` with an intentionally empty batch. FastAPI returns HTTP 422 for that invalid batch after accepting the API key; that response confirms the endpoint and key are valid. GPS points remain queued locally until successful acknowledgements.

The FastAPI backend uses data-only Firebase messages when its matching Firebase Admin service account is configured. Android also polls `/api/v1/footage-requests/pending` while its location foreground service is active. The notification parser accepts the backend's ISO timestamps, including UTC offsets written as `+00:00`. Push delivery requires Android notification permission; polling still works as the fallback.

## Firebase configuration

`app/google-services.json` is present for application ID `com.example.surakshanetra`, and the Google Services Gradle plugin is configured to apply when that file exists. FCM token issuance and incoming pushes still depend on the matching Firebase project and backend Firebase Admin credentials being configured correctly.

Use data-only FCM messages if the app must save the clip immediately on receipt, including while Android is in the background. The OS may display a notification payload itself without invoking app code until the user taps it.
