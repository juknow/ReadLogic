import pytest

from readlogic_ocr.models import RecognizedRegion, average_optional, union_bbox


def test_region_exposes_geometry_without_discarding_polygon() -> None:
    region = RecognizedRegion(
        text="문장",
        recognition_confidence=0.91,
        detection_confidence=0.82,
        polygon=((10, 5), (80, 8), (78, 30), (8, 27)),
    )

    assert region.bbox == (8, 5, 80, 30)
    assert region.width == 72
    assert region.height == 25
    assert region.x == 8
    assert region.y == 17.5


def test_unions_bounding_boxes_and_averages_present_values() -> None:
    assert union_bbox(((10, 20, 30, 40), (5, 25, 50, 60))) == (5, 20, 50, 60)
    assert average_optional((None, 0.8, 1.0)) == 0.9
    assert average_optional((None, None)) is None


def test_union_requires_a_box() -> None:
    with pytest.raises(ValueError):
        union_bbox(())
