from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    status: str


class ReadinessResponse(HealthResponse):
    engine: str
    model: str


class OcrResponse(BaseModel):
    confidence: float | None = Field(default=None, ge=0, le=1)
    engine: str
    model: str
    processingTimeMs: int = Field(ge=0)
    text: str


class ErrorResponse(BaseModel):
    code: str
    message: str
