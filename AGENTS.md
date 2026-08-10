# Agent Instructions

## Toolchain
- Use the checked-in Gradle wrapper: `./gradlew`.
- Use JDK 17; the Kotlin modules target JVM toolchain 17.

## Commands
| Task | Command |
|------|---------|
| Run one test class | `./gradlew test --tests "fully.qualified.TestClass"` |
| Run parser integration tests | `./gradlew test --tests "org.koitharu.kotatsu.parsers.MangaParserTest"` |
| Run all tests | `./gradlew test` |
| Compile the main library | `./gradlew compileKotlin` |
| Compile the KSP processor | `./gradlew :kotatsu-parsers-ksp:compileKotlin` |
| Run all checks | `./gradlew check` |
| Generate the HTML test report | `./gradlew generateTestsReport` |

## External References
| Need | File |
|------|------|
| Project usage and scope | `README.md` |
| Parser design and testing | `CONTRIBUTING.md` |
| Local development | `docs/development.md` |
| Fork maintenance policy | `docs/maintenance.md` |
| Current roadmap | `docs/plan.md` |
| Parser class hierarchy | `docs/parser_classes.png` |

## Git Workflow
- Before implementation, run `git fetch --prune`, inspect local and upstream state, and start from the latest target branch without discarding uncommitted work.
- Delete a completed local branch only when it is merged into its target and its upstream branch is gone.

## Key Conventions
- Put source-specific parsers under `src/main/kotlin/org/koitharu/kotatsu/parsers/site/`.
- Reuse an existing site-engine base parser when the source uses that engine.
- Use null-safe helpers from `src/main/kotlin/org/koitharu/kotatsu/parsers/util/` instead of raw JSoup helpers.
- Annotate concrete parsers with `@MangaSourceParser` and give them one primary constructor parameter of type `MangaLoaderContext`.
- Configure the default host through `configKeyDomain`; use `domain` at runtime instead of hardcoding hosts.
- Generate domain-independent IDs from relative URLs or source IDs with the existing `generateUid` helpers.
- Choose `PagedMangaParser` for page-based pagination and `SinglePageMangaParser` for non-paginated sources.
- Keep `availableSortOrders` non-empty.
- To select parser integration cases, temporarily edit `src/test/kotlin/org/koitharu/kotatsu/parsers/MangaSources.kt`; do not commit that selection change.
- Do not edit `build/generated/` outputs or `.github/summary.yaml` by hand; KSP produces them.
