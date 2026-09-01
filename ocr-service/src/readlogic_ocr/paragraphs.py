from __future__ import annotations

import re
from statistics import median

from readlogic_ocr.models import OcrLine, OcrParagraph, union_bbox

_NO_SPACE_BEFORE = re.compile(r"\s+([,.;:!?%)\]}。．、，！？：；）】』」])")
_NO_SPACE_AFTER = re.compile(r"([([{（【『「])\s+")


def group_paragraphs(lines: tuple[OcrLine, ...]) -> tuple[OcrParagraph, ...]:
    if not lines:
        return ()
    heights = [line.bbox[3] - line.bbox[1] for line in lines]
    median_height = median(heights)
    positive_gaps = [
        current.bbox[1] - previous.bbox[3]
        for previous, current in zip(lines, lines[1:], strict=False)
        if current.bbox[1] >= previous.bbox[3] and _horizontal_overlap(previous, current) > 0
    ]
    typical_gap = median(positive_gaps) if positive_gaps else median_height * 0.4
    groups: list[list[OcrLine]] = [[lines[0]]]
    for previous, current in zip(lines, lines[1:], strict=False):
        if _starts_new_paragraph(previous, current, median_height, typical_gap):
            groups.append([current])
        else:
            groups[-1].append(current)
    return tuple(
        _create_paragraph(index, tuple(group), median_height)
        for index, group in enumerate(groups, start=1)
    )


def fallback_paragraphs(lines: tuple[OcrLine, ...]) -> tuple[OcrParagraph, ...]:
    return tuple(
        OcrParagraph(
            id=index,
            order=index,
            type="unknown",
            text=line.text,
            bbox=line.bbox,
            confidence=line.confidence,
            lines=(line,),
        )
        for index, line in enumerate(lines, start=1)
    )


def merge_paragraph_lines(lines: tuple[OcrLine, ...]) -> str:
    if not lines:
        return ""
    text = lines[0].text.strip()
    for previous, current in zip(lines, lines[1:], strict=False):
        next_text = current.text.strip()
        if not next_text:
            continue
        if previous.language == "en" and text.endswith("-") and next_text[0].islower():
            text = text[:-1] + next_text
        elif _is_cjk_without_spacing(previous.language, current.language):
            text += next_text
        else:
            text += " " + next_text
    return _NO_SPACE_AFTER.sub(r"\1", _NO_SPACE_BEFORE.sub(r"\1", text)).strip()


def _starts_new_paragraph(
    previous: OcrLine,
    current: OcrLine,
    median_height: float,
    typical_gap: float,
) -> bool:
    if _horizontal_overlap(previous, current) == 0 and current.bbox[1] < previous.bbox[3]:
        return True
    vertical_gap = current.bbox[1] - previous.bbox[3]
    if vertical_gap > max(typical_gap * 1.5, median_height * 1.2):
        return True
    previous_height = previous.bbox[3] - previous.bbox[1]
    current_height = current.bbox[3] - current.bbox[1]
    if previous_height > median_height * 1.25 or current_height > median_height * 1.25:
        return True
    indentation = current.bbox[0] - previous.bbox[0]
    if indentation > median_height:
        return True
    previous_width = previous.bbox[2] - previous.bbox[0]
    current_width = current.bbox[2] - current.bbox[0]
    aligned_starts = abs(current.bbox[0] - previous.bbox[0]) <= median_height * 0.5
    return aligned_starts and previous_width < current_width * 0.75


def _create_paragraph(
    identifier: int,
    lines: tuple[OcrLine, ...],
    median_height: float,
) -> OcrParagraph:
    total_characters = sum(max(1, len(line.text)) for line in lines)
    confidence = sum(
        line.confidence * max(1, len(line.text)) for line in lines
    ) / total_characters
    only_height = lines[0].bbox[3] - lines[0].bbox[1]
    paragraph_type = "title" if len(lines) == 1 and only_height > median_height * 1.25 else "body"
    return OcrParagraph(
        id=identifier,
        order=identifier,
        type=paragraph_type,
        text=merge_paragraph_lines(lines),
        bbox=union_bbox(tuple(line.bbox for line in lines)),
        confidence=round(confidence, 4),
        lines=lines,
    )


def _horizontal_overlap(first: OcrLine, second: OcrLine) -> float:
    return max(0.0, min(first.bbox[2], second.bbox[2]) - max(first.bbox[0], second.bbox[0]))


def _is_cjk_without_spacing(previous_language: str, current_language: str) -> bool:
    return previous_language in {"ja", "zh"} and current_language in {"ja", "zh"}
