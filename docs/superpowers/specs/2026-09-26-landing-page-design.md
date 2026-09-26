# CortexJ Landing Page — Design

Date: 2026-09-26
Status: Approved (brainstorm session, visual companion). Revised same day after the privacy decision (plan C below).
Deliverable: product landing page hosted on GitHub Pages at `okostiukevych.github.io/CortexJ-site/`

## Goal

A single-page product site for CortexJ: what the library is for, how it works, and who it is for. English only.

**Plan C (privacy decision):** the CortexJ code repository stays **private** — it is never opened as part of this work. The landing ships as a separate **public** repository `okostiukevych/CortexJ-site` containing only the page (HTML/CSS, no product code). The landing is self-contained: no links to the private repo or its files.

Non-goals (YAGNI): Russian version, analytics, site generator/framework, blog, rendering the existing `docs/` markdown on the site (the private repo's docs stay private; deep documentation ships with a future release), search.

## Decisions

- **Language:** English (matches README, docs, and target audience).
- **Visual style:** Clean Light — light editorial minimalism. Background `#fafaf9`, primary text `#1c1917`, secondary `#78716c`, borders `#e7e5e4`, white cards, single violet accent `#7c6ff0`. Typography: Bricolage Grotesque (headings), Public Sans (body), JetBrains Mono (code) via Google Fonts; hand-colored syntax tokens in dark code cards. Headings extra-bold with tight tracking, generous whitespace.
- **Hosting:** separate public repository `okostiukevych/CortexJ-site` (created via GitHub API) + GitHub Actions workflow deploying its content to GitHub Pages (source: workflow). Nothing is placed into the private CortexJ repository or its `docs/` folder. No `gh-pages` branch.
- **No build step:** plain hand-written `index.html` + `styles.css` + inline SVG favicon. No bundler, no Jekyll.

## Page structure (8 sections, in order)

All copy is drawn from `README.md` (single source of truth — do not invent claims).

1. **Nav** — "CortexJ" wordmark; anchor links: Why · How it works · Runtimes · Who it's for; CTA "Get started" → `#quick-start` anchor. No GitHub/docs links (product repo is private).
2. **Hero** — badge "AI runtime orchestration & model portability for the JVM"; H1 "One JVM API. Any model. The best available runtime."; subline: the platform answers the infrastructure questions itself (where the model lives, which revision, which runtime, whether memory suffices, what the fallback is — and *why* the chosen path was selected); CTAs: Get started → `#quick-start`, How it works → `#how`; code block: the `CortexJ.builder()` → `models().load` → `capability(...).generate(...)` snippet from the README.
3. **Why (what it's for)** — heading "Stop hand-wiring model bindings"; 4 cards:
   - Model portability — one API for any compatible model; immutable revisions; local, Hugging Face and plain HTTP repositories.
   - Runtime orchestration — the planner picks the best available runtime: deterministic scoring, hard constraints, fallbacks.
   - Java 8 → 25 — starters for every LTS line; identical business code; wrong-JVM starters fail fast with actionable diagnostics.
   - Explainable decisions — every plan reports why: constraints, scores, memory estimates.
4. **How it works** — horizontal pipeline: Your code → CortexJ planner → Runtime SPI (ServiceLoader) → CPU / GPU / NPU. Under it, a sample plan annotation line (runtime, hardware, memory, reason codes) and three bullets: plan-before-execution, deterministic tie-breaks, fallback. Mentions `cortexj plan` for CLI users.
5. **Runtimes & integrations** — grouped chips. Runtimes: ONNX Runtime, llama.cpp (managed llama-server bridge), OpenAI-compatible remote (vLLM, gateways), DJL. Adapters & tooling: Spring AI, LangChain4j, OpenTelemetry, CLI (doctor/plan/run), lockfiles & air-gap bundles, converters (GGUF F32↔F16, Safetensors→GGUF), declarative `@ModelPrompt` API, TCK for provider authors.
6. **Who it's for** — 4 persona cards: Java teams adding AI (local models, no Python, one Maven dependency); enterprise & air-gapped deployments (lockfiles, verified bundles, offline re-resolution); legacy Java 8/11 applications (same code as Java 25); runtime/repository provider authors (public SPI + TCK).
7. **Quick start** — Maven `cortexj-bom` + `cortexj-starter-java21` snippet from README; chips listing all five starters (java8/11/17/21/25); note that a starter/JVM mismatch fails fast with an actionable diagnostic; pre-release status pill "0.1.0-SNAPSHOT · Maven Central release coming".
8. **Footer** — "CortexJ — AI runtime orchestration for the JVM"; "© 2026 CortexJ · Apache License 2.0"; site URL. No links to the private repo.

## Accessibility & polish

- Accent `#7c6ff0` for buttons/badges/decoration; darker `#5d4fd4` for small accent text (AA contrast on `#fafaf9`).
- Semantic landmarks (`header`, `nav`, `main`, `section` with `id`s, `footer`), one `h1`, logical heading order.
- Responsive: 2-column card grids collapse to one column on mobile; nav collapses to a hamburger (CSS-only checkbox toggle, no JS).
- OG/Twitter meta with description; `favicon.svg`.

## Files

All site files live at the root of the new public repository `CortexJ-site` (local path: `/Users/oleg/NikitaProjects/CortexJ-site`):

- `index.html` — the whole page: meta tags, all eight sections, footer.
- `styles.css` — all styles, one file.
- `favicon.svg` — rounded violet square, white "C".
- `README.md` — one-liner for the site repo with the live URL.
- `.github/workflows/pages.yml` — deploy workflow:
  - Triggers: push to `main` + `workflow_dispatch`.
  - Permissions: `contents: read`, `pages: write`, `id-token: write`; concurrency group `pages`.
  - Steps: `actions/checkout@v4` → `actions/configure-pages@v5` → `actions/upload-pages-artifact@v3` (path `.`) → `actions/deploy-pages@v4`. Matches `ci.yml` conventions.

In the private CortexJ repository:

- `.gitignore` — add `.superpowers/` (brainstorm session files).

## Deployment & enabling Pages

1. Create the public repo via API: `POST /user/repos` with `{"name":"CortexJ-site","private":false,"description":...}`; verify `"private": false` in the response. Use the credential already configured in the git remote of the private repo; never print it.
2. Enable Pages with source "GitHub Actions": `POST /repos/okostiukevych/CortexJ-site/pages` with `{"build_type":"workflow"}` (fall back to `PATCH` if the page already exists).
3. Push the site repo (clean remote URL; if credentials are not cached, one authenticated push with the token in the URL, never stored in the remote config).
4. After the workflow runs, the site is live at `https://okostiukevych.github.io/CortexJ-site/`.

## Verification

- Local: render the page in a browser (serve via `python3 -m http.server` from the site directory) before pushing; check desktop + mobile widths.
- After push: Pages workflow green on GitHub (checked via API); live URL returns 200; anchor navigation works; no dead links (the page is self-contained — every link is an anchor).
- No claims on the page that the README does not make (accuracy pass against README before commit).

## Revision 2 — "big framework" landing upgrade (2026-09-26, approved)

Selected by the user: docs integration, copy buttons + LTS tabs, status section, FAQ, SEO (JSON-LD + og:image), comparison table. Dark theme explicitly deferred.

- **Docs integration:** hero CTA "Get started" → `/docs/quickstart/`; quick-start section gains a 4-card link row (Installation, Quickstart, Concepts, CLI); footer rebuilt as a sitemap (brand block + Docs column + Project column).
- **Copy buttons** on both code cards (vanilla JS, clipboard API, transient "Copied" state).
- **LTS tabs** in Quick start: Java 8/11/17/21/25 switch the `cortexj-starter-*` artifactId inside the pom snippet (data-attribute swap; Java 21 default).
- **Status section** ("What ships today") between Who it's for and Quick start: 4 stat cards (177 tests/41 suites on JDK 25; 164 tests on JDK 8; 15/15 CI lanes ubuntu/macos/windows; 5 LTS starters) + 6 milestone cards (M1, M4, M3, M5, M7, M9 — wording from README Status). The "0.1.0-SNAPSHOT · Maven Central release coming" pill moves here from Quick start.
- **FAQ** section after Quick start: 5 native `<details>` accordions (Java versions; Python needed?; GPU honesty; Spring AI/LangChain4j positioning — frameworks vs runtime layer, adapters export CortexJ models as their models; Maven Central timing — publishing + API stability freeze + security review sign-off per README).
- **Comparison table** in Why: Hand-rolled bindings vs CortexJ across 5 rows (model source, runtime choice, memory, Java versions, reproducibility). Generic pain wording, no named competitors.
- **SEO:** JSON-LD `SoftwareApplication`; og:image — branded 1200×630 SVG rasterized via macOS `qlmanage`; if rasterization fails, og:image is deferred and JSON-LD alone ships.
- New page order: nav → hero → why(+compare) → how → runtimes → who → status → quick start(+tabs+links) → faq → footer v2.
