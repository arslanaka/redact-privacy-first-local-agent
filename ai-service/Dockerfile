FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HF_HOME=/models/hf \
    HF_HUB_DISABLE_TELEMETRY=1 \
    USE_TF=0

WORKDIR /app

# CPU-only torch keeps the image small (no CUDA libraries).
RUN pip install --no-cache-dir torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY NOTICE ./NOTICE

RUN useradd --create-home --uid 10001 app && mkdir -p /models && chown app /models
USER app

EXPOSE 8000
# /health returns 503 while the model downloads (~850 MB on first start), so allow a long start period.
HEALTHCHECK --interval=15s --timeout=5s --start-period=600s --retries=5 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health').status==200 else 1)"
# Override only for an IPv6-only private network (UVICORN_HOST=::). Note that asyncio makes "::" IPv6-only,
# so on dual-stack networks (current Railway environments) keep 0.0.0.0.
ENV UVICORN_HOST=0.0.0.0
CMD ["sh", "-c", "exec uvicorn app.main:app --host \"$UVICORN_HOST\" --port 8000"]
