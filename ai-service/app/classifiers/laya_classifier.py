"""LayaPiiClassifier: the real local model.

Pipeline (per document):
1. The vendored candidate finder proposes every span that *could* be personal data.
2. Laya scores each candidate against 22 labels via the public `laya.Agent.predict_batch` API.
3. Overlap selection (same rules as the model authors' reference demo): candidates nest, e.g.
   "Priya", "Priya Nair" and "I'm Priya Nair" are all proposed. Keep the most confident label
   first, then single words over multi-word runs, then the longest span; drop overlaps.
   Our one addition: capitalised runs across a line break or sentence end are discarded
   (observed on real output: "Berlin.\nWe" labelled as a city).
4. Map native labels to PiiType and join adjacent pieces of one entity (given name + surname).
"""
from __future__ import annotations

import logging
import os
import re
import threading
import time
from typing import Protocol

from app.classifiers import laya_candidates as cand
from app.classifiers.base import (
    DetectedSpan,
    DetectionResult,
    InferenceError,
    ModelInfo,
    ModelNotReadyError,
    PiiClassifier,
    PiiType,
)
from app.classifiers.labels import STANDALONE_DROP, can_join, resolve_type

log = logging.getLogger(__name__)

QUESTION_KEY = "pii"


class SpanScorer(Protocol):
    """Returns, for each candidate state, a probability for every label in laya_candidates.LABELS."""

    def score(self, states: list[dict]) -> list[dict[str, float]]: ...


class LayaSpanScorer:
    """Thin wrapper over the `laya` package. Imported lazily so the service runs without torch."""

    def __init__(self, repo: str, subfolder: str, device: str | None, batch_size: int,
                 torch_threads: int | None = None):
        import laya  # noqa: PLC0415 - heavy optional dependency
        import torch  # noqa: PLC0415

        if torch_threads:
            torch.set_num_threads(torch_threads)
        self.threads = torch.get_num_threads()
        self._agent = laya.Agent(repo, subfolder=subfolder, device=device)
        self._batch_size = batch_size
        self._question = {QUESTION_KEY: {"type": "choice", "instructions": cand.INSTRUCTIONS,
                                         "criteria": cand.LABELS}}
        self.device = str(self._agent.device)
        self.version = getattr(laya, "__version__", None)

    def score(self, states: list[dict]) -> list[dict[str, float]]:
        if not states:
            return []
        results = self._agent.predict_batch(states, self._question, batch_size=self._batch_size)
        return [r["answers"][QUESTION_KEY]["probabilities"] for r in results]


class LayaPiiClassifier(PiiClassifier):
    def __init__(self, *, repo: str, subfolder: str, device: str | None = None, batch_size: int = 16,
                 threshold: float = 0.5, region: str = "US", scorer: SpanScorer | None = None,
                 torch_threads: int | None = None, warmup: bool = False):
        self._torch_threads = torch_threads
        self._warmup = warmup
        self.spans_per_second: float | None = None  # measured by the warm-up; drives UI time/cost estimates
        self.cpu_threads: int | None = None
        self._repo = repo
        self._subfolder = subfolder
        self._device = device
        self._batch_size = batch_size
        self._threshold = threshold
        self._region = region
        self._scorer: SpanScorer | None = scorer
        self._load_error: str | None = None
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ lifecycle
    def load(self) -> None:
        """Download (first run only) and load the model. Safe to call from a background thread."""
        if self._scorer is not None:
            return
        started = time.perf_counter()
        log.info("Loading Laya PII model repo=%s subfolder=%s", self._repo, self._subfolder)
        try:
            scorer = LayaSpanScorer(self._repo, self._subfolder, self._device, self._batch_size,
                                    self._torch_threads)
        except Exception as exc:  # surfaced through /health and /model-info
            self._load_error = f"{type(exc).__name__}: {exc}"
            log.exception("Laya model failed to load")
            return
        log.info("Laya PII model loaded in %.1fs (device=%s, torch threads=%s, visible cpus=%s)",
                 time.perf_counter() - started, scorer.device, scorer.threads, os.cpu_count())
        self.cpu_threads = scorer.threads
        if self._warmup:
            self.spans_per_second = self._warm_up(scorer)
        self._scorer = scorer  # ready only after warm-up, so /health turns green when requests are fast

    @staticmethod
    def _warm_up(scorer: SpanScorer) -> float | None:
        """Scores a small fixed synthetic text once. Logs spans/second: the number to watch when tuning threads."""
        text = ("Meeting notes. Jordan Miller met Priya Nair in Berlin on 12 March 2024. "
                "Call Sam Lee on 555-0100 or email sam.lee@example.com about the Paris office.")
        states = [state for _, _, state in cand.build_states(text, "US")]
        started = time.perf_counter()
        try:
            scorer.score(states)
        except Exception:  # warm-up must never block the service from starting
            log.exception("Laya warm-up failed")
            return None
        seconds = time.perf_counter() - started
        rate = len(states) / seconds if seconds else None
        log.info("Laya warm-up: %d spans in %.2fs (%.1f spans/s)", len(states), seconds, rate or 0.0)
        return rate

    def is_ready(self) -> bool:
        return self._scorer is not None

    @property
    def load_error(self) -> str | None:
        return self._load_error

    # ------------------------------------------------------------------ inference
    def detect(self, text: str) -> DetectionResult:
        scorer = self._scorer
        if scorer is None:
            raise ModelNotReadyError(self._load_error or "Laya model is still loading")
        rows = cand.build_states(text, self._region)
        try:
            with self._lock:  # one forward pass at a time; torch already uses all cores
                probs = scorer.score([state for _, _, state in rows])
        except Exception as exc:
            raise InferenceError(f"Laya inference failed: {type(exc).__name__}") from exc
        if len(probs) != len(rows):
            raise InferenceError("Laya returned a different number of results than candidates")
        spans = select_entities(text, [(span, fams) for span, fams, _ in rows], probs, self._threshold)
        return DetectionResult(entities=spans, candidates_scored=len(rows))

    def info(self) -> ModelInfo:
        scorer = self._scorer
        return ModelInfo(
            name="laya",
            implementation="LayaPiiClassifier",
            model_id=f"{self._repo}/{self._subfolder}",
            is_mock=False,
            version=getattr(scorer, "version", None),
            device=getattr(scorer, "device", self._device),
            labels=list(cand.LABELS),
            supported_types=sorted({t.value for t in _supported_types()}),
            notes=("Span classifier: a rule-based candidate finder proposes spans, Laya labels each one. "
                   "No IBAN or ORGANIZATION label; English only; trained on synthetic data."),
        )


def _supported_types() -> set[PiiType]:
    from app.classifiers.labels import LAYA_TO_TYPE  # noqa: PLC0415
    return set(LAYA_TO_TYPE.values()) | {PiiType.DATE_OF_BIRTH}


_TITLES = {w.lower() for w in cand.TITLE_WORDS.split("|")}
_SENTENCE_END = re.compile(r"([\w'’\-]+)\.\s")


def crosses_boundary(span: str, families: list[str]) -> bool:
    """A capitalised run over a line break or a sentence end ("Berlin.\nWe") is never one entity.

    Only affects selection: the model still scores these candidates exactly as trained, but they are
    not allowed to win over their single-word sub-candidates. Titles ("Dr. Smith") are exempt.
    """
    if not all(f == "CAPS_RUN" for f in families):
        return False
    if "\n" in span:
        return True
    return any(m.group(1).lower() not in _TITLES for m in _SENTENCE_END.finditer(span))


def select_entities(text: str, candidates: list[tuple[tuple[int, int], list[str]]],
                    probs: list[dict[str, float]], threshold: float) -> list[DetectedSpan]:
    """Turn scored, nested candidates into non-overlapping, typed entities."""
    kept = []
    for ((s, e), fams), pr in zip(candidates, probs):
        pii = 1.0 - pr.get(cand.NOT_PII, 0.0)
        if pii < threshold or crosses_boundary(text[s:e], fams):
            continue
        label = max((k for k in pr if k != cand.NOT_PII), key=pr.get)
        multi_word = all(f == "CAPS_RUN" for f in fams)
        kept.append(((-round(pr[label], 1), multi_word, s - e), (s, e, label, pii)))

    chosen: list[tuple[int, int, str, float]] = []
    for _, ent in sorted(kept, key=lambda k: k[0]):
        s, e = ent[0], ent[1]
        if all(e <= c[0] or s >= c[1] for c in chosen):
            chosen.append(ent)
    chosen.sort(key=lambda c: c[0])

    merged: list[dict] = []
    for s, e, label, pii in chosen:
        ptype = resolve_type(label, text, s)
        prev = merged[-1] if merged else None
        joined = can_join(prev["type"], ptype, text[prev["end"]:s]) if prev else None
        if prev and joined:
            prev.update(end=e, type=joined, confidence=min(prev["confidence"], pii),
                        labels=prev["labels"] + (label,))
        else:
            merged.append({"start": s, "end": e, "type": ptype, "confidence": pii, "labels": (label,)})

    out = []
    for m in merged:
        if set(m["labels"]) <= STANDALONE_DROP:
            continue
        out.append(DetectedSpan(text=text[m["start"]:m["end"]], type=m["type"],
                                confidence=round(m["confidence"], 4), start=m["start"], end=m["end"],
                                model_labels=m["labels"]))
    return out
