from __future__ import annotations

import pytest

from app.classifiers import laya_candidates as cand


class FakeScorer:
    """Deterministic stand-in for Laya: labels known span texts, everything else is 'not pii'."""

    def __init__(self, answers: dict[str, tuple[str, float]], fail: bool = False, drop_one: bool = False):
        self.answers = answers
        self.fail = fail
        self.drop_one = drop_one
        self.calls = 0
        self.last_states: list[dict] = []

    def score(self, states: list[dict]) -> list[dict[str, float]]:
        self.calls += 1
        self.last_states = states
        if self.fail:
            raise RuntimeError("boom")
        out = []
        for st in states:
            label, p = self.answers.get(st["span"], (cand.NOT_PII, 0.99))
            probs = {lbl: 0.0 for lbl in cand.LABELS}
            probs[label] = p
            rest = 1.0 - p
            if label != cand.NOT_PII:
                probs[cand.NOT_PII] = rest
            else:
                probs["given name"] = rest
            out.append(probs)
        return out[:-1] if self.drop_one else out


@pytest.fixture
def fake_scorer_factory():
    return FakeScorer
