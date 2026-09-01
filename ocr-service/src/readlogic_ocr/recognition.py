from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from threading import Lock
from typing import Any, Protocol

import cv2
import numpy as np

from readlogic_ocr.errors import OcrInferenceError

SUPPORTED_LANGUAGES = frozenset({"auto", "en", "ja", "ko", "zh"})
RECOGNITION_MODELS = {
    "ko": "korean_PP-OCRv5_mobile_rec",
    "en": "en_PP-OCRv5_mobile_rec",
    "cjk": "PP-OCRv5_server_rec",
}
LANGUAGE_MODEL_KEYS = {"ko": "ko", "en": "en", "ja": "cjk", "zh": "cjk"}
TEXT_LINE_ORIENTATION_MODEL = "PP-LCNet_x1_0_textline_ori"


@dataclass(frozen=True)
class RecognitionCandidate:
    text: str
    confidence: float
    language: str
    model: str


class BatchTextRecognizer(Protocol):
    def recognize(
        self,
        images: Sequence[np.ndarray],
        language: str,
        model: str,
    ) -> tuple[RecognitionCandidate, ...]: ...


class TextLineOrienter(Protocol):
    def orient(self, images: Sequence[np.ndarray]) -> tuple[np.ndarray, ...]: ...


class PaddleBatchTextRecognizer:
    def __init__(self, model_name: str) -> None:
        from paddleocr import TextRecognition

        self._model = TextRecognition(model_name=model_name)

    def recognize(
        self,
        images: Sequence[np.ndarray],
        language: str,
        model: str,
    ) -> tuple[RecognitionCandidate, ...]:
        try:
            results = list(self._model.predict(list(images))) if images else []
            if len(results) != len(images):
                raise OcrInferenceError()
            return tuple(
                RecognitionCandidate(
                    text=str(_get_value(result, "rec_text")).strip(),
                    confidence=_clamp_confidence(_get_value(result, "rec_score")),
                    language=language,
                    model=model,
                )
                for result in results
            )
        except OcrInferenceError:
            raise
        except Exception as exception:
            raise OcrInferenceError() from exception


class PaddleTextLineOrienter:
    def __init__(self) -> None:
        from paddleocr import TextLineOrientationClassification

        self._model = TextLineOrientationClassification(
            model_name=TEXT_LINE_ORIENTATION_MODEL
        )

    def orient(self, images: Sequence[np.ndarray]) -> tuple[np.ndarray, ...]:
        if not images:
            return ()
        try:
            results = list(self._model.predict(list(images)))
            if len(results) != len(images):
                raise OcrInferenceError()
            return tuple(
                cv2.rotate(image, cv2.ROTATE_180)
                if _first_class_id(result) == 1
                else image
                for image, result in zip(images, results, strict=True)
            )
        except OcrInferenceError:
            raise
        except Exception as exception:
            raise OcrInferenceError() from exception


class RecognitionRegistry:
    def __init__(
        self,
        factory: Callable[[str], BatchTextRecognizer] = PaddleBatchTextRecognizer,
        preload_languages: Sequence[str] = ("ko",),
    ) -> None:
        self._factory = factory
        self._models: dict[str, BatchTextRecognizer] = {}
        self._locks = {key: Lock() for key in RECOGNITION_MODELS}
        for language in preload_languages:
            if language != "auto":
                self.get(language)

    def get(self, language: str) -> BatchTextRecognizer:
        key = model_key_for_language(language)
        model = self._models.get(key)
        if model is not None:
            return model
        with self._locks[key]:
            model = self._models.get(key)
            if model is None:
                model = self._factory(RECOGNITION_MODELS[key])
                self._models[key] = model
            return model

    def recognize(
        self,
        images: Sequence[np.ndarray],
        language: str,
    ) -> tuple[RecognitionCandidate, ...]:
        key = model_key_for_language(language)
        model_name = RECOGNITION_MODELS[key]
        return self.get(language).recognize(images, language, model_name)

    @property
    def loaded_model_names(self) -> tuple[str, ...]:
        return tuple(RECOGNITION_MODELS[key] for key in sorted(self._models))


def model_key_for_language(language: str) -> str:
    try:
        return LANGUAGE_MODEL_KEYS[language]
    except KeyError as exception:
        raise ValueError(f"Unsupported explicit OCR language: {language}") from exception


def _get_value(result: Any, key: str) -> Any:
    try:
        return result[key]
    except (KeyError, TypeError) as exception:
        raise OcrInferenceError() from exception


def _clamp_confidence(value: Any) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError) as exception:
        raise OcrInferenceError() from exception


def _first_class_id(result: Any) -> int:
    values = _get_value(result, "class_ids")
    try:
        class_ids = np.asarray(values).reshape(-1)
    except (TypeError, ValueError) as exception:
        raise OcrInferenceError() from exception
    if class_ids.size == 0:
        raise OcrInferenceError()
    try:
        return int(class_ids[0])
    except (TypeError, ValueError, OverflowError) as exception:
        raise OcrInferenceError() from exception
