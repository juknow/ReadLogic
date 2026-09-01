import json
import os
from pathlib import Path

import numpy as np
import pytest
from PIL import Image, ImageDraw, ImageFont
from quality_metrics import character_error_rate

from readlogic_ocr.engine import PaddleOcrEngine

RUN_MODEL_TESTS = os.getenv("RUN_OCR_MODEL_TESTS") == "1"
GOLDEN_PATH = Path(__file__).parent / "golden" / "korean_baseline.json"


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
    golden = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    reference_text = str(golden["referenceText"])
    font_path = find_font()
    if font_path is None:
        pytest.fail("A Korean test font is required for the model smoke test.")
    font = ImageFont.truetype(str(font_path), 72)
    page = Image.new("RGB", (1800, 650), "white")
    draw = ImageDraw.Draw(page)
    for line_index, line in enumerate(reference_text.splitlines()):
        draw.text((80, 80 + line_index * 180), line, fill="black", font=font)
    engine = PaddleOcrEngine()

    normal_result = engine.recognize(np.asarray(page)[:, :, ::-1].copy())
    rotated_result = engine.recognize(
        np.asarray(page.rotate(90, expand=True))[:, :, ::-1].copy()
    )

    minimum_accuracy = float(golden["minimumCharacterAccuracy"])
    assert 1 - character_error_rate(reference_text, normal_result.text) >= minimum_accuracy
    assert 1 - character_error_rate(reference_text, rotated_result.text) >= minimum_accuracy
