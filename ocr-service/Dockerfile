FROM python:3.11.16-slim-bookworm

COPY --from=ghcr.io/astral-sh/uv:0.12.5 /uv /uvx /bin/

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PADDLE_HOME=/models/paddle \
    PADDLE_PDX_CACHE_HOME=/models/paddlex

WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        fonts-noto-cjk \
        libgl1 \
        libglib2.0-0 \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev \
    && .venv/bin/python -c "import paddle"

COPY src src

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONPATH=/app/src

EXPOSE 8000

CMD ["uvicorn", "readlogic_ocr.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
