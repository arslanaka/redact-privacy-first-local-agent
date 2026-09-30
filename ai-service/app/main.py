"""FastAPI entry point for local PII inference.

Document text arrives in POST /v1/pii/detect, is analysed in-process, and is never logged,
persisted or forwarded anywhere. Logs carry request id, sizes and timings only.
"""
from __future__ import annotations

import logging
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from app.classifiers.base import InferenceError, ModelNotReadyError, PiiClassifier
from app.classifiers.factory import create_classifier
from app.classifiers.laya_classifier import LayaPiiClassifier
from app.config import Settings, get_settings
from app.schemas import (
    DetectRequest,
    DetectResponse,
    EntityOut,
    ErrorResponse,
    HealthResponse,
    ModelInfoResponse,
)

log = logging.getLogger("ai_service")


def create_app(settings: Settings | None = None, classifier: PiiClassifier | None = None) -> FastAPI:
    settings = settings or get_settings()
    logging.basicConfig(level=settings.log_level,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.classifier = classifier or create_classifier(settings)
        yield

    app = FastAPI(
        title="Local Privacy Agent - AI Service",
        version="0.1.0",
        description="Local PII inference. Wraps the Laya PII span classifier behind a PiiClassifier "
                    "abstraction. No document content leaves this process.",
        lifespan=lifespan,
    )

    def get_classifier(request: Request) -> PiiClassifier:
        return request.app.state.classifier

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
        started = time.perf_counter()
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        if request.url.path != "/health":
            log.info("request_id=%s method=%s path=%s status=%s duration_ms=%d", request_id,
                     request.method, request.url.path, response.status_code,
                     (time.perf_counter() - started) * 1000)
        return response

    @app.exception_handler(ModelNotReadyError)
    async def model_not_ready(_: Request, exc: ModelNotReadyError):
        return JSONResponse(status_code=503, content=ErrorResponse(
            error="MODEL_UNAVAILABLE", message=f"Local PII model is currently unavailable: {exc}").model_dump())

    @app.exception_handler(InferenceError)
    async def inference_failed(_: Request, exc: InferenceError):
        return JSONResponse(status_code=500, content=ErrorResponse(
            error="INFERENCE_FAILED", message=str(exc)).model_dump())

    @app.get("/health", response_model=HealthResponse, response_model_by_alias=True)
    async def health(request: Request):
        clf = get_classifier(request)
        info = clf.info()
        if clf.is_ready():
            status, detail = "ready", None
        elif isinstance(clf, LayaPiiClassifier) and clf.load_error:
            status, detail = "error", clf.load_error
        else:
            status, detail = "loading", "Model is downloading or loading; first start can take a few minutes."
        body = HealthResponse(status=status, model=info.name, is_mock=info.is_mock, detail=detail,
                              spans_per_second=getattr(clf, "spans_per_second", None),
                              cpu_threads=getattr(clf, "cpu_threads", None),
                              max_text_chars=settings.max_text_chars)
        return JSONResponse(status_code=200 if status == "ready" else 503,
                            content=body.model_dump(by_alias=True))

    @app.get("/model-info", response_model=ModelInfoResponse, response_model_by_alias=True)
    async def model_info(request: Request):
        clf = get_classifier(request)
        i = clf.info()
        return ModelInfoResponse(name=i.name, implementation=i.implementation, model_id=i.model_id,
                                 is_mock=i.is_mock, ready=clf.is_ready(), version=i.version, device=i.device,
                                 labels=i.labels, supported_types=i.supported_types, notes=i.notes)

    @app.post("/v1/pii/detect", response_model=DetectResponse, response_model_by_alias=True,
              responses={422: {"model": ErrorResponse}, 503: {"model": ErrorResponse}})
    async def detect(body: DetectRequest, request: Request):
        if len(body.text) > settings.max_text_chars:
            return JSONResponse(status_code=422, content=ErrorResponse(
                error="TEXT_TOO_LARGE",
                message=f"Text exceeds {settings.max_text_chars} characters").model_dump())
        clf = get_classifier(request)
        started = time.perf_counter()
        result = await run_in_threadpool(clf.detect, body.text)
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        info = clf.info()
        log.info("detect request_id=%s chars=%d candidates=%d entities=%d duration_ms=%d",
                 request.headers.get("X-Request-ID", "-"), len(body.text), result.candidates_scored,
                 len(result.entities), elapsed_ms)
        return DetectResponse(
            model=info.name,
            is_mock=info.is_mock,
            entities=[EntityOut(text=e.text, type=e.type.value, confidence=e.confidence, start=e.start,
                                end=e.end, model_labels=list(e.model_labels)) for e in result.entities],
            candidates_scored=result.candidates_scored,
            processing_time_ms=elapsed_ms,
        )

    return app


app = create_app()
