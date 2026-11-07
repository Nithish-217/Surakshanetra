# Location sync backend contract

Surakshanetra stores every received GPS fix in its local Room queue first. WorkManager then sends queued points while the network is connected. The app removes a point only after the backend acknowledges its `pingId`. The queue accepts up to 120,000 points (about 42 days at one point every 30 seconds); if it fills, the app warns the user and stops adding new points until sync frees space.

## Request

`POST {BASE_URL}/v1/location-pings/batch`

Headers:

```text
Authorization: Bearer <per-user-or-device-access-token>
Content-Type: application/json
```

Body:

```json
{
  "pings": [
    {
      "pingId": "a-stable-uuid-for-this-location-fix",
      "deviceId": "persistent-random-device-uuid",
      "vehicleNumber": "KA01AB1234",
      "recordingId": "active-recording-session-uuid",
      "recordedAt": "2026-09-25T13:00:00.000Z",
      "location": {
        "type": "Point",
        "coordinates": [77.5946, 12.9716]
      },
      "accuracyMeters": 8.0
    }
  ]
}
```

Coordinates are `[longitude, latitude]`. The app sends up to 500 location points per request, oldest first.

## Success response

Return a 2xx response with every newly stored **or already stored** ping ID in `acceptedPingIds`. Including already stored IDs makes retries safe if a response is lost after the database write.

```json
{
  "acceptedPingIds": ["a-stable-uuid-for-this-location-fix"]
}
```

The backend authenticates the bearer token, verifies that it is authorized for the registered vehicle, and uses `pingId` as an idempotency key. The starter backend also persists the optional `deviceId` and `recordingId` fields for device/session lookups. These fields identify the phone and active recording when a ping was captured. It stores only location metadata in MongoDB; this endpoint never sends video files. Keep the MongoDB connection string and credentials on the backend server, not in the APK.

## App configuration

In **Settings → Central location sync**, enter the deployed backend's HTTPS base URL and the access token issued for that vehicle. For local testing on a physical phone, use the development computer's private Wi-Fi IPv4 address and port (for example, `http://192.168.1.20:3000` for the Node starter), with both devices on the same network and inbound access to that port allowed. Do not use `localhost` or `10.0.2.2` from a physical phone. Until a reachable base URL and valid token are configured, location points remain queued on the phone.

## Backend setup

The starter API is in `backend/`. From that directory, run `npm install`, copy `.env.example` to `.env`, then set `MONGODB_URI` and `DEVICE_TOKENS_JSON`. Run `npm run new-token` to create a long random bearer token for each phone, then map each token to its registered vehicle in `DEVICE_TOKENS_JSON`, for example `{"<token-for-phone-1>":"KA01AB1234","<token-for-phone-2>":"MH12CD5678"}`. Give each phone its matching token in the app's Settings. Keep `.env` private; it is excluded from version control. Deploy the API behind HTTPS, allow the API host to reach the Atlas cluster, then enter the deployed API's base URL in the app. The API stores pings in `surakshanetra.vehicle_locations` and creates indexes for vehicle/time lookup, duplicate protection, and geographic queries.
