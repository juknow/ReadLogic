from collections.abc import Sequence

import numpy as np

from readlogic_ocr.detection import DetectedRegion
from readlogic_ocr.engine import PaddleOcrEngine
from readlogic_ocr.preprocessing import identity_preprocessing
from readlogic_ocr.recognition import RecognitionCandidate, RecognitionRegistry


class FakePreprocessor:
    def correct(self, image: np.ndarray):
        return identity_preprocessing(image)


class FakeDetector:
    def detect(self, _: np.ndarray) -> tuple[DetectedRegion, ...]:
        return (
            DetectedRegion(((0, 0), (60, 0), (60, 20), (0, 20)), 0.98),
        )


class IdentityOrienter:
    def orient(self, images: Sequence[np.ndarray]) -> tuple[np.ndarray, ...]:
        return tuple(images)


class FakeRecognizer:
    def __init__(self, model_name: str) -> None:
        self.model_name = model_name

    def recognize(
        self,
        images: Sequence[np.ndarray],
        language: str,
        model: str,
    ) -> tuple[RecognitionCandidate, ...]:
        return tuple(
            RecognitionCandidate(f"{language} text", 0.91, language, model)
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

    assert result.text == "ja text"
    assert result.requested_language == "ja"
    assert result.detected_language == "ja"
    assert result.regions[0].detection_confidence == 0.98
    assert result.primary_model == "PP-OCRv5_server_rec"
