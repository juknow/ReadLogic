from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from statistics import fmean, median
from typing import Any, Protocol

import numpy as np

from readlogic_ocr.errors import OcrInferenceError


@dataclass(frozen=True)
class EngineResult:
    confidence: float | None
    text: str


class OcrEngine(Protocol):
    def recognize(self, image: np.ndarray) -> EngineResult: ...


@dataclass(frozen=True)
class RecognizedRegion:
    confidence: float
    height: float
    text: str
    x: float
    y: float


class PaddleOcrEngine:
    def __init__(self) -> None:
        from paddleocr import PaddleOCR

        self._pipeline = PaddleOCR(
            lang="korean",
            ocr_version="PP-OCRv5",
            use_doc_orientation_classify=True,
            use_doc_unwarping=True,
            use_textline_orientation=True,
        )

    def recognize(self, image: np.ndarray) -> EngineResult:
        try:
            results = self._pipeline.predict(image)
            regions = list(_read_regions(results))
            return EngineResult(
                confidence=_average_confidence(regions),
                text=_assemble_text(regions),
            )
        except OcrInferenceError:
            raise
        except Exception as exception:
            raise OcrInferenceError() from exception


def _read_regions(results: Iterable[Any]) -> Iterable[RecognizedRegion]:
    for result in results:
        texts = list(_get_result_value(result, "rec_texts"))
        scores = list(_get_result_value(result, "rec_scores"))
        polygons = list(_get_result_value(result, "rec_polys"))
        if not (len(texts) == len(scores) == len(polygons)):
            raise OcrInferenceError()
        for text, score, polygon in zip(texts, scores, polygons, strict=True):
            normalized_text = str(text).strip()
            if not normalized_text:
                continue
            points = np.asarray(polygon, dtype=float)
            if points.ndim != 2 or points.shape[0] < 2 or points.shape[1] != 2:
                raise OcrInferenceError()
            xs = points[:, 0]
            ys = points[:, 1]
            yield RecognizedRegion(
                confidence=max(0.0, min(1.0, float(score))),
                height=max(1.0, float(ys.max() - ys.min())),
                text=normalized_text,
                x=float(xs.min()),
                y=float((ys.min() + ys.max()) / 2),
            )


def _get_result_value(result: Any, key: str) -> list[Any]:
    try:
        value = result[key]
    except (KeyError, TypeError) as exception:
        raise OcrInferenceError() from exception
    if isinstance(value, (Sequence, np.ndarray)):
        return list(value)
    raise OcrInferenceError()


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
