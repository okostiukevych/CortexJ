# CortexJ Landing Page — Design

Date: 2026-09-26
Status: Approved (brainstorm session, visual companion)
Deliverable: product landing page hosted on GitHub Pages at `okostiukevych.github.io/CortexJ`

## Goal

A single-page product site for CortexJ: what the library is for, how it works, and who it is for. English only. Lives in the repo next to the project and auto-deploys on push.

Non-goals (YAGNI): Russian version, analytics, site generator/framework, blog, rendering the existing `docs/` markdown on the site (landing links to GitHub instead), search.

## Decisions

- **Language:** English (matches README, docs, and target audience).
- **Visual style:** Clean Light — light editorial minimalism. Background `#fafaf9`, primary text `#1c1917`, secondary `#78716c`, borders `#e7e5e4`, white cards, single violet accent `#7c6ff0`. System font stack for UI, `ui-monospace` for code. Headings extra-bold with tight tracking, generous whitespace.
- **Hosting:** `site/` directory + GitHub Actions workflow deploying to GitHub Pages (source: workflow). No `gh-pages` branch, nothing placed into the documentation `docs/` folder.
- **No build step:** plain hand-written `index.html` + `styles.css` + inline SVG favicon. No bundler, no Jekyll.

## Page structure (8 sections, in order)

All copy is drawn from `README.md` (single source of truth — do not invent claims).

1. **Nav** — "CortexJ" wordmark; anchor links: Why · How it works · Runtimes · Who it's for; "Docs" → README on GitHub; GitHub button → `okostiukevych/CortexJ`.
2. **Hero** — badge "AI runtime orchestration & model portability for the JVM"; H1 "One JVM API. Any model. The best available runtime."; subline: the platform answers the infrastructure questions itself (where the model lives, which revision, which runtime, whether memory suffices, what the fallback is — and *why* the chosen path was selected); CTAs: Get started → README quick start, GitHub ↗; code block: the `CortexJ.builder()` → `models().load` → `capability(...).generate(...)` snippet from the README.
3. **Why (what it's for)** — heading "Stop hand-wiring model bindings"; 4 cards:
   - Model portability — one API for any compatible model; immutable revisions; local, Hugging Face and plain HTTP repositories.
   - Runtime orchestration — the planner picks the best available runtime: deterministic scoring, hard constraints, fallbacks.
   - Java 8 → 25 — starters for every LTS line; identical business code; wrong-JVM starters fail fast with actionable diagnostics.
   - Explainable decisions — every plan reports why: constraints, scores, memory estimates.
4. **How it works** — horizontal pipeline: Your code → CortexJ planner → Runtime SPI (ServiceLoader) → CPU / GPU / NPU. Under it, a sample plan annotation line (runtime, hardware, memory, reason codes) and three bullets: plan-before-execution, deterministic tie-breaks, fallback. Mentions `cortexj plan` for CLI users.
5. **Runtimes & integrations** — grouped chips. Runtimes: ONNX Runtime, llama.cpp (managed llama-server bridge), OpenAI-compatible remote (vLLM, gateways), DJL. Adapters & tooling: Spring AI, LangChain4j, OpenTelemetry, CLI (doctor/plan/run), lockfiles & air-gap bundles, converters (GGUF F32↔F16, Safetensors→GGUF), declarative `@ModelPrompt` API, TCK for provider authors.
6. **Who it's for** — 4 persona cards: Java teams adding AI (local models, no Python, one Maven dependency); enterprise & air-gapped deployments (lockfiles, verified bundles, offline re-resolution); legacy Java 8/11 applications (same code as Java 25); runtime/repository provider authors (public SPI + TCK).
7. **Quick start** — Maven `cortexj-bom` + `cortexj-starter-java21` snippet from README; note that starters exist for every LTS line; links to `docs/getting-started-java*.md` on GitHub.
8. **Footer** — "CortexJ — Apache 2.0" (per LICENSE); links: GitHub repo, `docs/` on GitHub, README. Small note: version `0.1.0-SNAPSHOT`, pre-1.0 status.

## Accessibility & polish

- Accent `#7c6ff0` for buttons/badges/decoration; darker `#5d4fd4` for small accent text (AA contrast on `#fafaf9`).
- Semantic landmarks (`header`, `nav`, `main`, `section` with `id`s, `footer`), one `h1`, logical heading order.
- Responsive: 2-column card grids collapse to one column on mobile; nav collapses to a hamburger via a few lines of vanilla JS.
- OG/Twitter meta with description; `favicon.svg`.

## Files

- `site/index.html` — the whole page: meta tags, all eight sections, footer.
- `site/styles.css` — all styles, one file.
- `site/favicon.svg` — rounded violet square, white "C".
- `.github/workflows/pages.yml` — deploy workflow:
  - Triggers: push to `main` with `paths: ['site/**']` + `workflow_dispatch`.
  - Permissions: `contents: read`, `pages: write`, `id-token: write`; concurrency group `pages`.
  - Steps: `actions/checkout@v4` → `actions/configure-pages@v5` → `actions/upload-pages-artifact@v3` (path `site`) → `actions/deploy-pages@v4`. Matches `ci.yml` conventions.
- `.gitignore` — add `.superpowers/` (brainstorm session files).

## Deployment & enabling Pages

1. Push branch; Pages must be enabled with source "GitHub Actions": `POST /repos/okostiukevych/CortexJ/pages` with `{"build_type":"workflow"}` (fall back to `PATCH` if the page already exists), using the credential already configured in the git remote. Never print the credential.
2. After the workflow runs, the site is live at `https://okostiukevych.github.io/CortexJ/`.

## Verification

- Local: render `site/index.html` in a browser (serve via `python3 -m http.server` from `site/`) before pushing; check desktop + mobile widths.
- After push: Pages workflow green on GitHub; live URL returns 200; all links resolve (repo, README anchors, `docs/*.md`); anchor navigation works.
- No claims on the page that the README does not make (accuracy pass against README before commit).
