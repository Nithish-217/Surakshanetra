# SurakshaNethra Architecture Context

> Living project context distilled from `architecture.html` on 2026-09-24. Update this document when implementation decisions change.

## Current Delivery Boundary

The current website scope is the **authorized viewer dashboard**. A separate Android/client-side system is expected to provide MongoDB records containing `vehicle_reg_number`, `timestamp`, `latitude`, and `longitude` (reported roughly every 30 seconds over HTTPS). The dashboard will search those records by location and time range, issue consent requests to the matching vehicle owners' mobile apps, and expose a clip only after approval.

Current request-aging display requirement: yellow approval-pending immediately after sending, green approval-pending at one week, red approval-pending at two weeks, and expired at one month. This is a presentation rule supplied for the current prototype and should be confirmed as product policy before production.

### Dashboard frontend

`index.html`, `styles.css`, and `app.js` provide the authorized-viewer dashboard. The page requests live location/time matches and creates footage requests through the FastAPI API; its settings dialog accepts the API base URL and demo API key for the browser session. Incident coordinates are entered as latitude/longitude pairs.

### Backend implementation status (2026-10-05)

The FastAPI demo service in `backend/` uses the local MongoDB Docker container through its ignored `backend/.env` file. It owns database access and exposes mobile batch ping ingestion, location/time matching, footage requests, preservation metadata, decline justification, approved upload/hash verification, evidence retrieval, and request polling. The `run-local.ps1` script checks Docker/MongoDB, starts the local dashboard on loopback port 8000, and starts a separate API-only listener on loopback port 8001. `run-tunnel.ps1` targets only port 8001; interactive API docs and dashboard files are not served on that listener. MongoDB uses the persistent Docker volume `mongodb_data`. Firebase push is not configured; the phone polls while its location foreground service is active.

### Revised demo evidence workflow

When a footage request reaches a mobile device, the app should immediately locate and trim the requested segment **locally**, calculate its SHA-256 hash, and preserve the derived clip in app-private storage. It sends the backend only preservation metadata and the hash while the owner decision remains pending. On approval, it uploads the already prepared clip and the backend verifies that its SHA-256 matches the pre-recorded hash.

Hashing provides tamper detection, not prevention of deletion. For the prototype, local app-private storage plus server-recorded hash metadata is acceptable. A later production system needs backend-controlled immutable object storage and a retention policy to meaningfully prevent deletion after upload. Mobile phones should call the backend API only; they must never receive direct MongoDB Atlas credentials.

## Purpose

SurakshaNethra is a dashcam-based evidence verification system for accident, theft, robbery, insurance-fraud, and incident investigation workflows.

It connects vehicle owners who retain private dashcam footage with authorized police or insurance officers who need to find possible evidence near an incident. Discovery is based on GPS and time metadata; video is never automatically collected or viewed.

## Core Privacy and Evidence Contract

1. Raw dashcam footage remains on the vehicle owner's device by default.
2. The system continuously stores only GPS/time trip metadata needed to find nearby devices.
3. An officer can search for devices by location, radius, and time window.
4. Each matching owner receives a consent request through email, SMS, or push notification.
5. Only after the owner explicitly agrees may the requested footage segment be uploaded.
6. Submitted footage is trimmed to the requested window, SHA-256 hashed, recorded in an append-only audit ledger, and placed in locked storage.
7. A non-response expires after 72 hours, with a reminder at 48 hours. The system retains the timestamped request/proof-of-existence record and may surface an adverse-inference flag for the related claim; it does not acquire the private video.

## Roles

| Role | Responsibilities |
| --- | --- |
| Vehicle owner / user | Uses the mobile app and dashcam/GPS logger, retains local video, and accepts or declines footage requests. |
| Officer | Police or insurer investigator who submits a location/time-window request and reviews authorized evidence. |
| Backend service | Authorizes access, matches GPS metadata, sends requests, orchestrates evidence handling, and records immutable audit events. |

## Major Components

### User side

- **Mobile app**: React Native or Flutter app paired with dashcam and GPS logging.
- **Local/private storage**: Device-app-sandbox footage store. Raw video remains here until consent.
- **Metadata sync**: Periodic GPS coordinates plus timestamps; no continuous video upload.

### Officer side

- **Officer dashboard**: React dashboard, with Leaflet or Mapbox for location-based discovery and evidence review.

### Backend services

| Component | Suggested technology | Responsibility |
| --- | --- | --- |
| API | Node.js/Express or Python/FastAPI | Role-based API for owners and officers; workflow orchestration. |
| Auth service | JWT and bcrypt | Authentication and role-based authorization. |
| Geo database | PostgreSQL with PostGIS | Stores trip pings and runs radius/time-window searches. |
| Notification service | Nodemailer/Gmail SMTP or SendGrid; SMS/push provider | Delivers consent requests and reminders. |
| Reminder job | Background scheduler/worker | Sends the 48-hour reminder and marks a request expired after 72 hours. |
| Trim worker | FFmpeg worker | Trims an authorized upload to the requested evidence interval. |
| Evidence ledger | PostgreSQL append-only, hash-chained table | Audits request and evidence events in tamper-evident order. |
| Evidence storage | MinIO or compatible S3 object storage | Stores authorized, trimmed clips with a retention lock. |
| Immutability layer | PostgreSQL triggers and limited DB privileges | Blocks premature evidence update/deletion. |
| Claim evaluation flag | Backend/domain service | Shows uncorroborated/adverse-inference state after an expired request. |

## Primary Workflow

1. The user app records raw footage locally and periodically transmits trip pings.
2. An officer submits `lat`, `lon`, `radius`, `t_from`, and `t_to` from the dashboard.
3. The API queries PostGIS for devices whose trip pings overlap the location and time window.
4. The API appends a request event to the evidence ledger before any video exchange.
5. The notification service sends a consent request to each relevant owner.
6. On consent, only the relevant raw segment is transferred to the trimming worker.
7. FFmpeg trims to the exact requested interval and computes a SHA-256 digest.
8. The system appends the footage hash, timestamp, request ID, and prior ledger hash to the evidence ledger.
9. The trimmed clip is stored with `locked_until = now + 30 days` (as specified by the reference architecture).
10. The officer can review the locked authorized evidence.

## Request States

Recommended lifecycle: `created` -> `notified` -> `consented` -> `uploading` -> `processing` -> `evidence_ready`.

Alternative terminal states: `declined`, `expired`, `failed`.

- At 48 hours without a response: send a reminder.
- At 72 hours without a response: set `expired`, retain the original request/audit record, and create the claim-evaluation flag where applicable.

## Core Data Model

| Table | Purpose | Important fields |
| --- | --- | --- |
| `users` | Vehicle owners | `id`, `name`, `email`, `vehicle_id` |
| `officers` | Investigators | `id`, `name`, `badge_id`, `org` |
| `trip_pings` | GPS metadata only | `device_id`, `lat`, `lon`, `timestamp` |
| `footage_requests` | Officer search/request record | `id`, `officer_id`, `lat`, `lon`, `radius`, `t_from`, `t_to`, `status` |
| `footage_files` | Authorized clip metadata | `id`, `request_id`, `storage_path`, `sha256`, `locked_until` |
| `evidence_ledger` | Append-only evidence audit trail | `id`, `event_type`, `footage_id`, `hash`, `prev_hash`, `timestamp` |

Implementation note: the reference model does not yet describe device registration, notification-delivery history, consent records, or the many-to-many relationship between a location search and individual owner requests. Add dedicated tables for these when the schema is implemented.

## Evidence Integrity

- **Request-time fact record**: Record device/match context, requested location and time window, and request timestamp when the request is created, before a clip arrives.
- **SHA-256 hashing**: Hash the authorized processed clip and retain the digest with the evidence metadata and ledger event.
- **Hash chain**: Each ledger record incorporates its predecessor: `hash_n = SHA256(data_n + hash_(n-1))`. Any historical alteration becomes detectable through chain validation.
- **Database enforcement**: Use `BEFORE UPDATE` / `BEFORE DELETE` triggers to reject mutation of evidence before `locked_until`; remove `DELETE` privileges from the application's database role as defense in depth.
- **Object retention**: Align object-storage retention/versioning with the database lock. A DB trigger alone cannot stop direct object-store deletion.

## Prototype Assumptions

- Sample footage may be recorded manually rather than obtained from real dashcams.
- Synthetic `trip_pings` can simulate vehicles/routes around a demo incident.
- MinIO can run locally or on a low-cost/free hosting environment for demonstration use.

## Decisions Still Open

- Select one backend runtime: Node.js/Express or Python/FastAPI.
- Select one mobile client: React Native or Flutter.
- Define officer verification, organization tenancy, and authorization/audit requirements.
- Define consent UX, cancellation/revocation rules, and a secure device-to-backend upload protocol.
- Define clip encryption, encryption-key ownership, retention/deletion policy after `locked_until`, and lawful evidence disclosure requirements.
- Specify search precision/radius limits, GPS retention period, and user controls for metadata collection.
- Decide whether an expired request can legally produce an adverse-inference flag in the intended jurisdiction; this is a policy/legal decision, not a technical default.

## Implementation Guardrails

- Do not add a feature that uploads, previews, or accesses raw footage before explicit per-request consent.
- Keep geospatial discovery limited to authorized officers and auditable searches.
- Treat every officer request, consent decision, upload attempt, processing action, and evidence-view event as auditable.
- Make request ownership and status transitions explicit and server-authoritative.
- Use signed, short-lived uploads/downloads and least-privilege service credentials.
- Treat evidence locking as a combined database, object-storage, and access-control concern.

## Source

The original visual architecture is maintained in `architecture.html`.

