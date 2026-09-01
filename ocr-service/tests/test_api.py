from io import BytesIO
from threading import Event
from typing import cast
from uuid import UUID

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from readlogic_ocr.config import Settings
from readlogic_ocr.engine import EngineResult, OcrEngine
from readlogic_ocr.errors import OcrBusyError
from readlogic_ocr.main import create_app
from readlogic_ocr.runtime import RuntimeState


class FakeEngine:
    def __init__(self, result: EngineResult | None = None) -> None:
        self.result = result or EngineResult(
            confidence=0.9123,
            text="인식된 문장",
            image_size=(20, 20),
        )
        self.requests = 0
        self.languages: list[str] = []

    def recognize(self, _: np.ndarray, language: str = "ko") -> EngineResult:
        self.requests += 1
        self.languages.append(language)
        return self.result


def create_image(
    image_format: str = "PNG",
    size: tuple[int, int] = (20, 20),
) -> bytes:
    content = BytesIO()
    Image.new("RGB", size, "white").save(content, image_format)
    return content.getvalue()


def create_client(
    engine: FakeEngine,
    **settings_overrides: int,
) -> TestClient:
    settings = Settings(**settings_overrides)
    return TestClient(create_app(settings=settings, engine_factory=lambda: engine))


def recognize(
    client: TestClient,
    content: bytes,
    content_type: str = "image/png",
    language: str | None = None,
):
    data = {} if language is None else {"language": language}
    return client.post(
        "/internal/v1/ocr",
        headers={"X-Ocr-Request-Id": str(UUID(int=1))},
        files={"image": ("page", content, content_type)},
        data=data,
    )


def test_health_and_ocr_contract_load_engine_once() -> None:
    engine = FakeEngine()
    with create_client(engine) as client:
        assert client.get("/health/live").json() == {"status": "UP"}
        assert client.get("/health/ready").json() == {
            "status": "UP",
            "engine": "paddleocr",
            "model": "PP-OCRv5-korean",
        }

        first_response = recognize(client, create_image())
        second_response = recognize(client, create_image())

    assert first_response.status_code == 200
    assert first_response.json() == {
        "text": "인식된 문장",
        "confidence": 0.9123,
        "engine": "paddleocr",
        "model": "korean_PP-OCRv5_mobile_rec",
        "processingTimeMs": first_response.json()["processingTimeMs"],
        "document": {
            "schemaVersion": 1,
            "requestedLanguage": "ko",
            "detectedLanguage": "ko",
            "coordinateSpace": "corrected_image",
            "image": {"width": 20, "height": 20},
            "correction": {
                "exifApplied": True,
                "orientationApplied": False,
                "rotationDegrees": 0,
                "unwarpingApplied": False,
                "fallbackUsed": False,
            },
            "models": {
                "orientation": "PP-LCNet_x1_0_doc_ori",
                "unwarping": "UVDoc",
                "detector": "PP-OCRv5_server_det",
                "textLineOrientation": "PP-LCNet_x1_0_textline_ori",
                "recognizers": ["korean_PP-OCRv5_mobile_rec"],
            },
            "warnings": [
                {
                    "code": "NO_TEXT_DETECTED",
                    "message": "No text was detected in the image.",
                }
            ],
            "paragraphs": [],
        },
    }
    assert second_response.status_code == 200
    assert engine.requests == 2
    assert engine.languages == ["ko", "ko"]


def test_empty_ocr_result_is_successful() -> None:
    engine = FakeEngine(EngineResult(confidence=None, text=""))
    with create_client(engine) as client:
        response = recognize(client, create_image())

    assert response.status_code == 200
    assert response.json()["text"] == ""
    assert response.json()["confidence"] is None


def test_accepts_supported_language_and_rejects_unknown_language() -> None:
    engine = FakeEngine()
    with create_client(engine) as client:
        japanese = recognize(client, create_image(), language="ja")
        invalid = recognize(client, create_image(), language="fr")

    assert japanese.status_code == 200
    assert engine.languages == ["ja"]
    assert invalid.status_code == 400
    assert invalid.json()["code"] == "INVALID_OCR_LANGUAGE"


@pytest.mark.parametrize(
    ("content_type", "expected_status", "expected_code"),
    [
        ("application/pdf", 415, "UNSUPPORTED_IMAGE_TYPE"),
        ("image/gif", 415, "UNSUPPORTED_IMAGE_TYPE"),
    ],
)
def test_rejects_unsupported_image_types(
    content_type: str,
    expected_status: int,
    expected_code: str,
) -> None:
    with create_client(FakeEngine()) as client:
        response = recognize(client, b"not-an-image", content_type)

    assert response.status_code == expected_status
    assert response.json()["code"] == expected_code


def test_rejects_oversized_upload() -> None:
    with create_client(FakeEngine(), max_image_size_bytes=3) as client:
        response = recognize(client, b"four", "image/png")

    assert response.status_code == 400
    assert response.json()["code"] == "IMAGE_TOO_LARGE"


def test_rejects_excessive_decoded_pixels() -> None:
    with create_client(FakeEngine(), max_image_pixels=100) as client:
        response = recognize(client, create_image(size=(11, 10)))

    assert response.status_code == 400
    assert response.json()["code"] == "IMAGE_TOO_LARGE"


def test_rejects_damaged_image_without_exposing_content() -> None:
    with create_client(FakeEngine()) as client:
        response = recognize(client, b"private-page-content")

    assert response.status_code == 400
    assert response.json() == {
        "code": "INVALID_IMAGE",
        "message": "The uploaded image cannot be decoded.",
    }
    assert "private-page-content" not in response.text


def test_runtime_rejects_parallel_inference() -> None:
    started = Event()
    release = Event()

    class BlockingEngine:
        def recognize(self, _: np.ndarray, language: str = "ko") -> EngineResult:
            started.set()
            release.wait(timeout=2)
            return EngineResult(None, "")

    runtime = RuntimeState("paddleocr", "model", 1, BlockingEngine)
    runtime.initialize()
    from concurrent.futures import ThreadPoolExecutor

    with ThreadPoolExecutor(max_workers=1) as executor:
        first_request = executor.submit(runtime.recognize, np.zeros((1, 1, 3)))
        assert started.wait(timeout=1)
        with pytest.raises(OcrBusyError):
            runtime.recognize(np.zeros((1, 1, 3)))
        release.set()
        assert first_request.result(timeout=1).text == ""

    assert cast(OcrEngine, runtime._engine) is not None  # noqa: SLF001
