from io import BytesIO

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

from readlogic_ocr.errors import (
    ImageTooLargeError,
    InvalidImageError,
    OcrServiceError,
)


def decode_image(content: bytes, max_pixels: int) -> np.ndarray:
    try:
        with Image.open(BytesIO(content)) as source:
            width, height = source.size
            if width <= 0 or height <= 0:
                raise InvalidImageError()
            if width * height > max_pixels:
                raise ImageTooLargeError(
                    f"The decoded image cannot exceed {max_pixels} pixels."
                )
            source.load()
            normalized = ImageOps.exif_transpose(source).convert("RGB")
            return cv2.cvtColor(np.asarray(normalized), cv2.COLOR_RGB2BGR)
    except OcrServiceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exception:
        raise InvalidImageError() from exception
