from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    status: str


class ReadinessResponse(HealthResponse):
    engine: str
    model: str


class ImageInfoResponse(BaseModel):
    width: int = Field(ge=0)
    height: int = Field(ge=0)


class CorrectionResponse(BaseModel):
    exifApplied: bool
    orientationApplied: bool
    rotationDegrees: int
    unwarpingApplied: bool
    fallbackUsed: bool


class ModelInfoResponse(BaseModel):
    orientation: str
    unwarping: str
    detector: str
    textLineOrientation: str
    recognizers: list[str]


class WarningResponse(BaseModel):
    code: str
    message: str


class OcrLineResponse(BaseModel):
    id: int = Field(ge=1)
    order: int = Field(ge=1)
    text: str
    bbox: tuple[float, float, float, float]
    polygon: tuple[tuple[float, float], ...]
    detectionConfidence: float | None = Field(default=None, ge=0, le=1)
    confidence: float = Field(ge=0, le=1)
    language: str
    model: str


class OcrParagraphResponse(BaseModel):
    id: int = Field(ge=1)
    order: int = Field(ge=1)
    type: str
    text: str
    bbox: tuple[float, float, float, float]
    confidence: float = Field(ge=0, le=1)
    lines: list[OcrLineResponse]


class OcrDocumentResponse(BaseModel):
    schemaVersion: int = 1
    requestedLanguage: str
    detectedLanguage: str
    coordinateSpace: str = "corrected_image"
    image: ImageInfoResponse
    correction: CorrectionResponse
    models: ModelInfoResponse
    warnings: list[WarningResponse]
    paragraphs: list[OcrParagraphResponse]


class OcrResponse(BaseModel):
    confidence: float | None = Field(default=None, ge=0, le=1)
    engine: str
    model: str
    processingTimeMs: int = Field(ge=0)
    text: str
    document: OcrDocumentResponse


class ErrorResponse(BaseModel):
    code: str
    message: str
