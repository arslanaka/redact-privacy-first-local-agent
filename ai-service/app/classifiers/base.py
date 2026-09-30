"""The model abstraction the rest of the service depends on.

Routes and the Spring Boot backend only ever see `PiiClassifier`; which implementation sits behind
it (the real Laya model or the clearly labelled mock) is a configuration decision.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import StrEnum


class PiiType(StrEnum):
    """The application's PII categories. Must stay in sync with the backend's PiiType enum."""

    PERSON = "PERSON"
    EMAIL = "EMAIL"
    PHONE = "PHONE"
    ADDRESS = "ADDRESS"
    IBAN = "IBAN"
    CREDIT_CARD = "CREDIT_CARD"
    ID_NUMBER = "ID_NUMBER"
    DATE_OF_BIRTH = "DATE_OF_BIRTH"
    ORGANIZATION = "ORGANIZATION"
    LOCATION = "LOCATION"
    OTHER = "OTHER"


@dataclass(frozen=True)
class DetectedSpan:
    text: str
    type: PiiType
    confidence: float
    """Probability that the span is personal data at all (1 - P(not pii)). A model signal, not certainty."""
    start: int
    end: int
    model_labels: tuple[str, ...] = ()
    """The model's native label(s) for this span, before mapping to PiiType. Kept for transparency."""


@dataclass(frozen=True)
class DetectionResult:
    entities: list[DetectedSpan]
    candidates_scored: int = 0


@dataclass(frozen=True)
class ModelInfo:
    name: str
    implementation: str
    model_id: str
    is_mock: bool
    version: str | None = None
    device: str | None = None
    labels: list[str] = field(default_factory=list)
    supported_types: list[str] = field(default_factory=list)
    notes: str = ""


class ModelNotReadyError(RuntimeError):
    """The model is still loading or failed to load. Maps to HTTP 503."""


class InferenceError(RuntimeError):
    """Inference itself failed. Maps to HTTP 500."""


class PiiClassifier(ABC):
    """Finds PII spans in plain text. Implementations must be local: no network calls with document text."""

    @abstractmethod
    def detect(self, text: str) -> DetectionResult: ...

    @abstractmethod
    def info(self) -> ModelInfo: ...

    def is_ready(self) -> bool:
        return True
