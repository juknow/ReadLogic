from fastapi import FastAPI, HTTPException, Request, status

from readlogic_ocr.config import get_settings
from readlogic_ocr.runtime import RuntimeState

settings = get_settings()
runtime = RuntimeState(engine=settings.engine_name, model=settings.model_name)
app = FastAPI(
    title="ReadLogic OCR Service",
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


@app.get("/health/live")
def get_liveness() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready")
def get_readiness(request: Request) -> dict[str, str]:
    current_runtime: RuntimeState = request.app.state.runtime
    if not current_runtime.ready:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "OCR_NOT_READY", "message": "OCR model is not ready."},
        )
    return {
        "status": "UP",
        "engine": current_runtime.engine,
        "model": current_runtime.model,
    }


app.state.runtime = runtime
