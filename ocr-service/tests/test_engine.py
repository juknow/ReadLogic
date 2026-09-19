import numpy as np
import pytest

from readlogic_ocr.engine import PaddleOcrEngine
from readlogic_ocr.errors import OcrInferenceError


class FakePipeline:
    def __init__(self, results: list[dict[str, object]]) -> None:
        self.results = results

    def predict(self, _: np.ndarray) -> list[dict[str, object]]:
        return self.results


def create_engine(results: list[dict[str, object]]) -> PaddleOcrEngine:
    engine = PaddleOcrEngine.__new__(PaddleOcrEngine)
    engine._pipeline = FakePipeline(results)  # type: ignore[attr-defined]
    return engine


def test_orders_regions_into_lines_and_averages_confidence() -> None:
    engine = create_engine(
        [
            {
                "rec_texts": ["둘째", "오른쪽", "첫째"],
                "rec_scores": [0.8, 0.7, 1.0],
                "dt_scores": [0.9, 0.85, 0.99],
                "rec_polys": [
                    [[0, 40], [40, 40], [40, 60], [0, 60]],
                    [[100, 0], [160, 0], [160, 20], [100, 20]],
                    [[0, 0], [40, 0], [40, 20], [0, 20]],
                ],
            }
        ]
    )

    result = engine.recognize(np.zeros((100, 200, 3), dtype=np.uint8))

    assert result.text == "첫째 오른쪽\n둘째"
    assert result.confidence == 0.8333
    assert result.regions[0].bbox == (0.0, 40.0, 40.0, 60.0)
    assert result.regions[0].polygon == (
        (0.0, 40.0),
        (40.0, 40.0),
        (40.0, 60.0),
        (0.0, 60.0),
    )
    assert result.regions[0].detection_confidence == 0.9
    assert result.regions[0].model == "korean_PP-OCRv5_mobile_rec"


def test_returns_empty_result_when_no_text_was_detected() -> None:
    engine = create_engine(
        [{"rec_texts": [], "rec_scores": [], "rec_polys": []}]
    )

    result = engine.recognize(np.zeros((20, 20, 3), dtype=np.uint8))

    assert result.text == ""
    assert result.confidence is None
    assert result.image_size == (20, 20)


def test_rejects_malformed_model_result() -> None:
    engine = create_engine(
        [{"rec_texts": ["문장"], "rec_scores": [], "rec_polys": []}]
    )

    with pytest.raises(OcrInferenceError):
        engine.recognize(np.zeros((20, 20, 3), dtype=np.uint8))


def test_rejects_mismatched_detection_scores() -> None:
    engine = create_engine(
        [
            {
                "rec_texts": ["문장"],
                "rec_scores": [0.9],
                "rec_polys": [[[0, 0], [10, 0], [10, 10], [0, 10]]],
                "dt_scores": [],
            }
        ]
    )

    with pytest.raises(OcrInferenceError):
        engine.recognize(np.zeros((20, 20, 3), dtype=np.uint8))
