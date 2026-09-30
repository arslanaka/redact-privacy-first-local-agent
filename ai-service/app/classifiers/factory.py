from __future__ import annotations

import threading

from app.classifiers.base import PiiClassifier
from app.classifiers.laya_classifier import LayaPiiClassifier
from app.classifiers.mock_classifier import MockPiiClassifier
from app.config import Settings


def create_classifier(settings: Settings, *, load_in_background: bool = True) -> PiiClassifier:
    if settings.pii_classifier == "mock":
        return MockPiiClassifier()
    classifier = LayaPiiClassifier(
        repo=settings.laya_repo,
        subfolder=settings.laya_subfolder,
        device=settings.laya_device,
        batch_size=settings.laya_batch_size,
        torch_threads=settings.laya_torch_threads,
        warmup=settings.laya_warmup,
        threshold=settings.laya_threshold,
        region=settings.laya_region,
    )
    if load_in_background:
        # The first start downloads ~850 MB; the API answers /health with "loading" meanwhile.
        threading.Thread(target=classifier.load, name="laya-loader", daemon=True).start()
    else:
        classifier.load()
    return classifier
