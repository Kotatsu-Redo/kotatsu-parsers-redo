# Fork initialization plan

This document tracks repository-level work for making Kotatsu Parsers Redo independently maintainable. Parser-specific bugs belong in GitHub issues rather than this plan.

## Completed

- [x] Identify the project as the Kotatsu-Redo maintained fork.
- [x] Point README badges and dependency coordinates at this repository.
- [x] Document JDK 17, Gradle commands, generated outputs, and focused parser testing.
- [x] Document fork scope and upstream-change handling.
- [x] Add repository instructions for coding agents.

## Next

- [ ] Add CI that runs compilation, unit tests, and KSP validation on JDK 17.
- [ ] Separate deterministic unit tests from live website integration tests in CI.
- [ ] Define semantic versioning, tagging, release notes, and a reproducible publishing workflow.
- [ ] Publish and verify the first fork-owned dependency artifact.
- [ ] Audit issue templates, repository links, and community references inherited from upstream.
- [ ] Establish a baseline of active, broken, protected, and retired sources.

## Later

- [ ] Add regression fixtures for shared parser engines where responses can be tested offline.
- [ ] Document API compatibility expectations for Kotatsu-Redo and third-party clients.
- [ ] Automate source-health reporting without treating transient site failures as code regressions.
- [ ] Review generated source metadata in CI and fail when committed output is stale.

## Definition of initialized

The fork is initialized when validation runs on every pull request, a versioned artifact can be reproduced from a tag, fork-owned links and policies are in place, and maintainers have a documented process for triaging parser failures.
