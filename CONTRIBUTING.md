# Contributing

Thanks for your interest. This is a small research project about local PII detection with a human in the
loop. Issues and pull requests are welcome, especially bug reports, evaluation cases and detector
improvements.

## Ground rules

- **Synthetic data only.** Never put real personal data in issues, pull requests, tests, samples or
  screenshots. Use reserved values: `example.com` emails, published test card numbers, example IBANs,
  invented names. If you report a missed detection, rewrite the text with invented values that trigger the
  same behaviour.
- **No document text leaves the machine.** Changes must not send document content to external services
  (LLM APIs, telemetry, error trackers). The one allowed outbound request is the model-weight download.
- **Humans decide.** Nothing may be redacted without an explicit review decision. Keep the audit trail free
  of raw values (use `AuditService.mask`).
- **Honest numbers.** If a change affects detection, run the evaluation and include the before/after table
  in the pull request.

## Development setup

Requirements: Docker (6 GB+ memory for the real model), or, for component work, Java 21, Python 3.12 with
[uv](https://docs.astral.sh/uv/) and Node 22.

```bash
cp .env.example .env
docker compose up --build            # http://localhost:3000 (sign-in is off locally)
```

To work without downloading the model, use the clearly labelled mock classifier:

```bash
PII_CLASSIFIER=mock docker compose up --build
```

## Before opening a pull request

Run the checks for the parts you touched:

```bash
cd backend && ./mvnw test                                   # JUnit 5 + Testcontainers (needs Docker)
```

```bash
cd ai-service && uv sync --group dev && uv run pytest
```

```bash
cd frontend && npm ci && npm test && npm run lint && npm run typecheck
```

```bash
python3 -m unittest discover -s evaluation -p "test_*.py"
```

If you changed detection, also run the benchmark against a running stack and paste the results table:

```bash
docker compose up -d && python3 evaluation/run_evaluation.py
```

## Pull request checklist

- [ ] Focused change with a clear description of *why*
- [ ] Tests added or updated; all checks above pass
- [ ] No real personal data anywhere in the diff
- [ ] Docs updated (README, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`) if behaviour or design changed
- [ ] Evaluation table included if detection changed

## Style

Follow the conventions of the surrounding code: Java with Lombok and records, Python with type hints,
TypeScript with the existing shadcn/ui components. British English in user-facing text ("analyse",
"anonymisation").

## Licence

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
