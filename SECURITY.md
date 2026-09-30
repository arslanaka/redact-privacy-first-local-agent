# Security policy

Local Privacy Agent handles documents that may contain personal data, so security reports are taken
seriously.

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately through GitHub:
**Security → Report a vulnerability** on this repository (private vulnerability reporting).

Include what you found, how to reproduce it (with synthetic data only) and the impact you expect.
You should get a first response within a few days.

## Scope

In scope: anything that could leak document content or personal data, bypass human review, leave
readable data in a redacted output, expose the backend or AI service without the internal token, or
bypass sign-in on a private deployment.

## Known limitations (not vulnerabilities)

These are documented design limits; see the README's Limitations section:

- It is not a certified anonymisation system, and detectors can miss personal data.
- BLOCK redaction style reveals the length of each redacted value.
- Extracted text is stored unencrypted in PostgreSQL for the retention window.
- A private deployment has no roles or per-user document isolation: any signed-in user can open any document
  they have a link to.
- Only local use or a small private deployment is supported; it is not hardened for public, multi-tenant use.

## Supported versions

Only the latest commit on the default branch receives fixes.
