from collections.abc import Sequence

import numpy as np

from readlogic_ocr.recognition import (
    RECOGNITION_MODELS,
    PaddleTextLineOrienter,
    RecognitionCandidate,
    RecognitionRegistry,
    model_key_for_language,
)


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
            RecognitionCandidate("text", 0.9, language, model) for _ in images
        )


def test_maps_supported_languages_to_expected_models() -> None:
    assert model_key_for_language("ko") == "ko"
    assert model_key_for_language("en") == "en"
    assert model_key_for_language("ja") == "cjk"
    assert model_key_for_language("zh") == "cjk"


def test_registry_shares_cjk_model_and_does_not_load_duplicates() -> None:
    created: list[str] = []

    def create(model_name: str) -> FakeRecognizer:
        created.append(model_name)
        return FakeRecognizer(model_name)

    registry = RecognitionRegistry(factory=create, preload_languages=())
    crop = np.zeros((10, 20, 3), dtype=np.uint8)

    japanese = registry.recognize((crop,), "ja")
    chinese = registry.recognize((crop,), "zh")

    assert japanese[0].model == RECOGNITION_MODELS["cjk"]
    assert chinese[0].model == RECOGNITION_MODELS["cjk"]
    assert created == [RECOGNITION_MODELS["cjk"]]


def test_text_line_orienter_accepts_numpy_class_ids() -> None:
    class FakeOrientationModel:
        def predict(self, images: list[np.ndarray]) -> list[dict[str, np.ndarray]]:
            return [
                {"class_ids": np.asarray([class_id], dtype=np.int32)}
                for class_id, _ in enumerate(images)
            ]

    orienter = PaddleTextLineOrienter.__new__(PaddleTextLineOrienter)
    orienter._model = FakeOrientationModel()
    first = np.arange(12, dtype=np.uint8).reshape(2, 2, 3)
    second = np.arange(12, 24, dtype=np.uint8).reshape(2, 2, 3)

    oriented = orienter.orient((first, second))

    assert np.array_equal(oriented[0], first)
    assert np.array_equal(oriented[1], np.rot90(second, 2))
