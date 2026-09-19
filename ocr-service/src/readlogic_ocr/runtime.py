from collections.abc import Callable
from threading import BoundedSemaphore, Lock

import numpy as np

from readlogic_ocr.engine import EngineResult, OcrEngine
from readlogic_ocr.errors import OcrBusyError, OcrNotReadyError


class RuntimeState:
    def __init__(
        self,
        engine_name: str,
        model_name: str,
        max_concurrency: int,
        engine_factory: Callable[[], OcrEngine],
    ) -> None:
        self.engine_name = engine_name
        self.model_name = model_name
        self._engine: OcrEngine | None = None
        self._engine_factory = engine_factory
        self._initialization_lock = Lock()
        self._semaphore = BoundedSemaphore(max_concurrency)

    @property
    def ready(self) -> bool:
        return self._engine is not None

    def initialize(self) -> None:
        with self._initialization_lock:
            if self._engine is None:
                self._engine = self._engine_factory()

    def recognize(self, image: np.ndarray, language: str = "ko") -> EngineResult:
        engine = self._engine
        if engine is None:
            raise OcrNotReadyError()
        if not self._semaphore.acquire(blocking=False):
            raise OcrBusyError()
        try:
            return engine.recognize(image, language)
        finally:
            self._semaphore.release()

    def mark_not_ready(self) -> None:
        self._engine = None
