from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from statistics import fmean, median
from typing import Any, Protocol

import numpy as np

from readlogic_ocr.detection import PaddleTextDetector, TextDetector, perspective_crop
from readlogic_ocr.errors import OcrInferenceError
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
    ) -> None:
        self._preprocessor = preprocessor or PaddleDocumentPreprocessor()
        self._detector = detector or PaddleTextDetector()
        self._orienter = orienter or PaddleTextLineOrienter()
        self._registry = registry or RecognitionRegistry()
        self._router = LanguageRouter(self._registry)

    def recognize(self, image: np.ndarray, language: str = "ko") -> EngineResult:
        try:
            if hasattr(self, "_pipeline"):
                return self._recognize_legacy(image)
            preprocessed = (
                self._preprocessor.correct(image)
                if hasattr(self, "_preprocessor")
                else identity_preprocessing(image)
            )
            detected_regions = self._detector.detect(preprocessed.image)
            crops = tuple(
                perspective_crop(preprocessed.image, region.polygon)
                for region in detected_regions
            )
            oriented_crops = self._orienter.orient(crops)
            routing = self._router.recognize(oriented_crops, language)
            if len(routing.candidates) != len(detected_regions):
                raise OcrInferenceError()
            regions = [
                RecognizedRegion(
                    text=candidate.text,
                    recognition_confidence=candidate.confidence,
                    detection_confidence=detected.confidence,
                    polygon=detected.polygon,
                    language=candidate.language,
                    model=candidate.model,
                )
                for detected, candidate in zip(
                    detected_regions,
                    routing.candidates,
                    strict=True,
                )
                if candidate.text
            ]
            lines = reconstruct_reading_order(tuple(regions))
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
            return EngineResult(
                confidence=_average_confidence(regions),
                text="\n\n".join(paragraph.text for paragraph in paragraphs),
                regions=tuple(regions),
                correction=preprocessed.correction,
                warnings=preprocessed.warnings + routing.warnings + grouping_warnings,
                image_size=(
                    int(preprocessed.image.shape[1]),
                    int(preprocessed.image.shape[0]),
                ),
                requested_language=routing.requested_language,
                detected_language=routing.detected_language,
                primary_model=routing.primary_model,
                lines=lines,
                paragraphs=paragraphs,
            )
        except OcrInferenceError:
            raise
        except Exception as exception:
            raise OcrInferenceError() from exception

    def _recognize_legacy(self, image: np.ndarray) -> EngineResult:
        results = self._pipeline.predict(image)
        regions = list(_read_regions(results))
        return EngineResult(
            confidence=_average_confidence(regions),
            text=_assemble_text(regions),
            regions=tuple(regions),
            image_size=(int(image.shape[1]), int(image.shape[0])),
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
