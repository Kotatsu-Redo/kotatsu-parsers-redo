# Fork maintenance

## Purpose

Kotatsu Parsers Redo continues the parser library for Kotatsu-Redo while retaining compatibility with the original `org.koitharu.kotatsu.parsers` API where practical. The fork prioritizes:

1. keeping active sources functional;
2. supporting Kotatsu-Redo integration;
3. accepting focused source fixes and additions;
4. preserving a usable Kotlin/JVM and Android library API.

The original [KotatsuApp/kotatsu-parsers](https://github.com/KotatsuApp/kotatsu-parsers) repository remains the historical upstream and attribution source.

## Change policy

- Prefer small parser-specific changes over unrelated refactors.
- Reuse shared engine parsers when several sources have the same implementation.
- Treat source URLs, page structures, and undocumented APIs as unstable external contracts.
- Keep parser IDs domain-independent so domain changes do not invalidate client data.
- Document intentional API incompatibilities in the pull request and release notes.
- Do not remove a source without confirming that it is permanently unavailable, superseded, or subject to a valid removal request.

## Upstream changes

Before importing changes from another repository:

1. identify the exact commits and affected parsers;
2. review them against this fork's current code instead of merging blindly;
3. preserve original authorship and license notices;
4. run focused tests, followed by `./gradlew check` when practical;
5. record conflicts or deliberate deviations in the pull request.

## Releases

Until an automated release policy is established, JitPack can build a commit hash or branch snapshot. Consumers that require reproducible builds should pin a tag or full commit hash rather than a moving snapshot. Release automation and versioning remain tracked in [plan.md](plan.md).

## Triage

When a parser breaks, capture:

- source and configured domain;
- failing operation (list, search, details, chapters, or pages);
- relevant HTTP status or sanitized response details;
- whether the site works in a normal browser;
- whether anti-bot protection or authentication is involved.

Never publish credentials, session cookies, access tokens, or personal reading data.
