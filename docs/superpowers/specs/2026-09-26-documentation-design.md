# CortexJ Documentation — Design

Date: 2026-09-26
Status: Approved (brainstorm continuation)
Deliverable: public rendered documentation at `https://okostiukevych.github.io/CortexJ-site/docs/`

## Goal

User-facing documentation for the CortexJ library "in the best traditions of the best frameworks": structured per Diátaxis (tutorials → concepts → how-to → reference), searchable, navigable, English-only, publicly readable on the existing product site. The API surface is public by nature (any dependent developer sees it); the implementation stays private (plan C).

## Decisions

- **Location:** public `CortexJ-site` repo — documentation is part of the public face of the product. The private CortexJ repo's internal `docs/` notes remain untouched (they are engineering notes, not user docs).
- **Engine:** MkDocs + Material for MkDocs (the FastAPI/Pydantic standard): nav sidebar, full-text search, dark mode, code copy buttons. Built by the existing `pages.yml` workflow on every push.
- **Structure:** Diátaxis — docs home, installation, quickstart, concepts, CLI reference, integrations, production, declarative/converters, provider SPI, reference.
- **Language:** English (consistent with the landing and JVM audience).
- **URL layout:** landing stays at `https://okostiukevych.github.io/CortexJ-site/`; docs live under `…/CortexJ-site/docs/…`.
- **Accuracy rule:** every claim must be verifiable from the private repo's `README.md`, existing `docs/*.md` notes, or the technical spec. Never invent API names, flags, env vars, or error codes. Where only one error code is verified (`CORTEXJ-CONF-0002`), it is mentioned in context instead of a fabricated error-code index.

## Repo layout (CortexJ-site)

```text
CortexJ-site/
├── index.html, styles.css, favicon.svg   (landing — unchanged except nav link)
├── mkdocs.yml                            (new)
├── site-docs/
│   └── docs/                             (mkdocs docs_dir: site-docs; the docs/ level
│       ├── index.md                      → /docs/            (docs home)
│       ├── installation.md               → /docs/installation/
│       ├── quickstart.md                 → /docs/quickstart/
│       ├── concepts/
│       │   ├── models.md                 (references & immutable revisions)
│       │   ├── planner.md                (candidates, constraints, scoring, explanations, fallback)
│       │   ├── runtimes.md               (ONNX, llama.cpp, OpenAI-remote, DJL)
│       │   ├── hardware.md               (discovery, memory estimation)
│       │   ├── capabilities.md           (text generation, embeddings, streaming)
│       │   └── configuration.md          (builder > system properties > env; offline; CORTEXJ-CONF-0002)
│       ├── cli.md                        (doctor, runtimes, hardware, inspect, plan, pull, run, server, convert, cache, bundle)
│       ├── integrations.md               (Spring AI, LangChain4j, OpenTelemetry)
│       ├── production.md                 (lockfiles, air-gap bundles, offline deployment, metrics)
│       ├── declarative.md                (@ModelPrompt + JsonPromptDecoder)
│       ├── converters.md                 (GGUF F32↔F16, Safetensors→GGUF)
│       ├── providers.md                   (runtime SPI, repository SPI, planner policies, TCK)
│       └── reference/
│           ├── configuration-properties.md (cortexj.* / CORTEXJ_* table)
│           └── api-stability.md             (Public / Stable SPI / Experimental / Internal tiers)
└── .github/workflows/pages.yml           (modified: build docs, overlay landing, deploy)
```

The `docs/` level inside `site-docs/` exists so MkDocs output lands under the `/docs/` URL prefix.

## mkdocs.yml (key settings)

- `site_name: CortexJ Documentation`, `site_url: https://okostiukevych.github.io/CortexJ-site/` (correct subpath handling for relative asset links), `repo_url` pointing to the public site repo.
- Theme `material`, palette: light default + dark toggle, brand violet `#7c6ff0` via `extra_css` override, `content.code.copy`, `navigation.sections`, `search.highlight`, `toc.permalink`.
- Markdown extensions: admonition, pymdownx.superfences, pymdownx.highlight (code highlighting), pymdownx.details, tables, toc.
- Explicit `nav` (ordered as in the layout above) — no implicit ordering.

## Workflow change (pages.yml)

1. `actions/setup-python@v5` → `pip install mkdocs-material`
2. `mkdocs build --strict` with `site_dir: _site`
3. Copy landing files (`index.html`, `styles.css`, `favicon.svg`) into `_site/` root — landing keeps `/`, docs get `/docs/…`
4. Upload `_site` artifact → `deploy-pages` (rest unchanged)

`--strict` turns broken links/warnings into build failures, so the docs cannot silently degrade.

## Landing change

- Nav gains a `Docs` link (`/docs/`) between "Who it's for" and the Get started CTA; footer meta gains the docs URL. No other landing changes.

## Content sources

- `README.md` (private repo) — canonical product claims, snippets (builder/load/generate, BOM+starter, CLI list, milestones).
- Existing `docs/*.md` (private) — technical specifics: env vars, runtime flags, extension APIs (writing-*-provider/planner-policy), spring-ai/langchain4j wiring, offline/enterprise notes, api-stability.
- `cortexj-technical-spec-v2.0.md` — concept phrasing (translated to English; never referenced as a spec document in public docs).
- The already-public landing — wording may be reused for the docs home.

## Verification

- Local: `mkdocs build --strict` clean; `mkdocs serve` visual check of nav, search, dark mode, code copy; landing still renders at `/` of the build output.
- After push: Pages workflow green; `/docs/` returns 200; nav and search work on the live URL; landing unchanged except the Docs link; spot-check 3 pages.
- Accuracy pass: every code sample and flag re-checked against the private repo sources before commit.

## Out of scope (YAGNI)

- Javadoc hosting (source is private; ship with the 1.0 release).
- Multi-version docs (single `latest` until releases exist).
- Russian version, blog, search analytics.
- Generated API reference — the reference section covers configuration properties and stability tiers only.

## Commit plan

- Site repo: feature commit(s) for mkdocs config + workflow + landing nav link + docs content (user reviews spec before content is written; content lands as its own commit series).
- Private repo: spec + implementation plan committed as before (docs/superpowers/).
