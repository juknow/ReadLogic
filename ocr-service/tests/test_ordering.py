from readlogic_ocr.models import RecognizedRegion
from readlogic_ocr.ordering import reconstruct_reading_order


def region(
    text: str,
    left: float,
    top: float,
    right: float,
    bottom: float,
    language: str = "ko",
) -> RecognizedRegion:
    return RecognizedRegion(
        text=text,
        recognition_confidence=0.9,
        detection_confidence=0.8,
        polygon=((left, top), (right, top), (right, bottom), (left, bottom)),
        language=language,
    )


def test_groups_fragments_into_lines_and_preserves_geometry() -> None:
    lines = reconstruct_reading_order(
        (
            region("오른쪽", 80, 0, 140, 20),
            region("첫째", 0, 0, 50, 20),
            region("둘째", 0, 40, 50, 60),
        )
    )

    assert [line.text for line in lines] == ["첫째 오른쪽", "둘째"]
    assert lines[0].bbox == (0, 0, 140, 20)
    assert lines[0].detection_confidence == 0.8


def test_orders_left_column_before_right_column() -> None:
    lines = reconstruct_reading_order(
        (
            region("오른쪽 1", 300, 0, 440, 20),
            region("왼쪽 2", 0, 40, 140, 60),
            region("오른쪽 2", 300, 40, 440, 60),
            region("왼쪽 1", 0, 0, 140, 20),
        )
    )

    assert [line.text for line in lines] == ["왼쪽 1", "왼쪽 2", "오른쪽 1", "오른쪽 2"]
