"""MockPiiClassifier - TEMPORARY / TEST-ONLY stand-in for the real model.

This is NOT a machine-learning model. It is a handful of crude patterns so the pipeline can run on
machines that cannot load the 850 MB Laya model (and in tests). It reports `is_mock=True` through
/model-info, and the backend and UI show a "MOCK MODEL" warning whenever it is active.
Select it with PII_CLASSIFIER=mock. Never use it for evaluation results.
"""
from __future__ import annotations

import re

from app.classifiers.base import DetectedSpan, DetectionResult, ModelInfo, PiiClassifier, PiiType

_FIRST_NAMES = ("Anna|Markus|Jordan|Laura|Priya|Oliver|Sofia|Lukas|Emma|Jonas|Mia|David|Sarah|"
                "Thomas|Julia|Daniel|Lena|Felix|Clara|Noah")

_PATTERNS: list[tuple[PiiType, re.Pattern[str], float]] = [
    (PiiType.EMAIL, re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"), 0.95),
    (PiiType.PHONE, re.compile(r"\+\d{1,3}(?:[ \-]?\d{2,5}){2,4}"), 0.80),
    (PiiType.PERSON, re.compile(rf"\b(?:{_FIRST_NAMES})(?: [A-Z][a-z]+)?\b"), 0.75),
]


class MockPiiClassifier(PiiClassifier):
    def detect(self, text: str) -> DetectionResult:
        found: list[DetectedSpan] = []
        taken: list[tuple[int, int]] = []
        for ptype, rx, conf in _PATTERNS:
            for m in rx.finditer(text):
                s, e = m.span()
                if any(s < te and e > ts for ts, te in taken):
                    continue
                taken.append((s, e))
                found.append(DetectedSpan(text=m.group(0), type=ptype, confidence=conf, start=s, end=e,
                                          model_labels=("mock",)))
        found.sort(key=lambda d: d.start)
        return DetectionResult(entities=found, candidates_scored=len(found))

    def info(self) -> ModelInfo:
        return ModelInfo(
            name="mock",
            implementation="MockPiiClassifier",
            model_id="mock",
            is_mock=True,
            labels=["mock"],
            supported_types=[PiiType.EMAIL.value, PiiType.PHONE.value, PiiType.PERSON.value],
            notes="TEMPORARY MOCK: crude patterns, not a model. Set PII_CLASSIFIER=laya for real inference.",
        )
