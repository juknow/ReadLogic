from __future__ import annotations

from dataclasses import dataclass, field
from statistics import fmean

Point = tuple[float, float]
BoundingBox = tuple[float, float, float, float]


@dataclass(frozen=True)
class OcrWarning:
    code: str
    message: str


@dataclass(frozen=True)
class CorrectionMetadata:
    exif_applied: bool = True
    orientation_applied: bool = False
    rotation_degrees: int = 0
    unwarping_applied: bool = False
    fallback_used: bool = False


@dataclass(frozen=True)
class RecognizedRegion:
    text: str
    recognition_confidence: float
    polygon: tuple[Point, ...]
    detection_confidence: float | None = None
    language: str = "ko"
    model: str = "korean_PP-OCRv5_mobile_rec"

    @property
    def bbox(self) -> BoundingBox:
        xs = [point[0] for point in self.polygon]
        ys = [point[1] for point in self.polygon]
        return min(xs), min(ys), max(xs), max(ys)

    @property
    def confidence(self) -> float:
        return self.recognition_confidence

    @property
    def height(self) -> float:
        _, top, _, bottom = self.bbox
        return max(1.0, bottom - top)

    @property
    def width(self) -> float:
        left, _, right, _ = self.bbox
        return max(1.0, right - left)

    @property
    def x(self) -> float:
        return self.bbox[0]

    @property
    def y(self) -> float:
        _, top, _, bottom = self.bbox
        return (top + bottom) / 2


@dataclass(frozen=True)
class OcrLine:
    id: int
    order: int
    text: str
    bbox: BoundingBox
    polygon: tuple[Point, ...]
    detection_confidence: float | None
    confidence: float
    language: str
    model: str
    regions: tuple[RecognizedRegion, ...] = field(repr=False)


@dataclass(frozen=True)
class OcrParagraph:
    id: int
    order: int
    type: str
    text: str
    bbox: BoundingBox
    confidence: float
    lines: tuple[OcrLine, ...]


def union_bbox(boxes: tuple[BoundingBox, ...]) -> BoundingBox:
    if not boxes:
        raise ValueError("At least one bounding box is required.")
    return (
        min(box[0] for box in boxes),
        min(box[1] for box in boxes),
        max(box[2] for box in boxes),
        max(box[3] for box in boxes),
    )


def average_optional(values: tuple[float | None, ...]) -> float | None:
    present = [value for value in values if value is not None]
    return round(fmean(present), 4) if present else None
