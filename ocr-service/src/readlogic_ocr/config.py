from functools import lru_cache

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from readlogic_ocr.recognition import SUPPORTED_LANGUAGES


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OCR_SERVICE_", extra="ignore")

    engine_name: str = "paddleocr"
    max_concurrency: int = Field(default=1, ge=1)
    max_image_pixels: int = Field(default=40_000_000, ge=1)
    max_image_size_bytes: int = Field(default=10 * 1024 * 1024, ge=1)
    model_name: str = "PP-OCRv5-korean"
    preload_languages: str = "ko,en,ja,zh"
    stage_timeout_seconds: float = Field(default=105.0, gt=0)

    @field_validator("preload_languages")
    @classmethod
    def validate_preload_languages(cls, value: str) -> str:
        languages = tuple(
            dict.fromkeys(
                language.strip().lower()
                for language in value.split(",")
                if language.strip()
            )
        )
        invalid = set(languages) - (SUPPORTED_LANGUAGES - {"auto"})
        if invalid:
            raise ValueError("preload languages must be selected from ko, en, ja, and zh")
        return ",".join(languages)

    @property
    def preload_language_values(self) -> tuple[str, ...]:
        return tuple(language for language in self.preload_languages.split(",") if language)


@lru_cache
def get_settings() -> Settings:
    return Settings()
