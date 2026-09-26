# CortexJ Site (Landing Page) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the approved CortexJ landing page as a new public repository `okostiukevych/CortexJ-site`, deployed to GitHub Pages at `https://okostiukevych.github.io/CortexJ-site/`, while the private CortexJ code repository stays private and untouched (except `.gitignore` housekeeping).

**Architecture:** A no-build static one-pager (`index.html` + `styles.css` + `favicon.svg`) living at the root of the new public repo. A `pages.yml` workflow deploys the whole repo content to GitHub Pages on every push to `main`. All product claims are drawn from the private repo's `README.md` (single source of truth — do not invent claims). The page is self-contained: every link is an anchor; there are no links to the private repo.

**Tech Stack:** Plain HTML/CSS (no framework, CSS-only nav toggle), Google Fonts (Bricolage Grotesque / Public Sans / JetBrains Mono), GitHub Actions Pages deploy, GitHub REST API for repo creation and Pages enablement.

**Context for the executor:**
- Private repo (source of truth for copy): `/Users/oleg/NikitaProjects/CortexJ` (its `README.md` is referenced for the accuracy pass).
- New public repo local path: `/Users/oleg/NikitaProjects/CortexJ-site` (does not exist yet — created in Task 1).
- Design spec: `/Users/oleg/NikitaProjects/CortexJ/docs/superpowers/specs/2026-09-26-landing-page-design.md`.
- GitHub credential: already embedded in the private repo's `origin` remote URL. Extract it into a shell variable only when a step needs it, and never print/echo it or write it into any file or remote URL permanently.
- The user wants a **single commit** in the site repo (Task 7) — no intermediate commits.

---

### Task 1: Create the public `CortexJ-site` repo + enable Pages + local init

**Files:**
- Create: `/Users/oleg/NikitaProjects/CortexJ-site/` (git repo, no files yet)

- [ ] **Step 1: Create the public repo via API**

```bash
TOKEN=$(git -C /Users/oleg/NikitaProjects/CortexJ remote get-url origin | sed -E 's|https://[^:]+:([^@]+)@.*|\1|')
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/user/repos \
  -d '{"name":"CortexJ-site","private":false,"description":"CortexJ — AI runtime orchestration and model portability platform for the JVM (product site)","has_wiki":false,"has_projects":false}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('created:', d.get('full_name'), '| private:', d.get('private'), '| error:', d.get('message'))"
```

Expected output: `created: okostiukevych/CortexJ-site | private: False | error: None`

- [ ] **Step 2: Enable Pages with the workflow source**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/okostiukevych/CortexJ-site/pages \
  -d '{"build_type":"workflow"}'
```

Expected: `201`. If `409` (page already exists), run the same call with `-X PATCH` instead → expected `200`.

- [ ] **Step 3: Initialize the local repo**

```bash
mkdir -p /Users/oleg/NikitaProjects/CortexJ-site
cd /Users/oleg/NikitaProjects/CortexJ-site
git init -b main
git remote add origin https://github.com/okostiukevych/CortexJ-site.git
```

Note: the remote URL is intentionally clean (no credential embedded).

- [ ] **Step 4: Verify the setup**

```bash
git -C /Users/oleg/NikitaProjects/CortexJ-site remote -v && git -C /Users/oleg/NikitaProjects/CortexJ-site status --short --branch
```

Expected: remote origin → `https://github.com/okostiukevych/CortexJ-site.git` (fetch/push), branch `## No commits yet on main`.

---

### Task 2: Small files — `favicon.svg` and `README.md`

**Files:**
- Create: `/Users/oleg/NikitaProjects/CortexJ-site/favicon.svg`
- Create: `/Users/oleg/NikitaProjects/CortexJ-site/README.md`

- [ ] **Step 1: Write `favicon.svg`**

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <rect width="64" height="64" rx="14" fill="#7c6ff0"/>
  <text x="32" y="44" font-family="-apple-system, 'Segoe UI', sans-serif" font-size="38" font-weight="700" fill="#ffffff" text-anchor="middle">C</text>
</svg>
```

- [ ] **Step 2: Write `README.md`**

```markdown
# CortexJ-site

Product landing page for **CortexJ** — AI runtime orchestration and model portability platform for the JVM.

Live: https://okostiukevych.github.io/CortexJ-site/
```

---

### Task 3: `styles.css` — the complete stylesheet (Clean Light)

**Files:**
- Create: `/Users/oleg/NikitaProjects/CortexJ-site/styles.css`

- [ ] **Step 1: Write `styles.css` with this exact content**

```css
:root {
  --bg: #fafaf9;
  --surface: #ffffff;
  --ink: #1c1917;
  --muted: #78716c;
  --faint: #a8a29e;
  --line: #e7e5e4;
  --accent: #7c6ff0;
  --accent-deep: #5d4fd4;
  --accent-soft: #f3f1fe;
  --code-bg: #1c1917;
  --code-ink: #e7e5e4;
  --shadow: 0 1px 2px rgba(28, 25, 23, 0.04), 0 8px 24px -12px rgba(28, 25, 23, 0.12);
}

*, *::before, *::after { box-sizing: border-box; }

html { scroll-behavior: smooth; }

body {
  margin: 0;
  font-family: "Public Sans", -apple-system, "Segoe UI", sans-serif;
  font-size: 16px;
  line-height: 1.6;
  color: var(--ink);
  background: var(--bg);
  -webkit-font-smoothing: antialiased;
}

::selection { background: #e6e2fc; }

a { color: var(--accent-deep); }

:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

h1, h2, h3 {
  font-family: "Bricolage Grotesque", "Public Sans", sans-serif;
  line-height: 1.1;
  margin: 0;
}

code, pre { font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace; }

.container { max-width: 1080px; margin-inline: auto; padding-inline: 24px; }

.btn-primary {
  display: inline-block;
  background: var(--accent);
  color: #fff;
  font-weight: 600;
  font-size: 15px;
  text-decoration: none;
  padding: 12px 22px;
  border-radius: 10px;
  border: 1px solid transparent;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
  box-shadow: 0 8px 20px -8px rgba(124, 111, 240, 0.55);
}

.btn-primary:hover { transform: translateY(-1px); box-shadow: 0 12px 26px -8px rgba(124, 111, 240, 0.65); }

.btn-ghost {
  display: inline-block;
  color: var(--ink);
  font-weight: 600;
  font-size: 15px;
  text-decoration: none;
  padding: 12px 22px;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: var(--surface);
  transition: border-color 0.15s ease, transform 0.15s ease;
}

.btn-ghost:hover { border-color: var(--faint); transform: translateY(-1px); }

.nav {
  position: sticky;
  top: 0;
  z-index: 10;
  background: rgba(250, 250, 249, 0.85);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--line);
}

.nav-inner {
  display: flex;
  align-items: center;
  gap: 24px;
  height: 64px;
  position: relative;
}

.brand {
  font-family: "Bricolage Grotesque", sans-serif;
  font-weight: 800;
  font-size: 20px;
  letter-spacing: -0.02em;
  color: var(--ink);
  text-decoration: none;
}

.brand span { color: var(--accent); }

.nav-links { display: flex; gap: 24px; margin-left: auto; }

.nav-links a { color: var(--muted); text-decoration: none; font-size: 14.5px; font-weight: 500; }

.nav-links a:hover { color: var(--ink); }

.btn-nav { padding: 8px 16px; font-size: 14px; margin-left: 8px; }

.nav-toggle { display: none; }

.burger { display: none; flex-direction: column; gap: 5px; cursor: pointer; padding: 8px; margin-left: auto; }

.burger span { display: block; width: 20px; height: 2px; background: var(--ink); border-radius: 2px; }

section { padding: 88px 0; border-top: 1px solid var(--line); }

.hero {
  border-top: none;
  padding: 104px 0 96px;
  text-align: center;
  background:
    radial-gradient(56% 44% at 50% 0%, var(--accent-soft) 0%, rgba(243, 241, 254, 0) 70%),
    radial-gradient(38% 34% at 88% 18%, rgba(124, 111, 240, 0.07) 0%, rgba(124, 111, 240, 0) 70%);
}

.hero-inner { max-width: 780px; }

.pill {
  display: inline-block;
  background: var(--accent-soft);
  color: var(--accent-deep);
  border: 1px solid #e0dbfb;
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  padding: 6px 14px;
  border-radius: 999px;
  margin: 0 0 24px;
}

.hero h1 {
  font-size: clamp(2.4rem, 6vw, 3.9rem);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.05;
}

.hero-sub {
  color: var(--muted);
  font-size: 18px;
  max-width: 58ch;
  margin: 20px auto 0;
}

.hero-sub em { color: var(--ink); font-style: italic; }

.cta-row { display: flex; gap: 12px; justify-content: center; margin-top: 28px; }

.code-card {
  margin-top: 48px;
  background: var(--code-bg);
  border-radius: 14px;
  overflow: hidden;
  text-align: left;
  box-shadow: 0 24px 48px -24px rgba(28, 25, 23, 0.45);
}

.code-card-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 11px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.dot { width: 10px; height: 10px; border-radius: 50%; background: rgba(255, 255, 255, 0.14); }

.code-card-title { margin-left: 8px; color: var(--faint); font-size: 12px; }

.code-card pre { margin: 0; padding: 22px 24px; overflow-x: auto; font-size: 13.5px; line-height: 1.7; color: var(--code-ink); }

.tk-k { color: #a5a1f2; }
.tk-s { color: #7ee0c0; }
.tk-c { color: #78716c; }
.tk-f { color: #f5b455; }

.label {
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--accent-deep);
  margin: 0 0 12px;
}

.section-title { font-size: clamp(1.7rem, 3.4vw, 2.3rem); font-weight: 700; letter-spacing: -0.02em; }

.section-sub { color: var(--muted); font-size: 16.5px; max-width: 62ch; margin: 12px 0 0; }

.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; margin-top: 40px; }

.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 24px;
  transition: transform 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
}

.card:hover { transform: translateY(-2px); border-color: #c9c2fa; box-shadow: var(--shadow); }

.card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--accent-soft);
  color: var(--accent-deep);
  margin-bottom: 16px;
}

.card-icon svg { width: 20px; height: 20px; }

.card h3 { font-size: 16.5px; font-weight: 700; letter-spacing: -0.01em; }

.card p { color: var(--muted); font-size: 14.5px; line-height: 1.65; margin: 8px 0 0; }

.pipeline { display: flex; align-items: stretch; gap: 10px; margin-top: 40px; }

.pipe-step {
  flex: 1;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 18px 14px;
  text-align: center;
}

.pipe-step strong { display: block; font-size: 15px; letter-spacing: -0.01em; }

.pipe-step span { color: var(--muted); font-size: 12.5px; }

.pipe-step.hot { background: var(--accent-soft); border-color: #c9c2fa; }

.pipe-step.hot strong { color: var(--accent-deep); }

.pipe-arrow { align-self: center; color: var(--faint); font-size: 18px; }

.plan-note {
  margin-top: 16px;
  background: var(--code-bg);
  color: var(--code-ink);
  border-radius: 12px;
  padding: 14px 18px;
  font-size: 13px;
  overflow-x: auto;
  white-space: nowrap;
}

.plan-note b { color: #7ee0c0; font-weight: 600; }

.how-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; margin-top: 24px; }

.how-item h3 { font-size: 15.5px; }

.how-item .num { font-family: "JetBrains Mono", monospace; font-size: 12px; color: var(--accent-deep); font-weight: 600; display: block; margin-bottom: 6px; }

.how-item p { color: var(--muted); font-size: 14.5px; margin: 6px 0 0; }

.how-item code { background: var(--accent-soft); color: var(--accent-deep); font-size: 12.5px; padding: 1px 6px; border-radius: 6px; }

.chip-groups { margin-top: 40px; display: grid; gap: 28px; }

.chip-group-title { font-size: 13px; font-weight: 700; color: var(--ink); margin: 0 0 12px; }

.chips { display: flex; flex-wrap: wrap; gap: 10px; }

.chip {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 8px 15px;
  font-size: 14px;
  color: var(--muted);
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease;
}

.chip:hover { border-color: #c9c2fa; color: var(--ink); background: var(--accent-soft); }

.chip b { color: var(--ink); font-weight: 600; }

.starters { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }

.starter-chip {
  font-family: "JetBrains Mono", monospace;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent-deep);
  background: var(--accent-soft);
  border: 1px solid #e0dbfb;
  border-radius: 999px;
  padding: 6px 14px;
}

.quick-note { color: var(--muted); font-size: 15px; max-width: 62ch; margin-top: 20px; }

.status {
  display: inline-block;
  margin-top: 20px;
  font-size: 13px;
  font-weight: 600;
  color: var(--muted);
  border: 1px dashed var(--faint);
  border-radius: 999px;
  padding: 6px 14px;
}

footer { border-top: 1px solid var(--line); padding: 44px 0 52px; }

.footer-inner { display: flex; flex-wrap: wrap; gap: 16px; justify-content: space-between; align-items: center; }

.footer-tag { color: var(--muted); font-size: 14px; margin: 0; }

.footer-tag strong { color: var(--ink); }

.footer-meta { color: var(--faint); font-size: 13.5px; margin: 0; }

@keyframes rise { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: none; } }

[data-rise] { animation: rise 0.6s ease-out both; }

[data-rise="2"] { animation-delay: 0.08s; }
[data-rise="3"] { animation-delay: 0.16s; }
[data-rise="4"] { animation-delay: 0.24s; }
[data-rise="5"] { animation-delay: 0.32s; }

@media (prefers-reduced-motion: reduce) {
  html { scroll-behavior: auto; }
  [data-rise] { animation: none; }
  .btn-primary, .btn-ghost, .card { transition: none; }
}

@media (max-width: 760px) {
  .burger { display: flex; }
  .nav-links {
    display: none;
    position: absolute;
    top: 64px;
    left: 0;
    right: 0;
    background: var(--surface);
    border-bottom: 1px solid var(--line);
    padding: 16px 24px;
    flex-direction: column;
    gap: 14px;
    margin-left: 0;
  }
  .nav-toggle:checked ~ .nav-links { display: flex; }
  .btn-nav { display: none; }
  section { padding: 64px 0; }
  .hero { padding: 72px 0 64px; }
  .pipeline { flex-direction: column; gap: 6px; }
  .pipe-arrow { transform: rotate(90deg); }
  .hero-sub { font-size: 16px; }
}
```

---

### Task 4: `index.html` — the complete landing page

**Files:**
- Create: `/Users/oleg/NikitaProjects/CortexJ-site/index.html`

- [ ] **Step 1: Write `index.html` with this exact content**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>CortexJ — AI runtime orchestration and model portability for the JVM</title>
  <meta name="description" content="One JVM API. Any compatible model. The best available runtime. From legacy Java 8 applications to modern Java 25 systems, across interchangeable local and remote runtimes.">
  <link rel="icon" type="image/svg+xml" href="favicon.svg">
  <meta name="theme-color" content="#fafaf9">
  <meta property="og:type" content="website">
  <meta property="og:title" content="CortexJ — one JVM API, any model, the best runtime">
  <meta property="og:description" content="AI runtime orchestration and model portability for the JVM. Java 8 to Java 25, local and remote runtimes, explainable deterministic plans.">
  <meta property="og:url" content="https://okostiukevych.github.io/CortexJ-site/">
  <meta name="twitter:card" content="summary">
  <meta name="twitter:title" content="CortexJ — one JVM API, any model, the best runtime">
  <meta name="twitter:description" content="AI runtime orchestration and model portability for the JVM. Java 8 to Java 25, local and remote runtimes, explainable deterministic plans.">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,700;12..96,800&family=JetBrains+Mono:wght@400;600&family=Public+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="styles.css">
</head>
<body>
<header class="nav">
  <div class="container nav-inner">
    <input type="checkbox" id="nav-toggle" class="nav-toggle" tabindex="-1" aria-hidden="true">
    <label class="burger" for="nav-toggle" aria-label="Toggle navigation"><span></span><span></span></label>
    <a class="brand" href="#top">Cortex<span>J</span></a>
    <nav class="nav-links" aria-label="Sections">
      <a href="#why">Why</a>
      <a href="#how">How it works</a>
      <a href="#runtimes">Runtimes</a>
      <a href="#who">Who it's for</a>
    </nav>
    <a class="btn-primary btn-nav" href="#quick-start">Get started</a>
  </div>
</header>
<main id="top">
  <section class="hero">
    <div class="container hero-inner">
      <p class="pill" data-rise>AI runtime orchestration & model portability for the JVM</p>
      <h1 data-rise="2">One JVM API. Any model.<br>The best available runtime.</h1>
      <p class="hero-sub" data-rise="3">The platform answers the infrastructure questions itself — where the model lives, which revision to use, which runtime fits the hardware, whether memory suffices, and what the fallback is. Including <em>why</em> the chosen execution path was selected.</p>
      <div class="cta-row" data-rise="4">
        <a class="btn-primary" href="#quick-start">Get started</a>
        <a class="btn-ghost" href="#how">How it works</a>
      </div>
      <div class="code-card" data-rise="5">
        <div class="code-card-head"><span class="dot"></span><span class="dot"></span><span class="dot"></span><span class="code-card-title">QuickExample.java</span></div>
<pre><code><span class="tk-c">// one dependency — any model, the best available runtime</span>
CortexJ platform = CortexJ.<span class="tk-f">builder</span>().<span class="tk-f">build</span>();

<span class="tk-k">try</span> (ModelHandle model = platform.<span class="tk-f">models</span>().<span class="tk-f">load</span>(ModelReference.<span class="tk-f">of</span>(<span class="tk-s">"Qwen/Qwen3-8B"</span>))) {
    GenerationResponse response = model
            .<span class="tk-f">capability</span>(TextGenerationCapability.<span class="tk-k">class</span>)
            .<span class="tk-f">generate</span>(GenerationRequest.<span class="tk-f">prompt</span>(<span class="tk-s">"Explain CQRS"</span>));
    System.out.<span class="tk-f">println</span>(response.<span class="tk-f">text</span>());
}</code></pre>
      </div>
    </div>
  </section>

  <section id="why">
    <div class="container">
      <p class="label">What it's for</p>
      <h2 class="section-title">Stop hand-wiring model bindings</h2>
      <p class="section-sub">CortexJ is an AI runtime orchestration and model portability platform for the JVM. You write against one API — the platform decides where the model lives, which runtime serves it, and what to do when hardware or policy says no.</p>
      <div class="grid">
        <div class="card">
          <div class="card-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l9 5-9 5-9-5 9-5z"/><path d="M3 12.5l9 5 9-5"/><path d="M3 16.5l9 5 9-5"/></svg></div>
          <h3>Model portability</h3>
          <p>One API for any compatible model — local, Hugging Face or plain HTTP repositories. Immutable revisions: what you resolved is what runs.</p>
        </div>
        <div class="card">
          <div class="card-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="6" cy="5" r="2.2"/><circle cx="18" cy="5" r="2.2"/><circle cx="12" cy="19" r="2.2"/><path d="M8.2 5h7.6M12 7.2v9.6"/></svg></div>
          <h3>Runtime orchestration</h3>
          <p>The execution planner picks the best available runtime for each model and machine — deterministic scoring, hard constraints, fallback chains.</p>
        </div>
        <div class="card">
          <div class="card-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3.5 12a8.5 8.5 0 1 0 2.6-6.1L3.5 8.5"/><path d="M3.5 3.5v5h5"/><path d="M12 8v4.2l2.8 1.6"/></svg></div>
          <h3>Java 8 → 25</h3>
          <p>Starters for every LTS line, identical business code. A starter built for a newer JVM fails fast with the exact remediation.</p>
        </div>
        <div class="card">
          <div class="card-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6.5 3.5h9l4 4V20a1.5 1.5 0 0 1-1.5 1.5H6.5A1.5 1.5 0 0 1 5 20V5a1.5 1.5 0 0 1 1.5-1.5z"/><path d="M15 3.5V8h4"/><path d="M9 14l2 2 4-4"/></svg></div>
          <h3>Explainable decisions</h3>
          <p>Every plan reports why: hard constraints considered, scores, memory estimates. No mystery paths, no provider-order luck.</p>
        </div>
      </div>
    </div>
  </section>

  <section id="how">
    <div class="container">
      <p class="label">How it works</p>
      <h2 class="section-title">Plan first. Execute second. Explain always.</h2>
      <p class="section-sub">Loading a model produces an execution plan before anything is downloaded or started. The plan is deterministic — same inputs, same plan — and it is explainable.</p>
      <div class="pipeline">
        <div class="pipe-step"><strong>Your code</strong><span>one API, any model</span></div>
        <div class="pipe-arrow">→</div>
        <div class="pipe-step hot"><strong>CortexJ planner</strong><span>candidates · policies · memory</span></div>
        <div class="pipe-arrow">→</div>
        <div class="pipe-step"><strong>Runtime SPI</strong><span>ServiceLoader plugins</span></div>
        <div class="pipe-arrow">→</div>
        <div class="pipe-step"><strong>CPU · GPU · NPU</strong><span>best available hardware</span></div>
      </div>
      <div class="plan-note"><b>plan ▸</b> runtime: llama.cpp · hardware: metal · memory: 6.2 / 8.0 GB ✓ · fallback: onnx · reasons: offline policy, gpu available</div>
      <div class="how-list">
        <div class="how-item"><span class="num">01</span><h3>Plan before execution</h3><p>Ask what will happen — <code>cortexj plan</code> — before downloading or running anything.</p></div>
        <div class="how-item"><span class="num">02</span><h3>Deterministic by construction</h3><p>Hard constraints first, then scoring with defined tie-breaks. Provider order never decides.</p></div>
        <div class="how-item"><span class="num">03</span><h3>Graceful fallback</h3><p>When the first choice can't run, the planner walks the fallback chain — and tells you.</p></div>
      </div>
    </div>
  </section>

  <section id="runtimes">
    <div class="container">
      <p class="label">Runtimes & integrations</p>
      <h2 class="section-title">Pluggable everything</h2>
      <p class="section-sub">Runtimes and repositories are plugins discovered via ServiceLoader. The planner decides from descriptors and policies, never from provider order — and adapters slot CortexJ into the frameworks you already use.</p>
      <div class="chip-groups">
        <div>
          <p class="chip-group-title">Runtimes</p>
          <div class="chips">
            <span class="chip"><b>ONNX Runtime</b> — probe, session load, tokenizer, embeddings</span>
            <span class="chip"><b>llama.cpp</b> — managed llama-server bridge</span>
            <span class="chip"><b>OpenAI-compatible remote</b> — vLLM, llama-server, gateways</span>
            <span class="chip"><b>DJL</b> — honest engine probe</span>
          </div>
        </div>
        <div>
          <p class="chip-group-title">Adapters & tooling</p>
          <div class="chips">
            <span class="chip">Spring AI</span>
            <span class="chip">LangChain4j</span>
            <span class="chip">OpenTelemetry</span>
            <span class="chip">CLI — doctor · plan · run</span>
            <span class="chip">Lockfiles & air-gap bundles</span>
            <span class="chip">GGUF & Safetensors converters</span>
            <span class="chip">Declarative @ModelPrompt API</span>
            <span class="chip">TCK for provider authors</span>
          </div>
        </div>
      </div>
    </div>
  </section>

  <section id="who">
    <div class="container">
      <p class="label">Who it's for</p>
      <h2 class="section-title">Built for JVM teams — legacy to bleeding edge</h2>
      <p class="section-sub">The same business code runs from Java 8 to Java 25; platform modules pick up what each JVM offers, from fixed thread pools to virtual threads.</p>
      <div class="grid">
        <div class="card"><h3>Java teams adding AI</h3><p>Run capable local models inside your JVM stack. One Maven dependency, no Python sidecar, no hand-rolled bindings.</p></div>
        <div class="card"><h3>Enterprise & air-gapped</h3><p>Pinned model lockfiles, verified air-gap bundles, offline re-resolution. Reproducible deployments by construction.</p></div>
        <div class="card"><h3>Legacy Java 8–11 stacks</h3><p>Bring modern AI features to old applications — the same code that runs on Java 25 compiles for Java 8.</p></div>
        <div class="card"><h3>Runtime & repository authors</h3><p>A public SPI, declarative policies and a technology compatibility kit for your provider.</p></div>
      </div>
    </div>
  </section>

  <section id="quick-start">
    <div class="container">
      <p class="label">Quick start</p>
      <h2 class="section-title">One starter, matching your JVM</h2>
      <p class="section-sub">Add the BOM, add the starter for your JVM line, load a model. The starter validates the JVM at bootstrap — a mismatch fails fast with an actionable diagnostic.</p>
      <div class="code-card">
        <div class="code-card-head"><span class="dot"></span><span class="dot"></span><span class="dot"></span><span class="code-card-title">pom.xml</span></div>
<pre><code>&lt;<span class="tk-k">dependencyManagement</span>&gt;
    &lt;<span class="tk-k">dependencies</span>&gt;
        &lt;<span class="tk-k">dependency</span>&gt;
            &lt;<span class="tk-k">groupId</span>&gt;io.cortexj&lt;/<span class="tk-k">groupId</span>&gt;
            &lt;<span class="tk-k">artifactId</span>&gt;cortexj-bom&lt;/<span class="tk-k">artifactId</span>&gt;
            &lt;<span class="tk-k">version</span>&gt;<span class="tk-s">0.1.0-SNAPSHOT</span>&lt;/<span class="tk-k">version</span>&gt;
            &lt;<span class="tk-k">type</span>&gt;pom&lt;/<span class="tk-k">type</span>&gt;
            &lt;<span class="tk-k">scope</span>&gt;import&lt;/<span class="tk-k">scope</span>&gt;
        &lt;/<span class="tk-k">dependency</span>&gt;
    &lt;/<span class="tk-k">dependencies</span>&gt;
&lt;/<span class="tk-k">dependencyManagement</span>&gt;

&lt;<span class="tk-k">dependency</span>&gt;
    &lt;<span class="tk-k">groupId</span>&gt;io.cortexj&lt;/<span class="tk-k">groupId</span>&gt;
    &lt;<span class="tk-k">artifactId</span>&gt;cortexj-starter-java21&lt;/<span class="tk-k">artifactId</span>&gt;
&lt;/<span class="tk-k">dependency</span>&gt;</code></pre>
      </div>
      <div class="starters">
        <span class="starter-chip">cortexj-starter-java8</span>
        <span class="starter-chip">cortexj-starter-java11</span>
        <span class="starter-chip">cortexj-starter-java17</span>
        <span class="starter-chip">cortexj-starter-java21</span>
        <span class="starter-chip">cortexj-starter-java25</span>
      </div>
      <p class="quick-note">The business code is identical across all five starters — pick once, never think about it again.</p>
      <span class="status">0.1.0-SNAPSHOT · Maven Central release coming</span>
    </div>
  </section>
</main>
<footer>
  <div class="container footer-inner">
    <p class="footer-tag"><strong>CortexJ</strong> — AI runtime orchestration and model portability for the JVM</p>
    <p class="footer-meta">© 2026 CortexJ · Apache License 2.0 · okostiukevych.github.io/CortexJ-site</p>
  </div>
</footer>
</body>
</html>
```

---

### Task 5: `.github/workflows/pages.yml` — deploy workflow

**Files:**
- Create: `/Users/oleg/NikitaProjects/CortexJ-site/.github/workflows/pages.yml`

- [ ] **Step 1: Write `pages.yml`**

```yaml
name: Pages

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: true

jobs:
  deploy:
    name: Deploy to GitHub Pages
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Configure Pages
        uses: actions/configure-pages@v5
      - name: Upload site artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: .
      - name: Deploy
        id: deployment
        uses: actions/deploy-pages@v4
```

- [ ] **Step 2: Validate the YAML**

```bash
python3 -c "import yaml; yaml.safe_load(open('/Users/oleg/NikitaProjects/CortexJ-site/.github/workflows/pages.yml')); print('yaml ok')"
```

Expected: `yaml ok` (PyYAML is installed on this machine — verified).

---

### Task 6: Local verification

**Files:** none (checks only)

- [ ] **Step 1: Serve the site locally**

```bash
cd /Users/oleg/NikitaProjects/CortexJ-site && python3 -m http.server 8123 > /dev/null 2>&1 &
sleep 1
```

- [ ] **Step 2: Check all assets return 200**

```bash
curl -s -o /dev/null -w "index %{http_code}\n" http://localhost:8123/
curl -s -o /dev/null -w "css   %{http_code}\n" http://localhost:8123/styles.css
curl -s -o /dev/null -w "icon  %{http_code}\n" http://localhost:8123/favicon.svg
```

Expected: `index 200`, `css 200`, `icon 200`.

- [ ] **Step 3: Ask the user to review in the browser**

Ask the user to open `http://localhost:8123/` (desktop width and a narrow/mobile width) and confirm the design looks right. Fix anything they flag, then stop the server:

```bash
kill %1
```

- [ ] **Step 4: Accuracy pass against README**

Re-read `/Users/oleg/NikitaProjects/CortexJ/README.md` and verify every claim on the page matches it (API snippet, starter names, runtime names, personas). Fix any mismatch.

---

### Task 7: Commit and push the site repo

**Files:** none (git only)

- [ ] **Step 1: Single commit (per user request) and push**

```bash
cd /Users/oleg/NikitaProjects/CortexJ-site
git add -A
git commit -m "CortexJ product site: Clean Light landing page + GitHub Pages deploy workflow"
git push -u origin main
```

Expected: push succeeds. If it fails with an auth error, push once with the token (never store it in the remote config):

```bash
TOKEN=$(git -C /Users/oleg/NikitaProjects/CortexJ remote get-url origin | sed -E 's|https://[^:]+:([^@]+)@.*|\1|')
git push https://okostiukevych:${TOKEN}@github.com/okostiukevych/CortexJ-site.git main
```

---

### Task 8: Verify the deployment

**Files:** none (checks only)

- [ ] **Step 1: Wait for the workflow, then check its status**

```bash
sleep 45
TOKEN=$(git -C /Users/oleg/NikitaProjects/CortexJ remote get-url origin | sed -E 's|https://[^:]+:([^@]+)@.*|\1|')
curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/okostiukevych/CortexJ-site/actions/runs?per_page=1" | python3 -c "import json,sys; r=json.load(sys.stdin)['workflow_runs'][0]; print(r['name'], '|', r['status'], '|', r['conclusion'])"
```

Expected: `Pages | completed | success`. If still `in_progress`, wait 30s and re-run. If `failure`, read the run's jobs via the API and fix the workflow.

- [ ] **Step 2: Check the live URL**

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://okostiukevych.github.io/CortexJ-site/
```

Expected: `200`.

- [ ] **Step 3: Set the repo homepage field (polish)**

```bash
TOKEN=$(git -C /Users/oleg/NikitaProjects/CortexJ remote get-url origin | sed -E 's|https://[^:]+:([^@]+)@.*|\1|')
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" https://api.github.com/repos/okostiukevych/CortexJ-site -d '{"homepage":"https://okostiukevych.github.io/CortexJ-site/"}' > /dev/null && echo "homepage set"
```

Expected: `homepage set`.

---

### Task 9: Private repo housekeeping

**Files:**
- Modify: `/Users/oleg/NikitaProjects/CortexJ/.gitignore` (append `.superpowers/`)
- Commit (private repo): revised spec + this plan + `.gitignore`

- [ ] **Step 1: Append `.superpowers/` to `.gitignore`**

Add a final line: `.superpowers/`

- [ ] **Step 2: Commit and push the private repo**

```bash
cd /Users/oleg/NikitaProjects/CortexJ
git add .gitignore docs/superpowers/specs/2026-09-26-landing-page-design.md docs/superpowers/plans/2026-09-26-cortexj-site-landing.md
git commit -m "Docs: landing spec revision for plan C (separate public CortexJ-site repo, product repo stays private) + implementation plan; ignore .superpowers/"
git push origin main
```

Expected: clean push; `git status` shows only untracked nothing (`.superpowers/` now ignored).
