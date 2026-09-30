from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", env_ignore_empty=True)

    pii_classifier: Literal["laya", "mock"] = "laya"
    laya_repo: str = "goku-san/laya-experts"
    laya_subfolder: str = "pii"
    laya_device: str | None = None  # None = auto (cuda / mps / cpu)
    laya_batch_size: int = 16
    # CPU threads for torch. None = torch's default, which in a container is often the *host's* core count and
    # oversubscribes the CPU quota (seen on Railway: ~1.2 s per span). Set it to the vCPUs you actually get.
    laya_torch_threads: int | None = None
    laya_warmup: bool = True  # score a small synthetic text after loading: logs throughput, speeds up the first request
    laya_threshold: float = 0.5
    laya_region: str = "US"  # US, GB, CA, IN or SG, as supported by the model
    max_text_chars: int = 200_000
    log_level: str = "INFO"


@lru_cache
def get_settings() -> Settings:
    return Settings()
