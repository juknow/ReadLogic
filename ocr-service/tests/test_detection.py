import numpy as np

from readlogic_ocr.detection import perspective_crop


def test_perspective_crop_normalizes_a_quadrilateral() -> None:
    image = np.zeros((100, 200, 3), dtype=np.uint8)
    image[20:60, 30:150] = 255

    crop = perspective_crop(
        image,
        ((30, 20), (150, 25), (145, 60), (25, 55)),
    )

    assert crop.ndim == 3
    assert crop.shape[0] >= 35
    assert crop.shape[1] >= 115
