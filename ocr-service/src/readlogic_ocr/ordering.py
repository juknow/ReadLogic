from __future__ import annotations

from statistics import fmean, median

from readlogic_ocr.models import (
    OcrLine,
    Point,
    RecognizedRegion,
    average_optional,
    union_bbox,
)


def reconstruct_reading_order(
    regions: tuple[RecognizedRegion, ...],
) -> tuple[OcrLine, ...]:
    if not regions:
        return ()
    grouped = _group_regions_into_lines(regions)
    lines = tuple(_create_line(index + 1, group) for index, group in enumerate(grouped))
    ordered = _order_columns(lines)
    return tuple(
        OcrLine(
            id=index,
            order=index,
            text=line.text,
            bbox=line.bbox,
            polygon=line.polygon,
            detection_confidence=line.detection_confidence,
            confidence=line.confidence,
            language=line.language,
            model=line.model,
            regions=line.regions,
        )
        for index, line in enumerate(ordered, start=1)
    )


def _group_regions_into_lines(
    regions: tuple[RecognizedRegion, ...],
) -> tuple[tuple[RecognizedRegion, ...], ...]:
    line_tolerance = max(8.0, median(region.height for region in regions) * 0.6)
    lines: list[list[RecognizedRegion]] = []
    for region in sorted(regions, key=lambda item: (item.y, item.x)):
        target = next(
            (
                line
                for line in reversed(lines)
                if _same_line(line, region, line_tolerance)
            ),
            None,
        )
        if target is None:
            lines.append([region])
        else:
            target.append(region)
    return tuple(
        tuple(sorted(line, key=lambda region: region.x))
        for line in sorted(lines, key=lambda value: fmean(region.y for region in value))
    )


def _same_line(
    line: list[RecognizedRegion],
    region: RecognizedRegion,
    tolerance: float,
) -> bool:
    center_matches = abs(fmean(item.y for item in line) - region.y) <= tolerance
    line_box = union_bbox(tuple(item.bbox for item in line))
    overlap = max(0.0, min(line_box[3], region.bbox[3]) - max(line_box[1], region.bbox[1]))
    overlap_ratio = overlap / min(line_box[3] - line_box[1], region.height)
    horizontal_gap = max(
        0.0,
        region.bbox[0] - line_box[2],
        line_box[0] - region.bbox[2],
    )
    maximum_fragment_gap = max(line_box[3] - line_box[1], region.height) * 5
    return (
        center_matches
        and overlap_ratio >= 0.5
        and horizontal_gap <= maximum_fragment_gap
    )


def _create_line(identifier: int, regions: tuple[RecognizedRegion, ...]) -> OcrLine:
    bbox = union_bbox(tuple(region.bbox for region in regions))
    languages = {region.language for region in regions}
    language = languages.pop() if len(languages) == 1 else "mixed"
    model = max(regions, key=lambda region: len(region.text)).model
    total_characters = sum(max(1, len(region.text)) for region in regions)
    confidence = sum(
        region.confidence * max(1, len(region.text)) for region in regions
    ) / total_characters
    return OcrLine(
        id=identifier,
        order=identifier,
        text=" ".join(region.text for region in regions),
        bbox=bbox,
        polygon=_bbox_polygon(bbox),
        detection_confidence=average_optional(
            tuple(region.detection_confidence for region in regions)
        ),
        confidence=round(confidence, 4),
        language=language,
        model=model,
        regions=regions,
    )


def _order_columns(lines: tuple[OcrLine, ...]) -> tuple[OcrLine, ...]:
    columns = _split_columns(lines)
    if len(columns) == 1:
        return tuple(sorted(lines, key=lambda line: (line.bbox[1], line.bbox[0])))
    all_left = min(line.bbox[0] for line in lines)
    all_right = max(line.bbox[2] for line in lines)
    page_width = max(1.0, all_right - all_left)
    spanning = tuple(line for line in lines if _line_width(line) >= page_width * 0.75)
    column_lines = {line.id for column in columns for line in column}
    spanning = tuple(line for line in spanning if line.id not in column_lines)
    top = min((line.bbox[1] for column in columns for line in column), default=0.0)
    top_spanning = tuple(
        sorted(
            (line for line in spanning if line.bbox[1] <= top),
            key=lambda line: (line.bbox[1], line.bbox[0]),
        )
    )
    bottom_spanning = tuple(
        sorted(
            (line for line in spanning if line not in top_spanning),
            key=lambda line: (line.bbox[1], line.bbox[0]),
        )
    )
    ordered_columns = tuple(
        line
        for column in sorted(columns, key=lambda value: min(line.bbox[0] for line in value))
        for line in sorted(column, key=lambda value: (value.bbox[1], value.bbox[0]))
    )
    return top_spanning + ordered_columns + bottom_spanning


def _split_columns(lines: tuple[OcrLine, ...]) -> tuple[tuple[OcrLine, ...], ...]:
    if len(lines) < 4:
        return (lines,)
    median_height = median(line.bbox[3] - line.bbox[1] for line in lines)
    candidate_boundaries = sorted(
        {
            line.bbox[0]
            for line in lines
        }
        | {line.bbox[2] for line in lines}
    )
    best: tuple[float, tuple[OcrLine, ...], tuple[OcrLine, ...]] | None = None
    for left_boundary, right_boundary in zip(
        candidate_boundaries,
        candidate_boundaries[1:],
        strict=False,
    ):
        gap = right_boundary - left_boundary
        if gap < median_height * 1.5:
            continue
        left = tuple(line for line in lines if line.bbox[2] <= left_boundary)
        right = tuple(line for line in lines if line.bbox[0] >= right_boundary)
        if len(left) < 2 or len(right) < 2:
            continue
        if best is None or gap > best[0]:
            best = (gap, left, right)
    if best is None:
        return (lines,)
    _, left, right = best
    left_columns = _split_columns(left)
    right_columns = _split_columns(right)
    return left_columns + right_columns


def _line_width(line: OcrLine) -> float:
    return line.bbox[2] - line.bbox[0]


def _bbox_polygon(bbox: tuple[float, float, float, float]) -> tuple[Point, ...]:
    left, top, right, bottom = bbox
    return ((left, top), (right, top), (right, bottom), (left, bottom))
