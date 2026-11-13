import hashlib
import hmac
import shutil
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

from fastapi import APIRouter, Depends, FastAPI, File, Header, HTTPException, Query, UploadFile, status
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pymongo import ASCENDING, DESCENDING, AsyncMongoClient
from pymongo.errors import DuplicateKeyError

from .schemas import (
    DeclineRequest,
    DeviceRegistration,
    FootageRequestCreate,
    MatchSearch,
    MobileLocationPingBatch,
    PreserveEvidence,
    RecordingStart,
    RecordingStop,
    TelemetryPing,
)
from .settings import settings


def utc_now() -> datetime:
    return datetime.now(UTC)


def api_time(value):
    """Format a datetime as UTC with exactly 3 fraction digits.

    The Android client discards timestamps without an offset, and its
    SimpleDateFormat reads a 6-digit fraction as a count of milliseconds.
    """
    if not isinstance(value, datetime):
        return value
    aware = value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)
    return aware.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def initialize_firebase():
    credential_path = settings.firebase_service_account_path.strip()
    if not credential_path:
        return None
    try:
        import firebase_admin
        from firebase_admin import credentials
    except ImportError:
        print("FCM is configured by path but firebase-admin is not installed; requests will use app polling.")
        return None
    if not Path(credential_path).is_file():
        print("FCM service-account file is missing; requests will use app polling.")
        return None
    try:
        return firebase_admin.initialize_app(
            credentials.Certificate(credential_path),
            name="surakshanethra-demo",
        )
    except ValueError:
        return firebase_admin.get_app("surakshanethra-demo")
    except Exception as error:
        print(f"FCM initialization failed ({type(error).__name__}); requests will use app polling.")
        return None


async def require_demo_api_key(
    x_demo_api_key: str | None = Header(default=None),
    authorization: str | None = Header(default=None),
) -> None:
    bearer = authorization.split(" ", 1)[1].strip() if authorization and authorization.lower().startswith("bearer ") else None
    provided = x_demo_api_key or bearer or ""
    if not hmac.compare_digest(provided.encode(), settings.demo_api_key.encode()):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid demo API key")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.evidence_directory.mkdir(parents=True, exist_ok=True)
    client = AsyncMongoClient(settings.mongodb_uri, tz_aware=True)
    database = client[settings.mongodb_database]
    app.state.database = database
    # The local dashboard and public API run as separate ASGI applications.
    # Route handlers use database(), which reads app.state, so initialize it
    # regardless of which app's lifespan is starting this process.
    api_app.state.database = database

    await database.devices.create_index([("device_id", ASCENDING)], unique=True)
    await database.devices.create_index([("vehicle_reg_number", ASCENDING)], unique=True)
    await database.recordings.create_index([("recording_id", ASCENDING)], unique=True)
    await database.gps_pings.create_index([("location", "2dsphere")])
    await database.gps_pings.create_index([("ping_id", ASCENDING)], unique=True, sparse=True)
    await database.gps_pings.create_index([("vehicle_reg_number", ASCENDING), ("timestamp", DESCENDING)])
    await database.footage_requests.create_index([("request_id", ASCENDING)], unique=True)
    await database.footage_requests.create_index([("vehicle_reg_number", ASCENDING), ("created_at", DESCENDING)])
    firebase_app = initialize_firebase()
    app.state.firebase_app = firebase_app
    api_app.state.firebase_app = firebase_app
    yield
    await client.close()


app = FastAPI(title="SurakshaNethra Demo API", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-Demo-API-Key"],
)

# API routes are mounted on a separate API-only ASGI app below. The
# dashboard app remains available locally, while Cloudflare should target only
# api_app on port 8001.
api_router = APIRouter()


def database():
    api_database = getattr(api_app.state, "database", None)
    return api_database if api_database is not None else app.state.database


async def send_footage_push(device: dict, request: dict) -> str:
    firebase_app = getattr(api_app.state, "firebase_app", None)
    token = device.get("fcm_token")
    if firebase_app is None or not token:
        return "pending_app_poll"
    try:
        from firebase_admin import messaging

        data = {
            "requestId": str(request["request_id"]),
            "vehicleNumber": str(request["vehicle_reg_number"]),
            "startTimestamp": api_time(request["time_start"]),
            "endTimestamp": api_time(request["time_end"]),
            "latitude": str(request["location"]["coordinates"][1]),
            "longitude": str(request["location"]["coordinates"][0]),
            "title": "Surakshanetra footage request",
        }
        message = messaging.Message(
            token=token,
            data=data,
            android=messaging.AndroidConfig(priority="high", ttl=timedelta(days=2)),
        )
        await run_in_threadpool(messaging.send, message, app=firebase_app)
        return "sent_fcm"
    except Exception as error:
        print(f"FCM delivery failed ({type(error).__name__}); request remains available through app polling.")
        return "pending_app_poll"


def public_request(document: dict) -> dict:
    return {
        "request_id": document["request_id"],
        "vehicle_reg_number": document["vehicle_reg_number"],
        "status": document["status"],
        "time_start": api_time(document["time_start"]),
        "time_end": api_time(document["time_end"]),
        "created_at": api_time(document["created_at"]),
        "deadline_at": api_time(document["deadline_at"]),
        "preserved_at": api_time(document.get("preserved_at")),
        "decline_justification": document.get("decline_justification"),
        "evidence_url": document.get("evidence_url"),
        "sha256": document.get("sha256"),
        "received_sha256": document.get("received_sha256"),
        "rejected_upload_count": document.get("rejected_upload_count", 0),
        "rejected_upload_sha256": document.get("rejected_upload_sha256"),
        "rejected_upload_at": api_time(document.get("rejected_upload_at")),
        "notification_status": document.get("notification_status", "pending_app_poll"),
        "latitude": (document.get("location") or {}).get("coordinates", [None, None])[1],
        "longitude": (document.get("location") or {}).get("coordinates", [None, None])[0],
    }


@api_router.get("/health")
async def health_check():
    return {"status": "ok", "service": "surakshanethra-demo-api"}


@api_router.post("/api/v1/devices/register", dependencies=[Depends(require_demo_api_key)])
async def register_device(payload: DeviceRegistration):
    db = database()
    document = payload.model_dump() | {"registered_at": utc_now()}
    try:
        await db.devices.update_one({"device_id": payload.device_id}, {"$set": document}, upsert=True)
    except DuplicateKeyError as error:
        raise HTTPException(status_code=409, detail="This vehicle registration is already assigned to another device") from error
    return {"device_id": payload.device_id, "vehicle_reg_number": payload.vehicle_reg_number, "status": "registered"}


@api_router.post("/api/v1/recordings/start", dependencies=[Depends(require_demo_api_key)])
async def start_recording(payload: RecordingStart):
    db = database()
    device = await db.devices.find_one({"device_id": payload.device_id, "vehicle_reg_number": payload.vehicle_reg_number})
    if not device:
        raise HTTPException(status_code=404, detail="Register the device before starting a recording")
    document = payload.model_dump() | {"status": "recording", "created_at": utc_now()}
    try:
        await db.recordings.insert_one(document)
    except DuplicateKeyError as error:
        raise HTTPException(status_code=409, detail="This recording ID already exists") from error
    await add_ping(db, payload.model_dump())
    return {"recording_id": payload.recording_id, "status": "recording"}


async def add_ping(db, payload: dict) -> None:
    timestamp = payload.get("timestamp", payload.get("started_at"))
    await db.gps_pings.insert_one(
        {
            "recording_id": payload["recording_id"],
            "device_id": payload["device_id"],
            "vehicle_reg_number": payload["vehicle_reg_number"].replace(" ", "").upper(),
            "timestamp": timestamp,
            "location": {"type": "Point", "coordinates": [payload["longitude"], payload["latitude"]]},
        }
    )


@api_router.post("/api/v1/telemetry/pings", dependencies=[Depends(require_demo_api_key)])
async def record_ping(payload: TelemetryPing):
    db = database()
    recording = await db.recordings.find_one({"recording_id": payload.recording_id, "status": "recording"})
    if not recording:
        raise HTTPException(status_code=404, detail="Active recording not found")
    await add_ping(db, payload.model_dump())
    return {"recording_id": payload.recording_id, "status": "ping_recorded"}


@api_router.post("/v1/location-pings/batch", dependencies=[Depends(require_demo_api_key)])
async def record_mobile_ping_batch(payload: MobileLocationPingBatch):
    """Accept offline-retried phone pings whether or not a video recording is active."""
    db = database()
    accepted_ids: list[str] = []
    for ping in payload.pings:
        vehicle = ping.vehicle_number.replace(" ", "").upper()
        existing_ping = await db.gps_pings.find_one({"ping_id": ping.ping_id}, {"device_id": 1, "vehicle_reg_number": 1})
        if existing_ping:
            if existing_ping.get("device_id") != ping.device_id or existing_ping.get("vehicle_reg_number") != vehicle:
                raise HTTPException(status_code=409, detail="This ping ID is already assigned to another device or vehicle")
            accepted_ids.append(ping.ping_id)
            continue
        existing_device = await db.devices.find_one({"device_id": ping.device_id}, {"vehicle_reg_number": 1})
        if existing_device and existing_device.get("vehicle_reg_number") != vehicle:
            raise HTTPException(status_code=409, detail="This device ID is already assigned to another vehicle")

        device = {
            "device_id": ping.device_id,
            "vehicle_reg_number": vehicle,
            "owner_name": ping.owner_name.strip() or "Vehicle owner",
            "last_seen_at": ping.recorded_at,
        }
        if not existing_device:
            try:
                await db.devices.update_one({"device_id": ping.device_id}, {"$setOnInsert": device}, upsert=True)
            except Exception as error:
                if error.__class__.__name__ == "DuplicateKeyError":
                    raise HTTPException(status_code=409, detail="This vehicle registration is already assigned to another device") from error
                raise
        else:
            await db.devices.update_one({"device_id": ping.device_id}, {"$set": {"owner_name": device["owner_name"], "last_seen_at": ping.recorded_at}})

        coordinates = list(ping.location.coordinates)
        document = {
            "ping_id": ping.ping_id,
            "device_id": ping.device_id,
            "vehicle_reg_number": vehicle,
            "owner_name": device["owner_name"],
            "recording_id": ping.recording_id,
            "timestamp": ping.recorded_at,
            "location": {"type": "Point", "coordinates": coordinates},
            "accuracy_meters": ping.accuracy_meters,
            "received_at": utc_now(),
        }
        await db.gps_pings.update_one({"ping_id": ping.ping_id}, {"$setOnInsert": document}, upsert=True)
        accepted_ids.append(ping.ping_id)
    return {"acceptedPingIds": accepted_ids}


@api_router.post("/api/v1/recordings/{recording_id}/stop", dependencies=[Depends(require_demo_api_key)])
async def stop_recording(recording_id: str, payload: RecordingStop):
    db = database()
    result = await db.recordings.update_one(
        {"recording_id": recording_id, "status": "recording"},
        {"$set": payload.model_dump() | {"status": "complete"}},
    )
    if not result.modified_count:
        raise HTTPException(status_code=404, detail="Active recording not found")
    return {"recording_id": recording_id, "status": "complete"}


@api_router.post("/api/v1/matches/search", dependencies=[Depends(require_demo_api_key)])
async def search_matches(payload: MatchSearch):
    db = database()
    if payload.time_end <= payload.time_start:
        raise HTTPException(status_code=422, detail="time_end must be after time_start")
    query = {
        "timestamp": {"$gte": payload.time_start, "$lte": payload.time_end},
        "location": {
            "$near": {
                "$geometry": {"type": "Point", "coordinates": [payload.longitude, payload.latitude]},
                "$maxDistance": payload.radius_meters,
            }
        },
    }
    pings = await db.gps_pings.find(query).sort("timestamp", DESCENDING).to_list(length=5000)
    latest_by_vehicle = {}
    for ping in pings:
        vehicle = ping.get("vehicle_reg_number") or ping.get("vehicleNumber")
        if not vehicle or vehicle in latest_by_vehicle:
            continue
        latest_by_vehicle[vehicle] = ping
    matches = []
    for vehicle, ping in latest_by_vehicle.items():
        device_id = ping.get("device_id")
        location = ping["location"]["coordinates"]
        owner_name = ping.get("owner_name", "Vehicle owner")
        if device_id:
            device = await db.devices.find_one({"device_id": device_id}, {"owner_name": 1})
            if device:
                owner_name = device.get("owner_name", owner_name)
        matches.append(
            {
                "vehicle_reg_number": vehicle,
                "owner_name": owner_name,
                "device_id": device_id,
                "recording_id": ping.get("recording_id"),
                "last_matching_ping": api_time(ping.get("timestamp", ping.get("recordedAt"))),
                "latitude": location[1],
                "longitude": location[0],
            }
        )
    return {"count": len(matches), "matches": matches}


@api_router.post("/api/v1/footage-requests", dependencies=[Depends(require_demo_api_key)])
async def create_footage_requests(payload: FootageRequestCreate):
    if payload.time_end <= payload.time_start:
        raise HTTPException(status_code=422, detail="time_end must be after time_start")
    db = database()
    devices = await db.devices.find({"vehicle_reg_number": {"$in": payload.vehicle_reg_numbers}}).to_list(length=20)
    registered = {device["vehicle_reg_number"]: device for device in devices}
    missing = [vehicle for vehicle in payload.vehicle_reg_numbers if vehicle not in registered]
    if missing:
        raise HTTPException(status_code=404, detail={"message": "Devices not registered", "vehicles": missing})

    now = utc_now()
    requests = []
    for vehicle in payload.vehicle_reg_numbers:
        request_id = str(uuid4())
        request = {
            "request_id": request_id,
            "vehicle_reg_number": vehicle,
            "device_id": registered[vehicle]["device_id"],
            "location": {"type": "Point", "coordinates": [payload.longitude, payload.latitude]},
            "radius_meters": payload.radius_meters,
            "time_start": payload.time_start,
            "time_end": payload.time_end,
            "requester_name": payload.requester_name,
            "requester_organization": payload.requester_organization,
            "status": "approval_pending",
            "created_at": now,
            "deadline_at": now + timedelta(days=30),
            "notification_status": "pending_app_poll",
        }
        await db.footage_requests.insert_one(request)
        request["notification_status"] = await send_footage_push(registered[vehicle], request)
        await db.footage_requests.update_one(
            {"request_id": request_id},
            {"$set": {"notification_status": request["notification_status"]}},
        )
        requests.append(public_request(request))
    pushed = sum(item["notification_status"] == "sent_fcm" for item in requests)
    polled = len(requests) - pushed
    note = f"{pushed} push notification(s) sent; {polled} request(s) available through app polling."
    return {"requests": requests, "notification_note": note}


@api_router.get("/api/v1/footage-requests/pending", dependencies=[Depends(require_demo_api_key)])
async def pending_footage_requests(vehicle_reg_number: str = Query(min_length=3, max_length=32)):
    """Polling fallback for demo phones; Firebase can be enabled separately later."""
    vehicle = vehicle_reg_number.replace(" ", "").upper()
    # A poll proves the app is online even while it has no new GPS fix to send.
    await database().devices.update_one({"vehicle_reg_number": vehicle}, {"$set": {"last_poll_at": utc_now()}})
    documents = await database().footage_requests.find(
        {"vehicle_reg_number": vehicle, "status": "approval_pending", "deadline_at": {"$gt": utc_now()}}
    ).sort("created_at", ASCENDING).to_list(length=50)
    return {
        "requests": [
            {
                "requestId": document["request_id"],
                "vehicleNumber": document["vehicle_reg_number"],
                "startTimestamp": api_time(document["time_start"]),
                "endTimestamp": api_time(document["time_end"]),
                "latitude": (document.get("location") or {}).get("coordinates", [None, None])[1],
                "longitude": (document.get("location") or {}).get("coordinates", [None, None])[0],
                "receivedAt": api_time(document["created_at"]),
            }
            for document in documents
        ]
    }


@api_router.post("/api/v1/footage-requests/{request_id}/preserve", dependencies=[Depends(require_demo_api_key)])
async def preserve_evidence(request_id: str, payload: PreserveEvidence):
    db = database()
    request = await db.footage_requests.find_one({"request_id": request_id, "status": "approval_pending"})
    if not request:
        raise HTTPException(status_code=404, detail="Pending footage request not found")
    # The hash is write-once: replacing it would let an altered clip pass the approval check.
    sha256 = payload.sha256.lower()
    update = payload.model_dump() | {"sha256": sha256, "preserved_at": utc_now(), "preservation_status": "ready_for_approval"}
    result = await db.footage_requests.update_one(
        {"request_id": request_id, "status": "approval_pending", "sha256": None},
        {"$set": update},
    )
    if not result.modified_count:
        current = await db.footage_requests.find_one({"request_id": request_id})
        if (current.get("sha256") or "").lower() != sha256:
            raise HTTPException(status_code=409, detail="A different hash is already recorded for this request and cannot be replaced")
    return {"request_id": request_id, "status": "approval_pending", "preservation_status": "ready_for_approval"}


@api_router.post("/api/v1/footage-requests/{request_id}/decline", dependencies=[Depends(require_demo_api_key)])
async def decline_request(request_id: str, payload: DeclineRequest):
    db = database()
    result = await db.footage_requests.update_one(
        {"request_id": request_id, "status": "approval_pending"},
        {"$set": {"status": "declined", "decline_justification": payload.justification, "decided_at": utc_now()}},
    )
    if not result.modified_count:
        raise HTTPException(status_code=404, detail="Pending footage request not found")
    return {"request_id": request_id, "status": "declined"}


@api_router.post("/api/v1/footage-requests/{request_id}/approve", dependencies=[Depends(require_demo_api_key)])
async def approve_and_upload(request_id: str, video_file: UploadFile = File()):
    db = database()
    request = await db.footage_requests.find_one({"request_id": request_id, "status": "approval_pending"})
    if not request or not request.get("sha256"):
        raise HTTPException(status_code=409, detail="A preserved hash is required before approval")
    if not (video_file.content_type or "").startswith("video/"):
        raise HTTPException(status_code=415, detail="Upload a video file")

    evidence_id = str(uuid4())
    suffix = Path(video_file.filename or "clip.mp4").suffix.lower() or ".mp4"
    output_path = settings.evidence_directory / f"{evidence_id}{suffix}"
    with output_path.open("wb") as output:
        shutil.copyfileobj(video_file.file, output)
    uploaded_hash = hashlib.sha256(output_path.read_bytes()).hexdigest()
    if uploaded_hash.lower() != request["sha256"].lower():
        output_path.unlink(missing_ok=True)
        await db.footage_requests.update_one(
            {"request_id": request_id},
            {"$set": {"rejected_upload_sha256": uploaded_hash, "rejected_upload_at": utc_now()}, "$inc": {"rejected_upload_count": 1}},
        )
        raise HTTPException(status_code=409, detail="Uploaded video does not match the preserved SHA-256 hash")

    evidence_url = f"/api/v1/evidence/{evidence_id}"
    await db.evidence.insert_one({"evidence_id": evidence_id, "request_id": request_id, "sha256": uploaded_hash, "path": str(output_path), "created_at": utc_now()})
    await db.footage_requests.update_one(
        {"request_id": request_id},
        {"$set": {"status": "approved", "evidence_id": evidence_id, "evidence_url": evidence_url, "received_sha256": uploaded_hash, "decided_at": utc_now()}},
    )
    return {"request_id": request_id, "status": "approved", "evidence_url": evidence_url}


@api_router.get("/api/v1/footage-requests", dependencies=[Depends(require_demo_api_key)])
async def list_footage_requests():
    db = database()
    now = utc_now()
    await db.footage_requests.update_many(
        {"status": "approval_pending", "deadline_at": {"$lte": now}},
        {"$set": {"status": "expired"}},
    )
    requests = await db.footage_requests.find().sort("created_at", DESCENDING).to_list(length=200)
    return {"requests": [public_request(request) for request in requests]}


@api_router.get("/api/v1/devices", dependencies=[Depends(require_demo_api_key)])
async def list_devices():
    """Phone status for the dashboard: latest GPS fix and when the app last reached the API."""
    db = database()
    devices = await db.devices.find({}, {"_id": 0, "fcm_token": 0}).to_list(length=500)
    cursor = await db.gps_pings.aggregate(
        [
            {"$sort": {"timestamp": DESCENDING}},
            {
                "$group": {
                    "_id": "$vehicle_reg_number",
                    "last_ping_at": {"$first": "$timestamp"},
                    "location": {"$first": "$location"},
                    "accuracy_meters": {"$first": "$accuracy_meters"},
                    "last_received_at": {"$max": "$received_at"},
                    "ping_count": {"$sum": 1},
                }
            },
        ],
        allowDiskUse=True,
    )
    latest = {row["_id"]: row for row in await cursor.to_list(length=None)}

    def newest(*values):
        present = [value for value in values if isinstance(value, datetime)]
        return max(present) if present else None

    result = []
    for device in devices:
        vehicle = device.get("vehicle_reg_number")
        ping = latest.get(vehicle, {})
        coordinates = (ping.get("location") or {}).get("coordinates") or [None, None]
        result.append(
            {
                "vehicle_reg_number": vehicle,
                "owner_name": device.get("owner_name") or "Vehicle owner",
                "last_ping_at": api_time(ping.get("last_ping_at")),
                "last_poll_at": api_time(device.get("last_poll_at")),
                "last_contact_at": api_time(newest(ping.get("last_received_at"), device.get("last_poll_at"))),
                "latitude": coordinates[1],
                "longitude": coordinates[0],
                "accuracy_meters": ping.get("accuracy_meters"),
                "ping_count": ping.get("ping_count", 0),
            }
        )
    result.sort(key=lambda item: item["last_contact_at"] or item["last_ping_at"] or "", reverse=True)
    return {"devices": result, "server_time": api_time(utc_now())}


@api_router.get("/api/v1/evidence/{evidence_id}", dependencies=[Depends(require_demo_api_key)])
async def get_evidence(evidence_id: str):
    db = database()
    evidence = await db.evidence.find_one({"evidence_id": evidence_id})
    if not evidence or not Path(evidence["path"]).is_file():
        raise HTTPException(status_code=404, detail="Evidence file not found")
    return FileResponse(evidence["path"], media_type="video/mp4", filename=Path(evidence["path"]).name)


# Serve dashboard files only from the local dashboard app. Do not mount the
# repository root: backend/.env and other private files live in this project.
WEB_ROOT = Path(__file__).resolve().parents[2]
# Without this, browsers keep using old dashboard files after an update.
REVALIDATE = {"Cache-Control": "no-cache"}


@app.get("/", include_in_schema=False)
async def dashboard_index():
    return FileResponse(WEB_ROOT / "index.html", media_type="text/html", headers=REVALIDATE)


@app.get("/index.html", include_in_schema=False)
async def dashboard_index_alias():
    return FileResponse(WEB_ROOT / "index.html", media_type="text/html", headers=REVALIDATE)


@app.get("/app.js", include_in_schema=False)
async def dashboard_script():
    return FileResponse(WEB_ROOT / "app.js", media_type="text/javascript", headers=REVALIDATE)


@app.get("/styles.css", include_in_schema=False)
async def dashboard_styles():
    return FileResponse(WEB_ROOT / "styles.css", media_type="text/css", headers=REVALIDATE)


@app.get("/evidence-library.html", include_in_schema=False)
async def evidence_library():
    return FileResponse(WEB_ROOT / "evidence-library.html", media_type="text/html", headers=REVALIDATE)


# Cloudflare Tunnel must target this API-only app at http://localhost:8001.
# API docs/OpenAPI are disabled on the public listener.
api_app = FastAPI(
    title="SurakshaNethra Demo API",
    version="0.1.0",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)
api_app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-Demo-API-Key"],
)
api_app.include_router(api_router)


