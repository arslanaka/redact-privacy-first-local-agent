from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class DetectRequest(CamelModel):
    text: str = Field(..., description="Plain text to analyse. Never logged.")


class EntityOut(CamelModel):
    text: str
    type: str
    confidence: float = Field(..., ge=0.0, le=1.0)
    start: int = Field(..., ge=0)
    end: int = Field(..., ge=0)
    model_labels: list[str] = Field(default_factory=list)


class DetectResponse(CamelModel):
    model: str
    is_mock: bool
    entities: list[EntityOut]
    candidates_scored: int
    processing_time_ms: int


class HealthResponse(CamelModel):
    status: str  # ready | loading | error
    model: str
    is_mock: bool
    detail: str | None = None
    spans_per_second: float | None = None  # measured at start-up (real model only)
    cpu_threads: int | None = None
    max_text_chars: int | None = None


class ModelInfoResponse(CamelModel):
    name: str
    implementation: str
    model_id: str
    is_mock: bool
    ready: bool
    version: str | None = None
    device: str | None = None
    labels: list[str]
    supported_types: list[str]
    notes: str


class ErrorResponse(CamelModel):
    error: str
    message: str
