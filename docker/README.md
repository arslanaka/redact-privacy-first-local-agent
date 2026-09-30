# Container setup

Each service owns its Dockerfile next to its code; `docker-compose.yml` at the repository root wires them together.

| Service | Image | Notes |
|---|---|---|
| `postgres` | `postgres:16-alpine` | Data in the `postgres-data` volume. Schema managed by Flyway migrations in `backend/src/main/resources/db/migration`. |
| `ai-service` | `ai-service/Dockerfile` (python:3.12-slim, CPU-only PyTorch) | Downloads the Laya weights on first start into the `model-cache` volume (`HF_HOME=/models/hf`). `/health` returns 503 until the model is loaded; the container healthcheck allows a 10-minute start period. Runs as a non-root user. |
| `backend` | `backend/Dockerfile` (multi-stage: Temurin 21 JDK build, 21 JRE Alpine runtime) | Uploaded and generated files in the `document-data` volume. Healthcheck on `/actuator/health/liveness`. Runs as a non-root user. |
| `frontend` | `frontend/Dockerfile` (Node 22 Alpine, Next.js standalone output) | Built with the **repository root** as context so the synthetic samples can be bundled for one-click demos (`frontend/Dockerfile.dockerignore` keeps the context small). The browser only talks to this service: `src/proxy.ts` checks the session and forwards `/api/*` to `BACKEND_URL` at runtime, so the image has no build-time API URL. |

Useful commands:

```bash
docker compose up --build                 # everything
docker compose logs -f ai-service         # watch the model download/load
docker compose down                       # stop (volumes kept: model cache, database, files)
docker compose down -v                    # stop and delete all data, including the model cache
```

Resource guide: about 3 GB of RAM for the AI service at peak, under 0.5 GB for the backend,
and about 1 GB of disk for the model cache.
