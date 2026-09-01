from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

import cv2
import numpy as np

from readlogic_ocr.errors import OcrInferenceError
from readlogic_ocr.models import Point

DETECTION_MODEL = "PP-OCRv5_server_det"


@dataclass(frozen=True)
class DetectedRegion:
    polygon: tuple[Point, ...]
    confidence: float | None


class TextDetector(Protocol):
    def detect(self, image: np.ndarray) -> tuple[DetectedRegion, ...]: ...


class PaddleTextDetector:
    def __init__(self) -> None:
        from paddleocr import TextDetection

        self._model = TextDetection(model_name=DETECTION_MODEL)

    def detect(self, image: np.ndarray) -> tuple[DetectedRegion, ...]:
        try:
            results = list(self._model.predict(image))
            if len(results) != 1:
                raise OcrInferenceError()
            polygons = list(_get_value(results[0], "dt_polys"))
            scores = list(_get_value(results[0], "dt_scores"))
            if len(polygons) != len(scores):
                raise OcrInferenceError()
            return tuple(
                DetectedRegion(
                    polygon=_normalize_polygon(polygon),
                    confidence=_clamp_confidence(score),
                )
                for polygon, score in zip(polygons, scores, strict=True)
            )
        except OcrInferenceError:
            raise
        except Exception as exception:
            raise OcrInferenceError() from exception


def perspective_crop(image: np.ndarray, polygon: tuple[Point, ...]) -> np.ndarray:
    if len(polygon) < 4:
        raise OcrInferenceError()
    points = np.asarray(polygon, dtype=np.float32)
    rectangle = _order_quad(points)
    top_left, top_right, bottom_right, bottom_left = rectangle
    width = max(
        int(round(np.linalg.norm(top_right - top_left))),
        int(round(np.linalg.norm(bottom_right - bottom_left))),
        1,
    )
    height = max(
        int(round(np.linalg.norm(bottom_left - top_left))),
        int(round(np.linalg.norm(bottom_right - top_right))),
        1,
    )
    destination = np.asarray(
        [[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]],
        dtype=np.float32,
    )
    transform = cv2.getPerspectiveTransform(rectangle, destination)
    return cv2.warpPerspective(
        image,
        transform,
        (width, height),
        borderMode=cv2.BORDER_REPLICATE,
    )


def _order_quad(points: np.ndarray) -> np.ndarray:
    if points.shape[0] != 4 or points.shape[1] != 2:
        raise OcrInferenceError()
    sums = points.sum(axis=1)
    differences = np.diff(points, axis=1).reshape(-1)
    return np.asarray(
        [
            points[np.argmin(sums)],
            points[np.argmin(differences)],
            points[np.argmax(sums)],
            points[np.argmax(differences)],
        ],
        dtype=np.float32,
    )


def _normalize_polygon(value: Any) -> tuple[Point, ...]:
    points = np.asarray(value, dtype=float)
    if points.ndim != 2 or points.shape[0] != 4 or points.shape[1] != 2:
        raise OcrInferenceError()
    return tuple((float(point[0]), float(point[1])) for point in points)


def _clamp_confidence(value: Any) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError) as exception:
        raise OcrInferenceError() from exception


def _get_value(result: Any, key: str) -> Any:
    try:
        return result[key]
    except (KeyError, TypeError) as exception:
        raise OcrInferenceError() from exception
