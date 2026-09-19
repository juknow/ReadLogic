from collections.abc import Sequence

import numpy as np
import pytest

from readlogic_ocr.detection import DetectedRegion
from readlogic_ocr.engine import PaddleOcrEngine
from readlogic_ocr.errors import OcrTimeoutError
from readlogic_ocr.models import CorrectionMetadata
from readlogic_ocr.preprocessing import PreprocessingResult, identity_preprocessing
from readlogic_ocr.recognition import (
    RecognitionCandidate,
    RecognitionRegistry,
    TextLineOrientationResult,
)


class FakePreprocessor:
    def correct(self, image: np.ndarray):
        return identity_preprocessing(image)


class FakeDetector:
    def detect(self, _: np.ndarray) -> tuple[DetectedRegion, ...]:
        return (
            DetectedRegion(((0, 0), (60, 0), (60, 20), (0, 20)), 0.98),
        )


class IdentityOrienter:
    def orient(self, images: Sequence[np.ndarray]) -> TextLineOrientationResult:
        return TextLineOrientationResult(tuple(images))


class FakeRecognizer:
    def __init__(self, model_name: str) -> None:
        self.model_name = model_name

    def recognize(
        self,
        images: Sequence[np.ndarray],
        language: str,
        model: str,
    ) -> tuple[RecognitionCandidate, ...]:
        texts = {"en": "English text", "ja": "日本語の文", "ko": "한국어 문장", "zh": "中文句子"}
        return tuple(
            RecognitionCandidate(texts[language], 0.91, language, model)
            for _ in images
        )


def test_routes_explicit_language_after_shared_detection() -> None:
    registry = RecognitionRegistry(factory=FakeRecognizer, preload_languages=())
    engine = PaddleOcrEngine(
        preprocessor=FakePreprocessor(),
        detector=FakeDetector(),
        orienter=IdentityOrienter(),
        registry=registry,
    )

    result = engine.recognize(np.zeros((50, 100, 3), dtype=np.uint8), "ja")

    assert result.text == "日本語の文"
    assert result.requested_language == "ja"
    assert result.detected_language == "ja"
    assert result.regions[0].detection_confidence == 0.98
    assert result.primary_model == "PP-OCRv5_server_rec"


def test_stage_deadline_returns_retryable_timeout() -> None:
    engine = PaddleOcrEngine(
        preprocessor=FakePreprocessor(),
        detector=FakeDetector(),
        orienter=IdentityOrienter(),
        registry=RecognitionRegistry(factory=FakeRecognizer, preload_languages=()),
        stage_timeout_seconds=0.000000001,
    )

    with pytest.raises(OcrTimeoutError) as raised:
        engine.recognize(np.zeros((50, 100, 3), dtype=np.uint8), "ko")

    assert raised.value.code == "OCR_TIMEOUT"
    assert raised.value.status_code == 504


def test_retries_original_image_when_unwarping_detects_no_text() -> None:
    class UnwarpingPreprocessor:
        def correct(self, image: np.ndarray) -> PreprocessingResult:
            return PreprocessingResult(
                image=np.full_like(image, 255),
                fallback_image=image,
                correction=CorrectionMetadata(unwarping_applied=True),
            )

    class ImageAwareDetector:
        def detect(self, image: np.ndarray) -> tuple[DetectedRegion, ...]:
            if int(image.mean()) > 0:
                return ()
            return (DetectedRegion(((0, 0), (60, 0), (60, 20), (0, 20)), 0.98),)

    engine = PaddleOcrEngine(
        preprocessor=UnwarpingPreprocessor(),
        detector=ImageAwareDetector(),
        orienter=IdentityOrienter(),
        registry=RecognitionRegistry(factory=FakeRecognizer, preload_languages=()),
    )

    result = engine.recognize(np.zeros((50, 100, 3), dtype=np.uint8), "ko")

    assert result.text == "한국어 문장"
    assert result.correction.fallback_used
    assert not result.correction.unwarping_applied
    assert any(warning.code == "DOCUMENT_UNWARPING_FALLBACK" for warning in result.warnings)


def test_reorders_lines_when_text_line_orientation_corrects_upside_down_page() -> None:
    class TwoLineDetector:
        def detect(self, _: np.ndarray) -> tuple[DetectedRegion, ...]:
            return (
                DetectedRegion(((0, 0), (60, 0), (60, 20), (0, 20)), 0.98),
                DetectedRegion(((0, 30), (60, 30), (60, 50), (0, 50)), 0.98),
            )

    class UpsideDownOrienter:
        def orient(self, images: Sequence[np.ndarray]) -> TextLineOrientationResult:
            return TextLineOrientationResult(tuple(images), page_rotation_degrees=180)

    class OrderedRecognizer(FakeRecognizer):
        def recognize(
            self,
            images: Sequence[np.ndarray],
            language: str,
            model: str,
        ) -> tuple[RecognitionCandidate, ...]:
            texts = ("두 번째 줄", "첫 번째 줄")
            return tuple(
                RecognitionCandidate(text, 0.91, language, model)
                for text, _ in zip(texts, images, strict=True)
            )

    engine = PaddleOcrEngine(
        preprocessor=FakePreprocessor(),
        detector=TwoLineDetector(),
        orienter=UpsideDownOrienter(),
        registry=RecognitionRegistry(factory=OrderedRecognizer, preload_languages=()),
    )

    result = engine.recognize(np.zeros((60, 100, 3), dtype=np.uint8), "ko")

    assert [line.text for line in result.lines] == ["첫 번째 줄", "두 번째 줄"]
    assert result.text == "첫 번째 줄 두 번째 줄"
    assert result.correction.orientation_applied
    assert result.correction.rotation_degrees == 180
