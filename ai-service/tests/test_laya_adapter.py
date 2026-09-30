import pytest

from app.classifiers import laya_candidates as cand
from app.classifiers.base import InferenceError, ModelNotReadyError, PiiType
from app.classifiers.laya_classifier import LayaPiiClassifier

TEXT = "Hi, I'm Priya Nair. Call me on +44 7700 900123 or email priya.nair@example.com."


def make(scorer, **kw):
    return LayaPiiClassifier(repo="r", subfolder="pii", scorer=scorer, **kw)


def test_states_follow_training_layout(fake_scorer_factory):
    scorer = fake_scorer_factory({})
    make(scorer, region="GB").detect(TEXT)
    st = scorer.last_states[0]
    assert list(st) == ["document_type", "language", "region", "left", "span", "right"]
    assert st["region"] == "GB" and st["language"] == "English"
    assert all(len(s["left"]) <= cand.CONTEXT_CHARS and len(s["right"]) <= cand.CONTEXT_CHARS
               for s in scorer.last_states)


def test_given_name_and_surname_merge_into_one_person(fake_scorer_factory):
    scorer = fake_scorer_factory({
        "Priya": ("given name", 0.97), "Nair": ("surname", 0.90),
        "I'm Priya": ("given name", 0.80),  # nested, less confident -> dropped
        "+44 7700 900123": ("phone number", 0.99),
        "priya.nair@example.com": ("email address", 0.99),
    })
    ents = make(scorer).detect(TEXT).entities
    by_type = {e.type: e for e in ents}
    person = by_type[PiiType.PERSON]
    assert person.text == "Priya Nair"
    assert person.model_labels == ("given name", "surname")
    assert person.confidence == pytest.approx(0.90)  # min of the parts' P(pii)
    assert TEXT[person.start:person.end] == "Priya Nair"
    assert by_type[PiiType.PHONE].text == "+44 7700 900123"
    assert by_type[PiiType.EMAIL].text == "priya.nair@example.com"


def test_entities_never_overlap_and_offsets_match_text(fake_scorer_factory):
    answers = {s: ("phone number", 0.95) for s in ("+44 7700 900123", "44", "7700", "900123")}
    ents = make(fake_scorer_factory(answers)).detect(TEXT).entities
    spans = sorted((e.start, e.end) for e in ents)
    assert all(a[1] <= b[0] for a, b in zip(spans, spans[1:]))
    assert all(TEXT[e.start:e.end] == e.text for e in ents)


def test_threshold_filters_low_pii_probability(fake_scorer_factory):
    scorer = fake_scorer_factory({"Priya": ("given name", 0.4)})
    assert make(scorer, threshold=0.5).detect(TEXT).entities == []
    assert len(make(scorer, threshold=0.3).detect(TEXT).entities) == 1


def test_standalone_title_is_dropped_but_kept_inside_a_name(fake_scorer_factory):
    text = "Dr signed. Later Dr Oliver Bennett called."
    scorer = fake_scorer_factory({"Dr": ("title", 0.95), "Oliver": ("given name", 0.95),
                                  "Bennett": ("surname", 0.95)})
    ents = make(scorer).detect(text).entities
    assert [e.text for e in ents] == ["Dr Oliver Bennett"]


def test_city_joins_address_but_standalone_city_is_location(fake_scorer_factory):
    text = "Ship to 12 Harrow Road, London. Our team met in Berlin."
    scorer = fake_scorer_factory({"12": ("building number", 0.95), "Harrow Road": ("street", 0.95),
                                  "London": ("city", 0.95), "Berlin": ("city", 0.9)})
    ents = make(scorer).detect(text).entities
    assert [(e.text, e.type) for e in ents] == [("12 Harrow Road, London", PiiType.ADDRESS),
                                                ("Berlin", PiiType.LOCATION)]


def test_not_loaded_raises_model_not_ready():
    clf = LayaPiiClassifier(repo="r", subfolder="pii")
    assert not clf.is_ready()
    with pytest.raises(ModelNotReadyError):
        clf.detect("text")


def test_scorer_failure_becomes_inference_error(fake_scorer_factory):
    with pytest.raises(InferenceError):
        make(fake_scorer_factory({}, fail=True)).detect(TEXT)


def test_result_count_mismatch_is_an_error(fake_scorer_factory):
    with pytest.raises(InferenceError):
        make(fake_scorer_factory({}, drop_one=True)).detect(TEXT)


def test_empty_text(fake_scorer_factory):
    res = make(fake_scorer_factory({})).detect("")
    assert res.entities == [] and res.candidates_scored == 0


def test_info_is_not_mock(fake_scorer_factory):
    info = make(fake_scorer_factory({})).info()
    assert info.is_mock is False and info.name == "laya"
    assert "not pii" in info.labels and "IBAN" not in info.supported_types


def test_capitalised_runs_across_sentence_or_line_breaks_are_not_entities(fake_scorer_factory):
    text = "Jordan works in Berlin.\nWe met Dr. Oliver Bennett. Then lunch."
    scorer = fake_scorer_factory({
        "Berlin. We": ("city", 0.99),  # the real Laya output that motivated the guard
        "Berlin": ("city", 0.90),
        "Dr. Oliver Bennett": ("given name", 0.99),
        "Bennett. Then": ("surname", 0.99),
    })
    ents = make(scorer).detect(text).entities
    texts = [e.text for e in ents]
    assert "Berlin" in texts
    assert "Dr. Oliver Bennett" in texts  # title period is not a sentence end
    assert not any("\n" in t or t.endswith("Then") for t in texts)


def test_crosses_boundary_only_applies_to_caps_runs():
    from app.classifiers.laya_classifier import crosses_boundary
    assert crosses_boundary("Berlin.\nWe", ["CAPS_RUN"])
    assert crosses_boundary("Smith. Then", ["CAPS_RUN"])
    assert not crosses_boundary("Dr. Smith", ["CAPS_RUN"])
    assert not crosses_boundary("12 Harrow Road", ["CAPS_RUN"])
    assert not crosses_boundary("a.b@x.org\nfoo", ["EMAIL"])


def test_warm_up_scores_a_synthetic_text_and_logs_throughput(caplog):
    class CountingScorer:
        calls: list[int] = []

        def score(self, states):
            self.calls.append(len(states))
            return [{} for _ in states]

    scorer = CountingScorer()
    with caplog.at_level("INFO"):
        LayaPiiClassifier._warm_up(scorer)
    assert scorer.calls and scorer.calls[0] > 5
    assert "spans/s" in caplog.text


def test_warm_up_failure_does_not_raise(caplog):
    class BrokenScorer:
        def score(self, states):
            raise RuntimeError("boom")

    LayaPiiClassifier._warm_up(BrokenScorer())
    assert "warm-up failed" in caplog.text
