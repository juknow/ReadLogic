from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OCR_SERVICE_", extra="ignore")

    engine_name: str = "paddleocr"
    max_concurrency: int = 1
    max_image_pixels: int = 40_000_000
    max_image_size_bytes: int = 10 * 1024 * 1024
    model_name: str = "PP-OCRv5-korean"


@lru_cache
def get_settings() -> Settings:
    return Settings()
