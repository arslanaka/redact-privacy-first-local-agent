import pytest
from fastapi.testclient import TestClient

from app.classifiers.laya_classifier import LayaPiiClassifier
from app.classifiers.mock_classifier import MockPiiClassifier
from app.config import Settings
from app.main import create_app

SETTINGS = Settings(pii_classifier="mock", max_text_chars=1000)


@pytest.fixture
def client():
    with TestClient(create_app(SETTINGS, MockPiiClassifier())) as c:
        yield c


def test_detect_output_schema(client):
    text = "Contact Anna Keller at anna.keller@example.com"
    r = client.post("/v1/pii/detect", json={"text": text}, headers={"X-Request-ID": "abc"})
    assert r.status_code == 200
    assert r.headers["X-Request-ID"] == "abc"
    body = r.json()
    assert set(body) == {"model", "isMock", "entities", "candidatesScored", "processingTimeMs"}
    assert body["model"] == "mock" and body["isMock"] is True
    for e in body["entities"]:
        assert set(e) == {"text", "type", "confidence", "start", "end", "modelLabels"}
        assert text[e["start"]:e["end"]] == e["text"]
        assert 0 <= e["confidence"] <= 1
    assert {e["type"] for e in body["entities"]} == {"PERSON", "EMAIL"}


def test_health_and_model_info_with_mock(client):
    assert client.get("/health").json() == {"status": "ready", "model": "mock", "isMock": True, "detail": None,
                                            "spansPerSecond": None, "cpuThreads": None, "maxTextChars": 1000}
    info = client.get("/model-info").json()
    assert info["isMock"] is True and info["ready"] is True


def test_text_too_large(client):
    r = client.post("/v1/pii/detect", json={"text": "a" * 1001})
    assert r.status_code == 422 and r.json()["error"] == "TEXT_TOO_LARGE"


def test_missing_text_is_rejected(client):
    assert client.post("/v1/pii/detect", json={}).status_code == 422


def test_model_not_loaded_returns_503():
    loading = LayaPiiClassifier(repo="r", subfolder="pii")
    with TestClient(create_app(SETTINGS, loading)) as c:
        assert c.get("/health").status_code == 503
        assert c.get("/health").json()["status"] == "loading"
        r = c.post("/v1/pii/detect", json={"text": "hello"})
        assert r.status_code == 503
        assert r.json()["error"] == "MODEL_UNAVAILABLE"
        assert c.get("/model-info").json()["ready"] is False


def test_inference_failure_returns_structured_500(fake_scorer_factory):
    broken = LayaPiiClassifier(repo="r", subfolder="pii", scorer=fake_scorer_factory({}, fail=True))
    with TestClient(create_app(SETTINGS, broken)) as c:
        r = c.post("/v1/pii/detect", json={"text": "Hello Anna"})
        assert r.status_code == 500 and r.json()["error"] == "INFERENCE_FAILED"
