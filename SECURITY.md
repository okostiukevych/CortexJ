# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |

The 1.0 line is in release-candidate stage. Security reports are handled
with production seriousness on all supported lines.

## Reporting a Vulnerability

**Do not open a public issue.** Use
[GitHub Security Advisories](https://github.com/okostiukevych/CortexJ/security/advisories/new)
(private report), or email the maintainer.

Please include:

- the affected module and version (`cortexj-*`, `1.0.0-RC1` + commit),
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
artifacts, an absent optional JInfer artifact on Maven Central, GPU lanes
requiring self-hosted runners, and incubating platform tracks behaving as
documented.

## Hardening already in place

SSRF guards on every network hop, checksum-verified downloads, atomic
cache writes, path-traversal and symlink containment, trust policies
(`TRUSTED_ONLY`), sealed credential delivery, parser caps, and a
`spotbugs-exclude.xml`-triaged static-analysis lane.
