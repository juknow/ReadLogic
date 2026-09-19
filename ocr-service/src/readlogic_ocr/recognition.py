from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from threading import Lock
from typing import Any, Protocol

import cv2
import numpy as np

from readlogic_ocr.errors import OcrInferenceError
from readlogic_ocr.models import OcrWarning

SUPPORTED_LANGUAGES = frozenset({"auto", "en", "ja", "ko", "zh"})
RECOGNITION_MODELS = {
    "ko": "korean_PP-OCRv5_mobile_rec",
    "en": "en_PP-OCRv5_mobile_rec",
    "cjk": "PP-OCRv5_server_rec",
}
LANGUAGE_MODEL_KEYS = {"ko": "ko", "en": "en", "ja": "cjk", "zh": "cjk"}
TEXT_LINE_ORIENTATION_MODEL = "PP-LCNet_x1_0_textline_ori"
LOW_CONFIDENCE_THRESHOLD = 0.70
AUTO_ROUTE_MARGIN = 0.10
FALLBACK_IMPROVEMENT = 0.05
MAX_AUTO_SAMPLES = 5


@dataclass(frozen=True)
class RecognitionCandidate:
    text: str
    confidence: float
    language: str
    model: str


@dataclass(frozen=True)
class RoutingResult:
    candidates: tuple[RecognitionCandidate, ...]
    requested_language: str
    detected_language: str
    primary_model: str
    warnings: tuple[OcrWarning, ...] = ()


@dataclass(frozen=True)
class TextLineOrientationResult:
    images: tuple[np.ndarray, ...]
    page_rotation_degrees: int = 0


class BatchTextRecognizer(Protocol):
    def recognize(
        self,
        images: Sequence[np.ndarray],
        language: str,
        model: str,
    ) -> tuple[RecognitionCandidate, ...]: ...


class TextLineOrienter(Protocol):
    def orient(self, images: Sequence[np.ndarray]) -> TextLineOrientationResult: ...


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

    def orient(self, images: Sequence[np.ndarray]) -> TextLineOrientationResult:
        if not images:
            return TextLineOrientationResult(())
        try:
            results = list(self._model.predict(list(images)))
            if len(results) != len(images):
                raise OcrInferenceError()
            classifications = tuple(
                (_first_class_id(result), _first_score(result)) for result in results
            )
            oriented_images = tuple(
                cv2.rotate(image, cv2.ROTATE_180)
                if class_id == 1
                else image
                for image, (class_id, _) in zip(images, classifications, strict=True)
            )
            return TextLineOrientationResult(
                images=oriented_images,
                page_rotation_degrees=_dominant_page_rotation(classifications),
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


class LanguageRouter:
    def __init__(self, registry: RecognitionRegistry) -> None:
        self._registry = registry

    def recognize(
        self,
        images: Sequence[np.ndarray],
        requested_language: str,
    ) -> RoutingResult:
        if requested_language not in SUPPORTED_LANGUAGES:
            raise ValueError(f"Unsupported OCR language: {requested_language}")
        if not images:
            primary_key = "ko" if requested_language == "auto" else model_key_for_language(
                requested_language
            )
            return RoutingResult(
                candidates=(),
                requested_language=requested_language,
                detected_language="und",
                primary_model=RECOGNITION_MODELS[primary_key],
            )
        if requested_language == "auto":
            return self._recognize_auto(images)
        return self._recognize_explicit(images, requested_language)

    def _recognize_explicit(
        self,
        images: Sequence[np.ndarray],
        requested_language: str,
    ) -> RoutingResult:
        primary = self._registry.recognize(images, requested_language)
        low_indices = [
            index
            for index, candidate in enumerate(primary)
            if not candidate.text or candidate.confidence < LOW_CONFIDENCE_THRESHOLD
        ]
        selected = list(primary)
        warnings: list[OcrWarning] = []
        if low_indices:
            fallback_language = _fallback_language(requested_language, primary)
            fallback_images = [images[index] for index in low_indices]
            alternatives = self._registry.recognize(fallback_images, fallback_language)
            applied = False
            for index, alternative in zip(low_indices, alternatives, strict=True):
                if _prefer_alternative(selected[index], alternative):
                    selected[index] = alternative
                    applied = True
            if applied:
                warnings.append(
                    OcrWarning(
                        code="LANGUAGE_FALLBACK_APPLIED",
                        message=(
                            "Low-confidence text lines were retried with an alternative "
                            "recognizer."
                        ),
                    )
                )
        detected, detection_warnings = _detect_document_language(
            tuple(selected), requested_language
        )
        warnings.extend(detection_warnings)
        return RoutingResult(
            candidates=tuple(selected),
            requested_language=requested_language,
            detected_language=detected,
            primary_model=RECOGNITION_MODELS[model_key_for_language(requested_language)],
            warnings=tuple(warnings),
        )

    def _recognize_auto(self, images: Sequence[np.ndarray]) -> RoutingResult:
        sample_indices = _sample_indices(len(images))
        sample_images = [images[index] for index in sample_indices]
        candidate_languages = ("ko", "en", "ja")
        sampled = {
            language: self._registry.recognize(sample_images, language)
            for language in candidate_languages
        }
        ranked = sorted(
            candidate_languages,
            key=lambda language: _candidate_set_score(sampled[language], language),
            reverse=True,
        )
        primary_language, secondary_language = ranked[:2]
        margin = _candidate_set_score(
            sampled[primary_language], primary_language
        ) - _candidate_set_score(sampled[secondary_language], secondary_language)
        primary = self._registry.recognize(images, primary_language)
        selected = list(primary)
        warnings: list[OcrWarning] = []
        if margin < AUTO_ROUTE_MARGIN:
            secondary = self._registry.recognize(images, secondary_language)
            selected = [
                _choose_candidate(first, second)
                for first, second in zip(primary, secondary, strict=True)
            ]
            warnings.append(
                OcrWarning(
                    code="LANGUAGE_ROUTING_AMBIGUOUS",
                    message="The two strongest recognition models were compared per text line.",
                )
            )
        detected, detection_warnings = _detect_document_language(tuple(selected), "auto")
        warnings.extend(detection_warnings)
        return RoutingResult(
            candidates=tuple(selected),
            requested_language="auto",
            detected_language=detected,
            primary_model=RECOGNITION_MODELS[model_key_for_language(primary_language)],
            warnings=tuple(warnings),
        )


def model_key_for_language(language: str) -> str:
    try:
        return LANGUAGE_MODEL_KEYS[language]
    except KeyError as exception:
        raise ValueError(f"Unsupported explicit OCR language: {language}") from exception


def _fallback_language(
    requested_language: str,
    candidates: Sequence[RecognitionCandidate],
) -> str:
    combined = "".join(candidate.text for candidate in candidates)
    if requested_language in {"ja", "zh"}:
        return "ko" if _contains_hangul(combined) else "en"
    if requested_language == "en":
        return "ko" if _contains_hangul(combined) or not combined else "ja"
    return "ja"


def _prefer_alternative(
    primary: RecognitionCandidate,
    alternative: RecognitionCandidate,
) -> bool:
    if _contains_hangul(primary.text) and primary.confidence >= LOW_CONFIDENCE_THRESHOLD:
        return False
    if not primary.text and alternative.text:
        return True
    return _candidate_score(alternative) >= _candidate_score(primary) + FALLBACK_IMPROVEMENT


def _choose_candidate(
    primary: RecognitionCandidate,
    secondary: RecognitionCandidate,
) -> RecognitionCandidate:
    return secondary if _prefer_alternative(primary, secondary) else primary


def _sample_indices(count: int) -> tuple[int, ...]:
    if count <= MAX_AUTO_SAMPLES:
        return tuple(range(count))
    return tuple(
        round(index * (count - 1) / (MAX_AUTO_SAMPLES - 1))
        for index in range(MAX_AUTO_SAMPLES)
    )


def _candidate_set_score(
    candidates: Sequence[RecognitionCandidate],
    language: str,
) -> float:
    if not candidates:
        return 0.0
    return sum(_candidate_score(candidate, language) for candidate in candidates) / len(
        candidates
    )


def _candidate_score(
    candidate: RecognitionCandidate,
    language: str | None = None,
) -> float:
    text = candidate.text
    if not text:
        return 0.0
    expected_language = language or candidate.language
    script_bonus = _script_ratio(text, expected_language) * 0.15
    invalid_penalty = sum(character in {"?", "\ufffd"} for character in text) / len(text)
    return candidate.confidence + script_bonus - invalid_penalty * 0.1


def _script_ratio(text: str, language: str) -> float:
    letters = [character for character in text if character.isalpha()]
    if not letters:
        return 0.0
    if language == "ko":
        matching = sum(_is_hangul(character) for character in letters)
    elif language == "en":
        matching = sum(_is_latin(character) for character in letters)
    else:
        matching = sum(_is_han(character) or _is_kana(character) for character in letters)
    return matching / len(letters)


def _detect_document_language(
    candidates: tuple[RecognitionCandidate, ...],
    requested_language: str,
) -> tuple[str, tuple[OcrWarning, ...]]:
    labels = {_detect_text_language(candidate.text, requested_language) for candidate in candidates}
    labels.discard("und")
    if len(labels) > 1:
        return "mixed", ()
    if labels:
        return labels.pop(), ()
    combined = "".join(candidate.text for candidate in candidates)
    if any(_is_han(character) for character in combined) and requested_language == "auto":
        return (
            "und",
            (
                OcrWarning(
                    code="CJK_LANGUAGE_UNCERTAIN",
                    message="Han-only text could not be classified as Japanese or Chinese.",
                ),
            ),
        )
    return "und", ()


def _detect_text_language(text: str, requested_language: str) -> str:
    if _contains_hangul(text):
        return "ko"
    if any(_is_kana(character) for character in text):
        return "ja"
    if any(_is_han(character) for character in text):
        return requested_language if requested_language in {"ja", "zh"} else "und"
    if any(_is_latin(character) for character in text):
        return "en"
    return "und"


def _contains_hangul(text: str) -> bool:
    return any(_is_hangul(character) for character in text)


def _is_hangul(character: str) -> bool:
    return "\uac00" <= character <= "\ud7a3" or "\u1100" <= character <= "\u11ff"


def _is_latin(character: str) -> bool:
    return "A" <= character <= "Z" or "a" <= character <= "z"


def _is_han(character: str) -> bool:
    return "\u3400" <= character <= "\u9fff"


def _is_kana(character: str) -> bool:
    return "\u3040" <= character <= "\u30ff"


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


def _first_score(result: Any) -> float:
    values = _get_value(result, "scores")
    try:
        scores = np.asarray(values).reshape(-1)
    except (TypeError, ValueError) as exception:
        raise OcrInferenceError() from exception
    if scores.size == 0:
        raise OcrInferenceError()
    return _clamp_confidence(scores[0])


def _dominant_page_rotation(
    classifications: Sequence[tuple[int, float]],
) -> int:
    if len(classifications) < 2:
        return 0
    total_weight = sum(score for _, score in classifications)
    if total_weight <= 0:
        return 0
    rotated_scores = tuple(score for class_id, score in classifications if class_id == 1)
    if not rotated_scores:
        return 0
    rotated_weight = sum(rotated_scores)
    rotated_ratio = rotated_weight / total_weight
    mean_rotated_confidence = sum(rotated_scores) / len(rotated_scores)
    return 180 if rotated_ratio >= 0.70 and mean_rotated_confidence >= 0.70 else 0
