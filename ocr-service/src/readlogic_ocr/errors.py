from dataclasses import dataclass


@dataclass
class OcrServiceError(Exception):
    code: str
    message: str
    status_code: int


class InvalidImageError(OcrServiceError):
    def __init__(self, message: str = "The uploaded image cannot be decoded.") -> None:
        super().__init__("INVALID_IMAGE", message, 400)


class UnsupportedImageTypeError(OcrServiceError):
    def __init__(self) -> None:
        super().__init__(
            "UNSUPPORTED_IMAGE_TYPE",
            "Only JPEG, PNG, and WebP images are supported.",
            415,
        )


class ImageTooLargeError(OcrServiceError):
    def __init__(self, message: str) -> None:
        super().__init__("IMAGE_TOO_LARGE", message, 400)


class InvalidOcrLanguageError(OcrServiceError):
    def __init__(self) -> None:
        super().__init__(
            "INVALID_OCR_LANGUAGE",
            "OCR language must be one of auto, ko, en, ja, or zh.",
            400,
        )


class OcrBusyError(OcrServiceError):
    def __init__(self) -> None:
        super().__init__("OCR_BUSY", "The OCR engine is busy.", 429)


class OcrNotReadyError(OcrServiceError):
    def __init__(self) -> None:
        super().__init__("OCR_NOT_READY", "The OCR model is not ready.", 503)


class OcrInferenceError(OcrServiceError):
    def __init__(self) -> None:
        super().__init__("OCR_INFERENCE_FAILED", "OCR inference failed.", 500)
