import os
from pathlib import Path

import numpy as np
import pytest
from PIL import Image, ImageDraw, ImageFont

from readlogic_ocr.engine import PaddleOcrEngine

RUN_MODEL_TESTS = os.getenv("RUN_OCR_MODEL_TESTS") == "1"


def find_font() -> Path | None:
    configured = os.getenv("OCR_TEST_FONT_PATH")
    candidates = [
        configured,
        "C:/Windows/Fonts/malgun.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    ]
    return next(
        (Path(path) for path in candidates if path and Path(path).exists()),
        None,
    )


@pytest.mark.model
@pytest.mark.skipif(not RUN_MODEL_TESTS, reason="real OCR model tests are disabled")
def test_recognizes_korean_printed_page_and_rotation() -> None:
    font_path = find_font()
    if font_path is None:
        pytest.fail("A Korean test font is required for the model smoke test.")
    font = ImageFont.truetype(str(font_path), 72)
    page = Image.new("RGB", (1500, 500), "white")
    ImageDraw.Draw(page).text(
        (80, 100),
        "독서는 생각을 확장한다",
        fill="black",
        font=font,
    )
    engine = PaddleOcrEngine()

    normal_result = engine.recognize(np.asarray(page)[:, :, ::-1].copy())
    rotated_result = engine.recognize(
        np.asarray(page.rotate(90, expand=True))[:, :, ::-1].copy()
    )

    assert "독서는" in normal_result.text.replace(" ", "")
    assert "독서는" in rotated_result.text.replace(" ", "")
