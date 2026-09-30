# Frontend

Next.js 16 (App Router) + TypeScript + Tailwind CSS 4 + shadcn/ui (Base UI primitives).

```bash
npm ci
npm run dev        # http://localhost:3000, forwards /api/* to BACKEND_URL (default http://localhost:8080); no sign-in unless APP_USERS is set
npm test           # Vitest + Testing Library
npm run lint && npm run typecheck
```

`predev` / `prebuild` copy the synthetic documents from `../samples` into `public/samples` for the
one-click demo buttons.

| Route | Screen |
|---|---|
| `/` | Upload |
| `/documents/[id]/processing` | Live analysis stages |
| `/documents/[id]` | Results: risk, summary cards, detection table, agent tool trace |
| `/documents/[id]/review` | Split-screen human review (keyboard: A / R / N / P) |
| `/documents/[id]/output` | Original vs redacted, verification, download |
| `/documents/[id]/audit` | Audit trail |

Pure logic (confidence bands, processing stages, text segmentation, DOM-selection offsets) lives in
`src/lib` and is unit-tested; the API client maps backend error codes to user-facing messages.
