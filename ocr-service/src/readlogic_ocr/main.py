import logging
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from time import perf_counter
from typing import Annotated
from uuid import UUID

from fastapi import FastAPI, File, Header, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from readlogic_ocr.config import Settings, get_settings
from readlogic_ocr.engine import OcrEngine, PaddleOcrEngine
from readlogic_ocr.errors import (
    ImageTooLargeError,
    OcrNotReadyError,
    OcrServiceError,
    UnsupportedImageTypeError,
)
from readlogic_ocr.image import decode_image
from readlogic_ocr.runtime import RuntimeState
from readlogic_ocr.schemas import ErrorResponse, HealthResponse, OcrResponse, ReadinessResponse

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
    ) -> OcrResponse:
        current_settings: Settings = request.app.state.settings
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
        result = await run_in_threadpool(current_runtime.recognize, decoded_image)
        processing_time_ms = max(0, round((perf_counter() - started_at) * 1000))
        logger.info("OCR request completed. requestId=%s", request_id)
        return OcrResponse(
            text=result.text,
            confidence=result.confidence,
            engine=current_runtime.engine_name,
            model=current_runtime.model_name,
            processingTimeMs=processing_time_ms,
        )

    return application


app = create_app()
