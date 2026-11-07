# SurakshaNethra: run and close the demo

This guide starts the project from a closed state, connects the Android app to the backend, and safely shuts everything down. Follow the order below.

## How the demo is connected

- MongoDB runs in Docker on this PC and keeps its data in Docker's persistent `mongodb_data` volume.
- The backend API runs on this PC at `http://127.0.0.1:8001` and is the only part that connects to MongoDB.
- The dashboard runs on this PC at `http://localhost:8000`.
- Cloudflare Quick Tunnel gives phones on other networks temporary HTTPS access to the API on port 8001. It does not expose the dashboard or MongoDB.
- The phone stores video locally. Only an owner-approved clip is uploaded. GPS points wait on the phone until the API acknowledges them.
- Firebase is not a separate server to start. The backend uses its service-account file to send FCM messages when configured; the phone also polls for requests while its location foreground service is active.

The dashboard and API have separate local ports in the current setup. Remote phones use the HTTPS tunnel URL as their backend base URL. Never use the MongoDB URL or port 27017 in the phone, dashboard, or tunnel.

## One-time setup checks

1. Install and open Docker Desktop. Keep the existing container named `mongodb` and its `mongodb_data` volume. Do not remove the container or volume to stop the project.
2. Confirm the private file `D:\SurakshaNethra\SurakshaNethra\backend\.env` exists. It must contain your own MongoDB URI, database name, demo API key, allowed dashboard origins, evidence directory, and (if using Firebase push) the path to your private Firebase Admin service-account JSON file. Use `backend\.env.example` as the template if you need to recreate it. Do not paste credentials into this guide, screenshots, or public code.
3. Confirm `cloudflared` is installed and the Firebase service-account JSON file still exists at the private path configured in `.env`. The Android Firebase client config is `app\google-services.json` in the Android Studio project. Firebase Console does not need to stay open and there is no Firebase container to start. Do not put the Admin JSON file in the Android app or publish it.
4. The latest APK currently built for testing is `C:\Users\srisa\AndroidStudioProjects\Surakshanetra\app\build\outputs\apk\debug\app-debug.apk` (version 1.1). If that file is not on this PC, open the Android project in Android Studio and choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The output appears in the app's `app\build\outputs\apk\debug` folder.
5. The backend Python environment should exist at `backend\.venv`. If it is missing, open PowerShell in the `backend` folder and run:

   ```powershell
   py -3 -m venv .venv
   .\.venv\Scripts\python.exe -m pip install -r requirements.txt
   ```

## Start the project

### 1. Start MongoDB

1. Start Docker Desktop and wait until it says Docker is running.
2. Open PowerShell and run:

   ```powershell
   docker start mongodb
   docker exec mongodb mongosh --quiet --eval "db.adminCommand({ ping: 1 }).ok"
   ```

   The ping should print `1`. If Docker says the container does not exist, stop here and restore/create the MongoDB container with its existing persistent volume and credentials; do not create a blank replacement or delete `mongodb_data`.

### 2. Start the dashboard and API

1. Open **Visual Studio Code**.
2. Choose **File → Open Folder…** and open:

   ```text
   D:\SurakshaNethra\SurakshaNethra
   ```

3. In VS Code choose **Terminal → New Terminal**. In PowerShell run:

   ```powershell
   cd D:\SurakshaNethra\SurakshaNethra\backend
   .\run-local.ps1
   ```

   Leave this terminal open. The script checks Docker/MongoDB, starts the dashboard at `http://localhost:8000`, and runs the API at `http://127.0.0.1:8001`.

4. Check API health in a second browser tab on the PC:

   ```text
   http://127.0.0.1:8001/health
   ```

   It should show `status: ok`. The dashboard itself is at:

   ```text
   http://localhost:8000/
   ```

### 3. Start the public API tunnel for phones

1. Keep the backend terminal running. Open a **second** VS Code terminal (or another PowerShell window) and run:

   ```powershell
   cd D:\SurakshaNethra\SurakshaNethra\backend
   .\run-tunnel.ps1
   ```

2. Copy the `https://…trycloudflare.com` address printed by `cloudflared`. This is the current backend API base URL. Check it on the PC by opening `<that URL>/health`; it should return `status: ok`.
3. Keep both terminals open and keep the PC awake and connected to the internet during the demo. A Quick Tunnel address is temporary and may change after restarting it. If it changes, update the phone and dashboard connection settings with the new URL.

The tunnel script exposes only the API listener on port 8001. Keep the MongoDB port 27017 private and leave the Windows Firewall rule **“SurakshaNethra - Block inbound MongoDB 27017”** enabled.

### 4. Prepare each Android phone

For a phone that has just downloaded the APK:

1. Transfer/install the current SurakshaNethra APK. On Android, approve **Install unknown apps** for the app you used to open the APK if Android asks. Install the APK, then open SurakshaNethra. For an update, install over the existing app; do **not** uninstall it or clear its storage, because that can remove locally stored clips and queued GPS points.
2. On first launch, register the owner name, vehicle registration number, enter the vehicle number a second time exactly the same, and provide the phone number and email. Check the vehicle number carefully; it cannot be changed after registration.
3. Allow camera and microphone access for video with audio. Allow precise location. Allow notifications so footage requests can alert the user.
4. Enable location in Android Quick Settings/System Settings. In SurakshaNethra, turn on **Live location** and keep its persistent location notification active. For continued updates when the app is in the background, use **Settings → Set up background location** and follow Android's separate background-location prompt. Android may require choosing **Allow all the time** in the app's system permission page. GPS runs while the location service is active; camera recording is separately controlled.
5. In the app open **Settings → Backend connection**, expand the connection section if needed, and enter:
   - **Backend API base URL:** the current HTTPS Quick Tunnel URL from step 3, with no `/health` suffix.
   - **Backend demo API key:** the same private demo key configured as `DEMO_API_KEY` in `backend\.env`.

   Tap **Save connection and sync**. Do not enter a MongoDB URI here. Keep the key private.
6. Confirm the settings report a saved connection. If points are queued, leave the app online until the pending count drops; the phone removes queued points only after the backend acknowledges them. Do not uninstall or clear app data while points are pending.
7. On **Home**, start and stop video recording as needed. Recordings stay on the phone. Leave the app's location foreground notification active if GPS collection should continue.

Repeat the registration and backend configuration on each phone. Each phone needs its own unique vehicle registration and device identity.

### 5. Connect and use the dashboard

1. On the PC, open `http://localhost:8000/` in Chrome or Edge.
2. Open **Backend connection** (gear icon). Enter the same current HTTPS Quick Tunnel URL and the same demo API key used in `.env` and the phones. Save/connect.
3. Search using the incident latitude/longitude, time range, and radius. Select matching vehicles and send the footage request.
4. The phone must have internet access and its location foreground service running so it can poll for requests. If Firebase push is configured and working, Android can also show an immediate notification. Tapping a request opens its details.
5. The phone owner reviews the request and chooses **Approve** or **Decline**. A decline requires a reason. Approval uploads the preserved clip and the backend verifies its SHA-256 hash.
6. Refresh the dashboard request tracker if needed. For an approved request, use **View clip** to open the authorized in-page video player.

## Safe shutdown (preserve data)

1. On each phone, stop active video recording and wait for the app to confirm the clip is saved. If practical, leave the phone online until its pending GPS count reaches zero. If it cannot sync now, the points remain queued locally; keep the app installed and its data intact so they can sync next time.
2. In the Cloudflare terminal, press **Ctrl+C** and wait for `cloudflared` to stop. This closes public access to the API.
3. In the backend terminal running `run-local.ps1`, press **Ctrl+C**. The script stops the API and its local dashboard process.
4. MongoDB may remain running in Docker for the next session. If you want to stop it too, open another PowerShell window and run:

   ```powershell
   docker stop mongodb
   ```

   This stops the container but preserves its data volume. Do not choose **Delete**, do not run `docker rm -v`, and do not remove the `mongodb_data` volume.
5. Close the browser and VS Code when ready. Keep the firewall block on port 27017. Do not delete `backend\evidence_uploads`, `backend\.env`, the Docker volume, or app data from phones if you want to retain the demo data and clips.

## Next time

Repeat the start steps: start Docker/MongoDB, run `backend\run-local.ps1`, run `backend\run-tunnel.ps1` in a second terminal, check the new tunnel URL, and update phone/dashboard settings if that URL changed. The tunnel, backend, and PC must remain online during remote use.

## If something does not work

- **Mongo ping fails:** check Docker Desktop and the `mongodb` container before starting the backend.
- **Local `/health` fails:** check that the `run-local.ps1` terminal is still running and inspect its error output.
- **Tunnel `/health` fails:** check local API health first, then restart only the tunnel script and use the newly printed URL.
- **Phone says server unreachable:** verify the phone uses the current HTTPS tunnel base URL (not `localhost`, `127.0.0.1`, port 8000, or a URL ending in `/health`), check internet, and tap **Save connection and sync** again.
- **Phone has pending GPS points:** keep the API/tunnel on and phone connected; do not clear app storage. Retry sync from Settings.
- **No immediate request alert:** keep the location foreground service active so polling can continue; notification permission and Firebase configuration affect push alerts.
- **Approved clip does not appear:** confirm the phone reported upload success, refresh the dashboard request tracker, and use **View clip** on the approved row. The browser dashboard requires its configured API URL/key to match the live tunnel.

