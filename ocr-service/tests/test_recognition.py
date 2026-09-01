from collections.abc import Sequence

import numpy as np

from readlogic_ocr.recognition import (
    RECOGNITION_MODELS,
    LanguageRouter,
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


class ScriptRecognizer:
    def __init__(self, model_name: str) -> None:
        self.model_name = model_name

    def recognize(
        self,
        images: Sequence[np.ndarray],
        language: str,
        model: str,
    ) -> tuple[RecognitionCandidate, ...]:
        values = {
            "ko": ("독서는 생각을 확장한다", 0.94),
            "en": ("reading expands thought", 0.72),
            "ja": ("読書は思考を広げる", 0.70),
        }
        text, confidence = values[language]
        return tuple(
            RecognitionCandidate(text, confidence, language, model) for _ in images
        )


def test_auto_routing_preserves_high_confidence_korean_result() -> None:
    registry = RecognitionRegistry(factory=ScriptRecognizer, preload_languages=())
    router = LanguageRouter(registry)
    crops = tuple(np.zeros((10, 20, 3), dtype=np.uint8) for _ in range(7))

    result = router.recognize(crops, "auto")

    assert result.detected_language == "ko"
    assert all(candidate.language == "ko" for candidate in result.candidates)
    assert result.primary_model == RECOGNITION_MODELS["ko"]


class FallbackRecognizer:
    def __init__(self, model_name: str) -> None:
        self.model_name = model_name

    def recognize(
        self,
        images: Sequence[np.ndarray],
        language: str,
        model: str,
    ) -> tuple[RecognitionCandidate, ...]:
        text, confidence = ("", 0.1) if language == "en" else ("한국어 문장", 0.95)
        return tuple(
            RecognitionCandidate(text, confidence, language, model) for _ in images
        )


def test_low_confidence_explicit_result_uses_one_fallback_model() -> None:
    registry = RecognitionRegistry(factory=FallbackRecognizer, preload_languages=())
    result = LanguageRouter(registry).recognize(
        (np.zeros((10, 20, 3), dtype=np.uint8),),
        "en",
    )

    assert result.candidates[0].text == "한국어 문장"
    assert result.detected_language == "ko"
    assert result.warnings[0].code == "LANGUAGE_FALLBACK_APPLIED"


def test_han_only_auto_result_is_reported_as_uncertain() -> None:
    class HanRecognizer(ScriptRecognizer):
        def recognize(
            self,
            images: Sequence[np.ndarray],
            language: str,
            model: str,
        ) -> tuple[RecognitionCandidate, ...]:
            confidence = 0.95 if language == "ja" else 0.6
            return tuple(
                RecognitionCandidate("人工知能", confidence, language, model)
                for _ in images
            )

    registry = RecognitionRegistry(factory=HanRecognizer, preload_languages=())
    result = LanguageRouter(registry).recognize(
        (np.zeros((10, 20, 3), dtype=np.uint8),),
        "auto",
    )

    assert result.detected_language == "und"
    assert any(warning.code == "CJK_LANGUAGE_UNCERTAIN" for warning in result.warnings)
