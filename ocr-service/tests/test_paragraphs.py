from readlogic_ocr.models import OcrLine
from readlogic_ocr.paragraphs import group_paragraphs, merge_paragraph_lines


def line(
    identifier: int,
    text: str,
    bbox: tuple[float, float, float, float],
    language: str = "ko",
) -> OcrLine:
    left, top, right, bottom = bbox
    return OcrLine(
        id=identifier,
        order=identifier,
        text=text,
        bbox=bbox,
        polygon=((left, top), (right, top), (right, bottom), (left, bottom)),
        detection_confidence=0.9,
        confidence=0.9,
        language=language,
        model="model",
        regions=(),
    )


def test_splits_paragraphs_using_vertical_whitespace() -> None:
    paragraphs = group_paragraphs(
        (
            line(1, "첫 문단의 첫 줄", (0, 0, 200, 20)),
            line(2, "첫 문단의 둘째 줄", (0, 25, 200, 45)),
            line(3, "둘째 문단", (0, 90, 140, 110)),
        )
    )

    assert [paragraph.text for paragraph in paragraphs] == [
        "첫 문단의 첫 줄 첫 문단의 둘째 줄",
        "둘째 문단",
    ]


def test_splits_an_indented_paragraph() -> None:
    paragraphs = group_paragraphs(
        (
            line(1, "본문", (0, 0, 200, 20)),
            line(2, "새 문단", (30, 25, 200, 45)),
        )
    )

    assert len(paragraphs) == 2


def test_splits_after_a_short_paragraph_final_line() -> None:
    paragraphs = group_paragraphs(
        (
            line(1, "첫 문단의 첫 줄", (0, 0, 200, 20)),
            line(2, "짧게 끝나는 줄", (0, 25, 110, 45)),
            line(3, "다음 문단의 첫 줄", (0, 50, 200, 70)),
        )
    )

    assert [paragraph.text for paragraph in paragraphs] == [
        "첫 문단의 첫 줄 짧게 끝나는 줄",
        "다음 문단의 첫 줄",
    ]


def test_uses_language_aware_line_merge() -> None:
    assert merge_paragraph_lines(
        (
            line(1, "人工知能は", (0, 0, 100, 20), "ja"),
            line(2, "発展している。", (0, 25, 120, 45), "ja"),
        )
    ) == "人工知能は発展している。"
    assert merge_paragraph_lines(
        (
            line(1, "read-", (0, 0, 100, 20), "en"),
            line(2, "ing improves thinking.", (0, 25, 180, 45), "en"),
        )
    ) == "reading improves thinking."
