FROM ghcr.io/docling-project/docling-serve-cpu:v1.32.0

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DAYTRACE_ASR_BACKEND=faster-whisper \
    DAYTRACE_ASR_MODEL=small \
    DAYTRACE_ASR_DEVICE=cpu \
    DAYTRACE_ASR_COMPUTE_TYPE=int8 \
    DAYTRACE_ASR_CPU_THREADS=8 \
    DAYTRACE_ASR_NUM_WORKERS=1 \
    DAYTRACE_DATA_DIR=/var/lib/daytrace/data \
    DAYTRACE_DATABASE_URL=sqlite:////var/lib/daytrace/data/daytrace.sqlite3 \
    HF_HOME=/var/lib/daytrace/models \
    XDG_CACHE_HOME=/var/lib/daytrace/models

WORKDIR /app

USER root

COPY pyproject.toml ./
COPY app ./app

RUN python -m pip install --no-cache-dir --upgrade pip \
    && python -m pip install --no-cache-dir '.[asr]' \
    && useradd --create-home --uid 10001 daytrace \
    && mkdir -p /var/lib/daytrace/data /var/lib/daytrace/models \
    && chown -R daytrace:daytrace /app /var/lib/daytrace

USER daytrace
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
