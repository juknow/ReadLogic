from collections.abc import Iterable, Sequence
from dataclasses import dataclass, replace
from statistics import fmean, median
from time import perf_counter
from typing import Any, Protocol

import numpy as np

from readlogic_ocr.detection import PaddleTextDetector, TextDetector, perspective_crop
from readlogic_ocr.errors import OcrInferenceError, OcrTimeoutError
from readlogic_ocr.models import (
    CorrectionMetadata,
    OcrLine,
    OcrParagraph,
    OcrWarning,
    RecognizedRegion,
)
from readlogic_ocr.ordering import reconstruct_reading_order
from readlogic_ocr.paragraphs import fallback_paragraphs, group_paragraphs
from readlogic_ocr.preprocessing import (
    DocumentPreprocessor,
    PaddleDocumentPreprocessor,
    identity_preprocessing,
)
from readlogic_ocr.recognition import (
    LanguageRouter,
    PaddleTextLineOrienter,
    RecognitionRegistry,
    RoutingResult,
    TextLineOrienter,
)


@dataclass(frozen=True)
class EngineResult:
    confidence: float | None
    text: str
    regions: tuple[RecognizedRegion, ...] = ()
    correction: CorrectionMetadata = CorrectionMetadata()
    warnings: tuple[OcrWarning, ...] = ()
    image_size: tuple[int, int] = (0, 0)
    requested_language: str = "ko"
    detected_language: str = "ko"
    primary_model: str = "korean_PP-OCRv5_mobile_rec"
    lines: tuple[OcrLine, ...] = ()
    paragraphs: tuple[OcrParagraph, ...] = ()


class OcrEngine(Protocol):
    def recognize(self, image: np.ndarray, language: str = "ko") -> EngineResult: ...


class PaddleOcrEngine:
    _pipeline: Any

    def __init__(
        self,
        preprocessor: DocumentPreprocessor | None = None,
        detector: TextDetector | None = None,
        orienter: TextLineOrienter | None = None,
        registry: RecognitionRegistry | None = None,
        preload_languages: Sequence[str] = ("ko",),
        stage_timeout_seconds: float = 105.0,
    ) -> None:
        self._preprocessor = preprocessor or PaddleDocumentPreprocessor()
        self._detector = detector or PaddleTextDetector()
        self._orienter = orienter or PaddleTextLineOrienter()
        self._registry = registry or RecognitionRegistry(preload_languages=preload_languages)
        self._router = LanguageRouter(self._registry)
        self._stage_timeout_seconds = stage_timeout_seconds

    def recognize(self, image: np.ndarray, language: str = "ko") -> EngineResult:
        deadline = perf_counter() + getattr(self, "_stage_timeout_seconds", 105.0)
        try:
            if hasattr(self, "_pipeline"):
                return self._recognize_legacy(image)
            preprocessed = (
                self._preprocessor.correct(image)
                if hasattr(self, "_preprocessor")
                else identity_preprocessing(image)
            )
            self._ensure_before_deadline(deadline)
            pipeline_warnings: tuple[OcrWarning, ...] = ()
            try:
                regions, routing, page_rotation = self._recognize_regions(
                    preprocessed.image,
                    language,
                    deadline,
                )
                selected_image = _rotate_page(preprocessed.image, page_rotation)
                correction = _apply_page_rotation(
                    preprocessed.correction,
                    page_rotation,
                )
            except OcrInferenceError:
                if not preprocessed.correction.unwarping_applied:
                    raise
                regions, routing, page_rotation = self._recognize_regions(
                    preprocessed.fallback_image,
                    language,
                    deadline,
                )
                selected_image = _rotate_page(
                    preprocessed.fallback_image,
                    page_rotation,
                )
                correction = _apply_page_rotation(
                    _fallback_correction(preprocessed.correction),
                    page_rotation,
                )
                pipeline_warnings = (_unwarping_fallback_warning(),)

            if preprocessed.correction.unwarping_applied and (
                not regions or (_average_confidence(regions) or 0.0) < 0.30
            ):
                try:
                    fallback_regions, fallback_routing, fallback_page_rotation = (
                        self._recognize_regions(
                            preprocessed.fallback_image,
                            language,
                            deadline,
                        )
                    )
                    if _recognition_quality(fallback_regions) > _recognition_quality(regions):
                        regions = fallback_regions
                        routing = fallback_routing
                        selected_image = _rotate_page(
                            preprocessed.fallback_image,
                            fallback_page_rotation,
                        )
                        correction = _apply_page_rotation(
                            _fallback_correction(preprocessed.correction),
                            fallback_page_rotation,
                        )
                        pipeline_warnings = (_unwarping_fallback_warning(),)
                except OcrInferenceError:
                    pipeline_warnings = (
                        OcrWarning(
                            code="DOCUMENT_UNWARPING_FALLBACK_FAILED",
                            message=(
                                "The unwarped result was kept because the fallback image "
                                "could not be recognized."
                            ),
                        ),
                    )

            lines = reconstruct_reading_order(regions)
            grouping_warnings: tuple[OcrWarning, ...] = ()
            try:
                paragraphs = group_paragraphs(lines)
            except Exception:
                paragraphs = fallback_paragraphs(lines)
                grouping_warnings = (
                    OcrWarning(
                        code="PARAGRAPH_GROUPING_FALLBACK",
                        message="Each recognized line was returned as a separate paragraph.",
                    ),
                )
            self._ensure_before_deadline(deadline)
            return EngineResult(
                confidence=_average_confidence(regions),
                text="\n\n".join(paragraph.text for paragraph in paragraphs),
                regions=regions,
                correction=correction,
                warnings=(
                    preprocessed.warnings
                    + pipeline_warnings
                    + routing.warnings
                    + grouping_warnings
                ),
                image_size=(
                    int(selected_image.shape[1]),
                    int(selected_image.shape[0]),
                ),
                requested_language=routing.requested_language,
                detected_language=routing.detected_language,
                primary_model=routing.primary_model,
                lines=lines,
                paragraphs=paragraphs,
            )
        except (OcrInferenceError, OcrTimeoutError):
            raise
        except Exception as exception:
            raise OcrInferenceError() from exception

    def _recognize_regions(
        self,
        image: np.ndarray,
        language: str,
        deadline: float,
    ) -> tuple[tuple[RecognizedRegion, ...], RoutingResult, int]:
        detected_regions = self._detector.detect(image)
        self._ensure_before_deadline(deadline)
        crops = tuple(perspective_crop(image, region.polygon) for region in detected_regions)
        orientation = self._orienter.orient(crops)
        self._ensure_before_deadline(deadline)
        routing = self._router.recognize(orientation.images, language)
        self._ensure_before_deadline(deadline)
        if len(routing.candidates) != len(detected_regions):
            raise OcrInferenceError()
        return (
            tuple(
                RecognizedRegion(
                    text=candidate.text,
                    recognition_confidence=candidate.confidence,
                    detection_confidence=detected.confidence,
                    polygon=_rotate_polygon(
                        detected.polygon,
                        image,
                        orientation.page_rotation_degrees,
                    ),
                    language=candidate.language,
                    model=candidate.model,
                )
                for detected, candidate in zip(
                    detected_regions,
                    routing.candidates,
                    strict=True,
                )
                if candidate.text
            ),
            routing,
            orientation.page_rotation_degrees,
        )

    @staticmethod
    def _ensure_before_deadline(deadline: float) -> None:
        if perf_counter() > deadline:
            raise OcrTimeoutError()

    def _recognize_legacy(self, image: np.ndarray) -> EngineResult:
        results = self._pipeline.predict(image)
        regions = list(_read_regions(results))
        return EngineResult(
            confidence=_average_confidence(regions),
            text=_assemble_text(regions),
            regions=tuple(regions),
            image_size=(int(image.shape[1]), int(image.shape[0])),
        )


def _recognition_quality(regions: Sequence[RecognizedRegion]) -> float:
    confidence = _average_confidence(regions) or 0.0
    character_count = min(200, sum(len(region.text) for region in regions))
    return confidence + character_count * 0.001


def _fallback_correction(correction: CorrectionMetadata) -> CorrectionMetadata:
    return replace(
        correction,
        unwarping_applied=False,
        fallback_used=True,
    )


def _apply_page_rotation(
    correction: CorrectionMetadata,
    rotation_degrees: int,
) -> CorrectionMetadata:
    if rotation_degrees == 0:
        return correction
    return replace(
        correction,
        orientation_applied=True,
        rotation_degrees=(correction.rotation_degrees + rotation_degrees) % 360,
    )


def _rotate_page(image: np.ndarray, rotation_degrees: int) -> np.ndarray:
    if rotation_degrees == 180:
        return np.rot90(image, 2).copy()
    return image


def _rotate_polygon(
    polygon: tuple[tuple[float, float], ...],
    image: np.ndarray,
    rotation_degrees: int,
) -> tuple[tuple[float, float], ...]:
    if rotation_degrees != 180:
        return polygon
    maximum_x = float(image.shape[1] - 1)
    maximum_y = float(image.shape[0] - 1)
    return tuple((maximum_x - x, maximum_y - y) for x, y in polygon)


def _unwarping_fallback_warning() -> OcrWarning:
    return OcrWarning(
        code="DOCUMENT_UNWARPING_FALLBACK",
        message="The original corrected-orientation image produced a more stable OCR result.",
    )


def _read_regions(
    results: Iterable[Any],
    language: str = "ko",
    model: str = "korean_PP-OCRv5_mobile_rec",
) -> Iterable[RecognizedRegion]:
    for result in results:
        texts = list(_get_result_value(result, "rec_texts"))
        scores = list(_get_result_value(result, "rec_scores"))
        polygons = list(_get_result_value(result, "rec_polys"))
        detection_scores = _get_optional_result_value(result, "dt_scores")
        if not (len(texts) == len(scores) == len(polygons)):
            raise OcrInferenceError()
        if detection_scores is not None and len(detection_scores) != len(texts):
            raise OcrInferenceError()
        normalized_detection_scores = detection_scores or [None] * len(texts)
        for text, score, polygon, detection_score in zip(
            texts,
            scores,
            polygons,
            normalized_detection_scores,
            strict=True,
        ):
            normalized_text = str(text).strip()
            if not normalized_text:
                continue
            points = np.asarray(polygon, dtype=float)
            if points.ndim != 2 or points.shape[0] < 2 or points.shape[1] != 2:
                raise OcrInferenceError()
            yield RecognizedRegion(
                text=normalized_text,
                recognition_confidence=_clamp_confidence(score),
                detection_confidence=(
                    None if detection_score is None else _clamp_confidence(detection_score)
                ),
                polygon=tuple((float(point[0]), float(point[1])) for point in points),
                language=language,
                model=model,
            )


def _get_result_value(result: Any, key: str) -> list[Any]:
    try:
        value = result[key]
    except (KeyError, TypeError) as exception:
        raise OcrInferenceError() from exception
    if isinstance(value, (Sequence, np.ndarray)):
        return list(value)
    raise OcrInferenceError()


def _get_optional_result_value(result: Any, key: str) -> list[Any] | None:
    try:
        value = result[key]
    except (KeyError, TypeError):
        return None
    if value is None:
        return None
    if isinstance(value, (Sequence, np.ndarray)):
        return list(value)
    raise OcrInferenceError()


def _clamp_confidence(value: Any) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError) as exception:
        raise OcrInferenceError() from exception


def _average_confidence(regions: Sequence[RecognizedRegion]) -> float | None:
    if not regions:
        return None
    return round(fmean(region.confidence for region in regions), 4)


def _assemble_text(regions: Sequence[RecognizedRegion]) -> str:
    if not regions:
        return ""
    line_tolerance = max(8.0, median(region.height for region in regions) * 0.6)
    lines: list[list[RecognizedRegion]] = []
    for region in sorted(regions, key=lambda item: (item.y, item.x)):
        target_line = next(
            (
                line
                for line in reversed(lines)
                if abs(fmean(item.y for item in line) - region.y) <= line_tolerance
            ),
            None,
        )
        if target_line is None:
            lines.append([region])
        else:
            target_line.append(region)
    ordered_lines = sorted(lines, key=lambda line: fmean(item.y for item in line))
    return "\n".join(
        " ".join(region.text for region in sorted(line, key=lambda item: item.x))
        for line in ordered_lines
    )
