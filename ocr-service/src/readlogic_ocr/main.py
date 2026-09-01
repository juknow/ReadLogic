import logging
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from time import perf_counter
from typing import Annotated
from uuid import UUID

from fastapi import FastAPI, File, Form, Header, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from readlogic_ocr.config import Settings, get_settings
from readlogic_ocr.engine import EngineResult, OcrEngine, PaddleOcrEngine
from readlogic_ocr.errors import (
    ImageTooLargeError,
    InvalidOcrLanguageError,
    OcrNotReadyError,
    OcrServiceError,
    UnsupportedImageTypeError,
)
from readlogic_ocr.image import decode_image
from readlogic_ocr.models import OcrLine, OcrParagraph
from readlogic_ocr.recognition import SUPPORTED_LANGUAGES
from readlogic_ocr.runtime import RuntimeState
from readlogic_ocr.schemas import (
    CorrectionResponse,
    ErrorResponse,
    HealthResponse,
    ImageInfoResponse,
    ModelInfoResponse,
    OcrDocumentResponse,
    OcrLineResponse,
    OcrParagraphResponse,
    OcrResponse,
    ReadinessResponse,
    WarningResponse,
)

logger = logging.getLogger(__name__)
SUPPORTED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}


def create_app(
    settings: Settings | None = None,
    engine_factory: Callable[[], OcrEngine] = PaddleOcrEngine,
) -> FastAPI:
    service_settings = settings or get_settings()
    runtime = RuntimeState(
        engine_name=service_settings.engine_name,
        model_name=service_settings.model_name,
        max_concurrency=service_settings.max_concurrency,
        engine_factory=engine_factory,
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        try:
            await run_in_threadpool(runtime.initialize)
        except Exception:
            logger.exception("OCR model initialization failed.")
        yield
        runtime.mark_not_ready()

    application = FastAPI(
        title="ReadLogic OCR Service",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    application.state.runtime = runtime
    application.state.settings = service_settings

    @application.exception_handler(OcrServiceError)
    async def handle_ocr_error(_: Request, error: OcrServiceError) -> JSONResponse:
        return JSONResponse(
            status_code=error.status_code,
            content=ErrorResponse(code=error.code, message=error.message).model_dump(),
        )

    @application.get("/health/live", response_model=HealthResponse)
    def get_liveness() -> HealthResponse:
        return HealthResponse(status="UP")

    @application.get("/health/ready", response_model=ReadinessResponse)
    def get_readiness(request: Request) -> ReadinessResponse:
        current_runtime: RuntimeState = request.app.state.runtime
        if not current_runtime.ready:
            raise OcrNotReadyError()
        return ReadinessResponse(
            status="UP",
            engine=current_runtime.engine_name,
            model=current_runtime.model_name,
        )

    @application.post("/internal/v1/ocr", response_model=OcrResponse)
    async def recognize_page(
        request: Request,
        request_id: Annotated[UUID, Header(alias="X-Ocr-Request-Id")],
        image: Annotated[UploadFile, File()],
        language: Annotated[str, Form()] = "ko",
    ) -> OcrResponse:
        current_settings: Settings = request.app.state.settings
        normalized_language = language.strip().lower() or "ko"
        if normalized_language not in SUPPORTED_LANGUAGES:
            raise InvalidOcrLanguageError()
        if image.content_type not in SUPPORTED_IMAGE_TYPES:
            raise UnsupportedImageTypeError()
        content = await image.read(current_settings.max_image_size_bytes + 1)
        if len(content) > current_settings.max_image_size_bytes:
            raise ImageTooLargeError(
                f"The uploaded image cannot exceed {current_settings.max_image_size_bytes} bytes."
            )
        decoded_image = await run_in_threadpool(
            decode_image,
            content,
            current_settings.max_image_pixels,
        )
        started_at = perf_counter()
        current_runtime: RuntimeState = request.app.state.runtime
        result = await run_in_threadpool(
            current_runtime.recognize,
            decoded_image,
            normalized_language,
        )
        processing_time_ms = max(0, round((perf_counter() - started_at) * 1000))
        logger.info("OCR request completed. requestId=%s", request_id)
        return OcrResponse(
            text=result.text,
            confidence=result.confidence,
            engine=current_runtime.engine_name,
            model=result.primary_model,
            processingTimeMs=processing_time_ms,
            document=_to_document_response(result),
        )

    return application


app = create_app()


def _to_document_response(result: EngineResult) -> OcrDocumentResponse:
    warnings = [WarningResponse(code=item.code, message=item.message) for item in result.warnings]
    if not result.paragraphs:
        warnings.append(
            WarningResponse(
                code="NO_TEXT_DETECTED",
                message="No text was detected in the image.",
            )
        )
    return OcrDocumentResponse(
        requestedLanguage=result.requested_language,
        detectedLanguage=result.detected_language,
        image=ImageInfoResponse(width=result.image_size[0], height=result.image_size[1]),
        correction=CorrectionResponse(
            exifApplied=result.correction.exif_applied,
            orientationApplied=result.correction.orientation_applied,
            rotationDegrees=result.correction.rotation_degrees,
            unwarpingApplied=result.correction.unwarping_applied,
            fallbackUsed=result.correction.fallback_used,
        ),
        models=ModelInfoResponse(
            orientation="PP-LCNet_x1_0_doc_ori",
            unwarping="UVDoc",
            detector="PP-OCRv5_server_det",
            textLineOrientation="PP-LCNet_x1_0_textline_ori",
            recognizers=sorted({line.model for line in result.lines} or {result.primary_model}),
        ),
        warnings=warnings,
        paragraphs=[_to_paragraph_response(paragraph) for paragraph in result.paragraphs],
    )


def _to_paragraph_response(paragraph: OcrParagraph) -> OcrParagraphResponse:
    return OcrParagraphResponse(
        id=paragraph.id,
        order=paragraph.order,
        type=paragraph.type,
        text=paragraph.text,
        bbox=paragraph.bbox,
        confidence=paragraph.confidence,
        lines=[_to_line_response(line) for line in paragraph.lines],
    )


def _to_line_response(line: OcrLine) -> OcrLineResponse:
    return OcrLineResponse(
        id=line.id,
        order=line.order,
        text=line.text,
        bbox=line.bbox,
        polygon=line.polygon,
        detectionConfidence=line.detection_confidence,
        confidence=line.confidence,
        language=line.language,
        model=line.model,
    )
