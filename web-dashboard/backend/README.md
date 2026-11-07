# SurakshaNethra local demo backend

FastAPI is the only component that connects to MongoDB. The mobile app and browser dashboard communicate through API endpoints; MongoDB is never exposed to them directly.

## Local Docker setup

The Docker Desktop container is named `mongodb` and stores data in the persistent `mongodb_data` volume. Keep Docker Desktop and the container running. The private backend `.env` contains the MongoDB URI and demo API key. Never put those values in the Android app source or commit `.env`.

Start both local components from PowerShell:

```powershell
.\run-local.ps1
```

This starts:

- Dashboard at `http://localhost:8000`, bound to loopback only.
- API at `http://localhost:8001`, also bound to loopback only and serving API routes only.

The API creates MongoDB indexes at startup and writes vehicle metadata and GPS samples to `surakshanethra_demo`. GPS batches are acknowledged only after MongoDB accepts their ping IDs.

## Create a temporary public API URL for remote phones

Install `cloudflared` from Cloudflare's official Windows download page. With `run-local.ps1` still running, open a second PowerShell window in this `backend` folder and run:

```powershell
.\run-tunnel.ps1
```

The script checks the API health endpoint and tunnels **only** `http://127.0.0.1:8001`. It does not tunnel the local dashboard on port 8000 or MongoDB on port 27017. Copy the generated `https://....trycloudflare.com` URL from that terminal.

On every phone, open **Settings → Backend connection** and set the base URL to the generated HTTPS URL, then enter the `DEMO_API_KEY` from the private `backend/.env`. In the dashboard on the PC, open `http://localhost:8000`, open **Backend connection**, and enter that same HTTPS URL and key. The dashboard's browser origin is allowed by the API CORS configuration.

A Quick Tunnel URL is temporary and changes when restarted. Leave both PowerShell windows, the PC, the backend, and Docker running for the demo. Press Ctrl+C in the tunnel window to stop remote access; press Ctrl+C in the backend window to stop the local services. MongoDB remains private and should never be included in the tunnel.

## Location and consent flow

Each phone sends its vehicle number, device ID, owner name, timestamp, coordinates, accuracy, and optional recording ID about every 30 seconds while its location foreground service is active. Offline pings remain in the phone's Room queue until the API confirms them. The dashboard searches by incident coordinates, time range, and radius. Matching apps poll for requests while their location foreground service is active.

The app prepares and hashes the requested clip locally. It sends preservation metadata first and uploads the video only after approval. Firebase push still requires Firebase project configuration; polling is the current demo notification fallback.

## Tamper check

The phone hashes the trimmed clip when a request arrives and reports the hash before the owner decides. The dashboard tracker shows that hash in **Hash before approval**. An upload whose hash differs is rejected and counted on the request. When a viewer opens an approved clip, the dashboard or Evidence Library hashes the downloaded bytes in the browser and shows whether they match. Browser hashing needs `localhost` or HTTPS. The check proves the clip has not changed since the phone hashed it, not that the footage was unedited before the request arrived.

## Endpoints exposed through the API-only listener

- `POST /v1/location-pings/batch` — offline-retried Android location batches.
- `POST /api/v1/matches/search` — location/time match search.
- `POST /api/v1/footage-requests` — create consent requests.
- `GET /api/v1/footage-requests/pending` — phone request polling.
- `POST /api/v1/footage-requests/{id}/preserve` — register local clip hash. The hash is write-once: repeating it is accepted, and a different hash returns HTTP 409.
- `POST /api/v1/footage-requests/{id}/decline` — submit the owner's reason.
- `POST /api/v1/footage-requests/{id}/approve` — upload an approved clip and verify SHA-256.
- `GET /api/v1/footage-requests` — dashboard request tracker.
- `GET /api/v1/evidence/{id}` — retrieve authorized evidence.
- `GET /health` — minimal service health response only.

All data endpoints require the demo key in `X-Demo-API-Key` or `Authorization: Bearer`. The health route returns only service status. The API-only app has no dashboard files and disables interactive API documentation/OpenAPI routes. The shared demo key is still only for a small test with non-sensitive data, not production authentication.
