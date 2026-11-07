from datetime import datetime

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class Location(BaseModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class DeviceRegistration(BaseModel):
    device_id: str = Field(min_length=4, max_length=128)
    vehicle_reg_number: str = Field(min_length=3, max_length=32)
    owner_name: str = Field(min_length=1, max_length=120)
    fcm_token: str = Field(min_length=10, max_length=4096)

    @field_validator("vehicle_reg_number")
    @classmethod
    def normalise_vehicle_number(cls, value: str) -> str:
        return value.replace(" ", "").upper()


class RecordingStart(Location):
    recording_id: str = Field(min_length=8, max_length=128)
    device_id: str = Field(min_length=4, max_length=128)
    vehicle_reg_number: str = Field(min_length=3, max_length=32)
    started_at: datetime

    @field_validator("vehicle_reg_number")
    @classmethod
    def normalise_vehicle_number(cls, value: str) -> str:
        return value.replace(" ", "").upper()


class TelemetryPing(Location):
    recording_id: str = Field(min_length=8, max_length=128)
    device_id: str = Field(min_length=4, max_length=128)
    vehicle_reg_number: str = Field(min_length=3, max_length=32)
    timestamp: datetime


class GeoJsonPoint(BaseModel):
    type: Literal["Point"]
    coordinates: tuple[float, float]

    @field_validator("coordinates")
    @classmethod
    def validate_coordinates(cls, value: tuple[float, float]) -> tuple[float, float]:
        longitude, latitude = value
        if not -180 <= longitude <= 180 or not -90 <= latitude <= 90:
            raise ValueError("Invalid GeoJSON longitude/latitude")
        return value


class MobileLocationPing(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    ping_id: str = Field(alias="pingId", min_length=16, max_length=80)
    device_id: str = Field(alias="deviceId", min_length=4, max_length=128)
    vehicle_number: str = Field(alias="vehicleNumber", min_length=3, max_length=32)
    owner_name: str = Field(default="Vehicle owner", alias="ownerName", max_length=120)
    recording_id: str | None = Field(default=None, alias="recordingId", max_length=128)
    recorded_at: datetime = Field(alias="recordedAt")
    location: GeoJsonPoint
    accuracy_meters: float = Field(alias="accuracyMeters", ge=0, le=100_000)

    @field_validator("vehicle_number")
    @classmethod
    def normalise_vehicle_number(cls, value: str) -> str:
        return value.replace(" ", "").upper()


class MobileLocationPingBatch(BaseModel):
    pings: list[MobileLocationPing] = Field(min_length=1, max_length=500)


class RecordingStop(BaseModel):
    ended_at: datetime
    duration_seconds: int = Field(gt=0, le=86_400)
    has_audio: bool = True


class MatchSearch(Location):
    radius_meters: int = Field(default=1000, gt=0, le=10_000)
    time_start: datetime
    time_end: datetime


class FootageRequestCreate(MatchSearch):
    vehicle_reg_numbers: list[str] = Field(min_length=1, max_length=20)
    requester_name: str = Field(default="Authorized Viewer", min_length=1, max_length=120)
    requester_organization: str = Field(default="SurakshaNethra Demo", min_length=1, max_length=120)

    @field_validator("vehicle_reg_numbers")
    @classmethod
    def normalise_vehicles(cls, values: list[str]) -> list[str]:
        return list(dict.fromkeys(value.replace(" ", "").upper() for value in values))


class PreserveEvidence(BaseModel):
    recording_id: str = Field(min_length=8, max_length=128)
    sha256: str = Field(pattern=r"^[a-fA-F0-9]{64}$")
    duration_seconds: int = Field(gt=0, le=86_400)
    trimmed_at: datetime


class DeclineRequest(BaseModel):
    justification: str = Field(min_length=3, max_length=1000)
