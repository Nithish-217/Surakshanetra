from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    mongodb_uri: str
    mongodb_database: str = "surakshanethra_demo"
    demo_api_key: str
    allowed_origins: str = "http://127.0.0.1:8000,http://localhost:8000,http://127.0.0.1:5500"
    evidence_directory: Path = Path("evidence_uploads")
    firebase_service_account_path: str = ""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @property
    def cors_origins(self) -> list[str]:
        return [origin.strip() for origin in self.allowed_origins.split(",") if origin.strip()]


settings = Settings()


