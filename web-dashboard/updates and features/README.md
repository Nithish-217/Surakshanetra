# SurakshaNethra — Updates and Features

**Last updated:** 6 October 2026  
**Purpose:** A plain project record of what is implemented, what is ready for a limited demo, and what remains to be completed. This document describes the source projects; it does not itself prove that a feature has been verified on every phone.

## Project layout

- `../` — web dashboard and architecture notes.
- `../backend/` — FastAPI service that connects to MongoDB and serves the dashboard/API.
- Android app source — `C:\Users\srisa\AndroidStudioProjects\Surakshanetra` (the separately opened Android Studio project). A debug APK was built as `Surakshanetra-Android16.apk` in that Android project folder.

The Android app and this website communicate through the backend API. **The mobile app and browser must never connect directly to MongoDB.**

- Android APK install fix on 6 October 2026: diagnosed the previously shared APK as `android:testOnly=true`, which caused Android to reject normal sideload installation. Clean-rebuilt the debug APK, confirmed the flag is absent, verified its signature, and successfully updated the connected Android 16 emulator with `adb install -r` without uninstalling or clearing app data. Replaced the project-root `Surakshanetra-Android16.apk`.
- 6 October 2026 connectivity audit after laptop sleep: local API `127.0.0.1:8001/health` and the provided public tunnel `/health` returned `ok`; device registration requests reached the API with HTTP 200. MongoDB responded to the backend and retains prior GPS records. The phone showed 55 locally queued pings, and MongoDB’s newest saved GPS point predates the sleep/outage. Manual “Save connection and sync” now replaces stale WorkManager retry/backoff with a fresh attempt while keeping every ping until server acknowledgement.
- Rebuilt `Surakshanetra-Android16.apk` as version 1.1 (versionCode 2), verified the test-only flag is absent, and updated the connected Android 16 emulator without uninstalling or clearing data. A physical-phone queue-drain test remains pending installation of this APK.
- Firebase Android and Admin configurations were verified to match the same project, the backend has one registered device FCM token, and the Admin SDK is installed. Existing footage requests have `pending_app_poll` status; actual FCM delivery is not confirmed. A new request after the phone refreshes its token and keeps live location active is still needed for an end-to-end push check.
- Safety audit: API and dashboard listeners bind to loopback only; Cloudflare exposes the API listener only. MongoDB has root username/password configured and Windows Firewall profiles are enabled, but Docker publishes TCP 27017 on wildcard IPv4 and IPv6 host interfaces. Adding a specific inbound block rule was denied by Windows with “Access is denied”; run the documented admin firewall command before treating MongoDB as private.
- 5 November 2026 end-to-end audit, using a simulated phone against an isolated test database plus the real dashboard in Chrome. Fixed: API timestamps had no timezone, so the app's polling discarded every footage request and the dashboard showed times 5:30 off in IST (API times are now UTC with 3 fraction digits and a `Z`); dashboard distances were wrong or `NaN`; vehicle and recording conflicts and non-ASCII API keys returned HTTP 500; recording routes did not normalize vehicle numbers; expired requests were still offered to phones; the Evidence Library showed both side panels at once and cleared a playing clip on its 30-second refresh. A real phone has not yet been re-tested against these fixes.
- 5 November 2026 tamper check: the pre-approval hash is now write-once on the backend, shown in the dashboard tracker, and compared with a hash of the received clip in the browser; rejected uploads are recorded and shown. Android 1.2 (versionCode 3) retries sending the hash without re-trimming. Backend and dashboard were tested, including a deliberately altered clip. The APK was built and installed on the emulator, but its new retry code has not been exercised because the emulator's system UI was frozen.
## Stage 1 — Implemented in the current demo source

### Mobile app

- Records dashcam video locally with microphone audio when the user grants microphone permission and audio recording is enabled.
- Keeps original recordings on the phone; GPS syncing does not automatically upload video.
- Collects location metadata during the active location/recording service and queues unsent pings locally when the network is unavailable.
- Sends queued GPS pings to the backend in batches when connected. The intended cadence is about every 30 seconds while the location service is active.
- Includes device ID, vehicle registration, coordinates, timestamp, and available recording/owner metadata in the API flow.
- Stores backend URL and demo API key in app settings so values remain available after navigating away.
- Polls the backend for footage requests while the location foreground service is active. The app registers its FCM token with the backend when Firebase configuration is present; otherwise it uses polling.
- Handles approve/decline decisions through the backend. Declining requires a reason. For an approval, the app prepares a requested clip locally, calculates its SHA-256 hash, sends preservation metadata, then uploads the clip for backend verification.

### Backend and MongoDB

- A FastAPI backend is wired to the local Docker MongoDB container using server-side configuration in the ignored `backend/.env` file. The dashboard is loopback-only on port 8000; a separate API-only listener is loopback-only on port 8001, with interactive API docs disabled on that listener. A first live-phone failure caused by the API listener reading MongoDB state from the wrong app was fixed. Authenticated request polling and a phone GPS batch then returned HTTP 200 through the active tunnel.
- Backend CORS now allows the actual VS Code Live Server dashboard origin `http://127.0.0.1:5500` as well as the local dashboard origins. The change was loaded by restarting the API, and the public tunnel preflight returned HTTP 200 for the `:5500` origin.
- The backend now sends a high-priority Firebase data message to a registered device token when Firebase Admin credentials are configured. It reports `sent_fcm` or leaves the request as `pending_app_poll`; polling remains the fallback.
- GPS pings are stored centrally in MongoDB; duplicate ping IDs are handled idempotently. Device/vehicle metadata is upserted from incoming pings.
- The API supports location/time match searches, footage request creation and polling, preservation metadata, decline reasons, approved clip upload/hash verification, and authorized evidence retrieval.
- The Docker MongoDB data uses the persistent `mongodb_data` volume. This is separate from the app’s local video storage.
- `backend/run-local.ps1` checks Docker/MongoDB, serves the dashboard locally on port 8000, and serves the API-only app on port 8001. `backend/run-tunnel.ps1` health-checks the API and tunnels only port 8001.
- The dashboard is API-backed: it searches live match data, creates footage requests, tracks requests, and retrieves authorized evidence. Incident search currently uses latitude/longitude coordinates, not address geocoding.
- Approved footage now opens in an authenticated in-page video player from the request tracker. The player shows loading/errors, allows playback controls, and releases its temporary video URL on close. Dashboard JavaScript syntax validation passed; playback against an approved clip still needs confirmation in the user's browser.

### Privacy and demo security

- The API, not the phone or browser, holds the MongoDB connection string.
- MongoDB port `27017` should remain private. Only the API-only listener on port `8001` should be made reachable to demo phones; the dashboard stays on local port `8000`.
- The app and dashboard use a shared demo API key. Keep it out of screenshots and public source; rotate it if exposed.
- Use only test accounts and non-sensitive location data. A key embedded/entered in a client app is not strong production authentication.
- Stop the API and any tunnel when the demo ends. When they are stopped, remote phones cannot reach this PC-hosted service.

## Stage 2 — Demo setup and verification still required

- `cloudflared` is installed and a Quick Tunnel is active for the current demo session. Its URL is temporary; start `backend/run-tunnel.ps1` again after it stops and update the app/dashboard if the URL changes. The script targets only `http://127.0.0.1:8001`.
- Keep the PC, Docker MongoDB, API, and tunnel running during remote phone tests.
- Enter the same demo API key from `backend/.env` into the app settings on each phone and the dashboard connection dialog. Do not copy the key into this document.
- Confirmed: one phone reached the API through the active public tunnel; authenticated request polling and its GPS batch were accepted (HTTP 200). Still verify multiple phones on different networks and confirm their pings in dashboard location/time search.
- Android Firebase setup: `app/google-services.json` was added on 6 October 2026 and validated against package `com.example.surakshanetra`. Rebuilt the Android debug APK with the Google Services plugin enabled. The Firebase Admin SDK dependency is installed in the backend virtual environment. On 6 October 2026, the Admin service-account JSON was validated locally, its private external path was configured in `backend/.env`, and the API listener was restarted; `http://127.0.0.1:8001/health` returned `ok`. The key remains outside the repository and must never be committed or shared. Still to verify: token registration from the updated APK and actual push delivery to a phone.
- Verify the full request path on actual phones: request received, local trim/hash prepared, consent decision recorded, and approved clip uploaded and hash-verified.
- Confirm the recording duration, audio, background GPS cadence, offline queue recovery, and Android 16 permission behavior on physical devices. Source implementation/build success does not confirm these real-device conditions.
- Check the API’s request/response behavior against any final backend contract changes before a multi-phone demonstration.

## Stage 3 — Planned before real-world or production use

- After the demo FCM setup is complete, replace the shared demo key with real user/device authentication, access controls, and credential rotation.
- Replace the shared demo key with real user/device authentication, access controls, and credential rotation.
- Add HTTPS-only deployment on a stable hosted backend. The current backend is PC-hosted and goes offline with that PC or its internet connection.
- Add geocoding/map tiles if searching by street/address and showing a real geographic map are required; the current search takes coordinate pairs and the dashboard map is schematic.
- Define and implement server-side retention/deletion, privacy notices, consent audit access, backups, and operational monitoring for location data and uploaded evidence.
- Review upload size/time limits and storage capacity before handling large clips or many devices.

## Current limitations to remember

- **Not production-ready:** the shared API key is a demo credential, and location data is sensitive.
- **No public access configured by this document:** the API is local until a tunnel or hosted deployment is set up.
- **FCM requires the backend credential:** the Android config is present, but until the private Firebase Admin service-account file is configured on the backend, request delivery depends on the location foreground service polling every ~30 seconds and having connectivity. Android notification permission must also be enabled.
- **Not yet verified across remote phones:** successful Android build and local API checks do not equal an end-to-end multi-network test.
- **Location retention policy:** confirm a retention period and implement cleanup before accumulating real user data. The 30-day video retention request is distinct from GPS metadata retention.
- **Local video durability:** app updates installed normally should preserve app data, but uninstalling/clearing app data or device failure can remove app-private clips. Back up important demo footage separately.

## Key files

- `../index.html`, `../app.js`, `../styles.css` — dashboard UI and API connection.
- `../backend/app/main.py`, `../backend/app/schemas.py`, `../backend/app/settings.py` — API routes, data validation, and configuration.
- `../backend/README.md`, `../backend/run-local.ps1`, `../backend/run-tunnel.ps1` — local backend and API-only tunnel setup.
- `../ARCHITECTURE_CONTEXT.md` — broader architecture, privacy, and evidence workflow context.
- Android app: `C:\Users\srisa\AndroidStudioProjects\Surakshanetra`.

**Credential reminder:** never paste `.env` values into this file, commit them, or include them in screenshots. `backend/.env.example` is a template; `backend/.env` contains local secrets and must stay private.





