import pytest
from pydantic import ValidationError

from readlogic_ocr.config import Settings


def test_preload_languages_are_normalized_and_deduplicated() -> None:
    settings = Settings(preload_languages=" KO, en, ja, zh, zh ")

    assert settings.preload_language_values == ("ko", "en", "ja", "zh")


def test_auto_and_unknown_languages_cannot_be_preloaded() -> None:
    with pytest.raises(ValidationError):
        Settings(preload_languages="ko,auto")

    with pytest.raises(ValidationError):
        Settings(preload_languages="ko,fr")
