from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

import numpy as np

from readlogic_ocr.models import CorrectionMetadata, OcrWarning


@dataclass(frozen=True)
class PreprocessingResult:
    image: np.ndarray
    fallback_image: np.ndarray
    correction: CorrectionMetadata
    warnings: tuple[OcrWarning, ...] = ()


class DocumentPreprocessor(Protocol):
    def correct(self, image: np.ndarray) -> PreprocessingResult: ...


class PaddleDocumentPreprocessor:
    def __init__(self) -> None:
        from paddleocr import DocPreprocessor

        self._pipeline = DocPreprocessor(
            doc_orientation_classify_model_name="PP-LCNet_x1_0_doc_ori",
            doc_unwarping_model_name="UVDoc",
            use_doc_orientation_classify=True,
            use_doc_unwarping=True,
        )

    def correct(self, image: np.ndarray) -> PreprocessingResult:
        try:
            results = self._pipeline.predict(image)
            if len(results) != 1:
                return _fallback(image, "Document preprocessing returned no usable result.")
            result = results[0]
            output_image = np.asarray(_get_value(result, "output_img"))
            if output_image.ndim != 3 or output_image.shape[2] != 3:
                return _fallback(image, "Document preprocessing returned an invalid image.")
            angle = _normalize_angle(_get_value(result, "angle"))
            settings = _get_value(result, "model_settings")
            unwarping_applied = (
                bool(settings.get("use_doc_unwarping", False))
                if isinstance(settings, dict)
                else True
            )
            return PreprocessingResult(
                image=output_image.copy(),
                fallback_image=image,
                correction=CorrectionMetadata(
                    orientation_applied=angle in {90, 180, 270},
                    rotation_degrees=angle,
                    unwarping_applied=unwarping_applied,
                ),
            )
        except Exception:
            return _fallback(
                image,
                "Document preprocessing failed; the EXIF-normalized image was used.",
            )


def identity_preprocessing(image: np.ndarray) -> PreprocessingResult:
    return PreprocessingResult(
        image=image,
        fallback_image=image,
        correction=CorrectionMetadata(),
    )


def _fallback(image: np.ndarray, message: str) -> PreprocessingResult:
    return PreprocessingResult(
        image=image,
        fallback_image=image,
        correction=CorrectionMetadata(fallback_used=True),
        warnings=(
            OcrWarning(
                code="DOCUMENT_CORRECTION_FALLBACK",
                message=message,
            ),
        ),
    )


def _get_value(result: Any, key: str) -> Any:
    return result[key]


def _normalize_angle(value: Any) -> int:
    try:
        angle = int(value) % 360
    except (TypeError, ValueError):
        return 0
    return angle if angle in {0, 90, 180, 270} else 0
