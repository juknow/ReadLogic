FROM ghcr.io/astral-sh/uv:0.12.5-python3.11-bookworm-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PADDLE_HOME=/models/paddle \
    PADDLEX_HOME=/models/paddlex

WORKDIR /app

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY src src

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONPATH=/app/src

EXPOSE 8000

CMD ["uvicorn", "readlogic_ocr.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
