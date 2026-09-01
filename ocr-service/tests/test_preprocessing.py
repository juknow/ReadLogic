import numpy as np

from readlogic_ocr.preprocessing import PaddleDocumentPreprocessor


class FakeDocPipeline:
    def __init__(self, result: object = None, error: Exception | None = None) -> None:
        self.result = result
        self.error = error

    def predict(self, _: np.ndarray) -> list[object]:
        if self.error is not None:
            raise self.error
        return [self.result]


def create_preprocessor(pipeline: FakeDocPipeline) -> PaddleDocumentPreprocessor:
    preprocessor = PaddleDocumentPreprocessor.__new__(PaddleDocumentPreprocessor)
    preprocessor._pipeline = pipeline  # type: ignore[attr-defined]
    return preprocessor


def test_records_document_rotation_and_unwarping() -> None:
    original = np.zeros((20, 30, 3), dtype=np.uint8)
    corrected = np.ones((30, 20, 3), dtype=np.uint8)
    preprocessor = create_preprocessor(
        FakeDocPipeline(
            {
                "angle": 90,
                "model_settings": {"use_doc_unwarping": True},
                "output_img": corrected,
            }
        )
    )

    result = preprocessor.correct(original)

    assert np.array_equal(result.image, corrected)
    assert result.correction.orientation_applied
    assert result.correction.rotation_degrees == 90
    assert result.correction.unwarping_applied
    assert not result.correction.fallback_used
    assert result.warnings == ()


def test_falls_back_to_exif_normalized_image_when_preprocessing_fails() -> None:
    original = np.zeros((20, 30, 3), dtype=np.uint8)
    preprocessor = create_preprocessor(FakeDocPipeline(error=RuntimeError("failed")))

    result = preprocessor.correct(original)

    assert result.image is original
    assert result.correction.fallback_used
    assert result.warnings[0].code == "DOCUMENT_CORRECTION_FALLBACK"


def test_falls_back_when_preprocessed_image_is_invalid() -> None:
    original = np.zeros((20, 30, 3), dtype=np.uint8)
    preprocessor = create_preprocessor(
        FakeDocPipeline(
            {
                "angle": 0,
                "model_settings": {"use_doc_unwarping": True},
                "output_img": np.zeros((20, 30), dtype=np.uint8),
            }
        )
    )

    result = preprocessor.correct(original)

    assert result.image is original
    assert result.correction.fallback_used
