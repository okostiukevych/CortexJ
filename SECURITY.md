# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

Pre-1.0: the API is under active development, but security reports are
handled with production seriousness on all supported lines.

## Reporting a Vulnerability

**Do not open a public issue.** Use
[GitHub Security Advisories](https://github.com/okostiukevych/CortexJ/security/advisories/new)
(private report), or email the maintainer.

Please include:

- the affected module and version (`cortexj-*`, `0.1.0-SNAPSHOT` + commit),
- a minimal reproducer (code, model reference, or CLI invocation),
- the impact you see (RCE, SSRF, path traversal, secret leak, DoS, …),
- whether it needs local model files, network, or a specific JDK.

## What happens next

- Acknowledgement within **7 days**.
- A fix or a documented workaround, then a public advisory once patched.
- Credit in the advisory unless you prefer anonymity.

## Scope notes

In scope: anything reachable through the public API, CLI, server mode,
repository/download/cache paths, the isolated worker protocol, and the
build (dependencies, plugins, workflows).

Out of scope by design (tracked, not vulnerabilities): missing DJL engine
artifacts, absent JInfer artifact on Maven Central, GPU lanes requiring
self-hosted runners, and incubating `0.x` platform tracks behaving as
documented (see the release-train table in `docs/verification-north-star.md`).

## Hardening already in place

SSRF guards on every network hop, checksum-verified downloads, atomic
cache writes, path-traversal and symlink containment, trust policies
(`TRUSTED_ONLY`), sealed credential delivery, parser caps, and a
`spotbugs-exclude.xml`-triaged static-analysis lane. The audit trail lives
in `docs/reliability-audit-*.md` and `docs/security-review.md`.
