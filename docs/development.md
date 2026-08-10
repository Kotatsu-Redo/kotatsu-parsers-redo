# Development

## Prerequisites

- JDK 17
- Git
- A network connection for Gradle dependencies and live parser integration tests

Use `./gradlew` rather than a system Gradle installation.

## Repository layout

| Path | Purpose |
|------|---------|
| `src/main/kotlin/org/koitharu/kotatsu/parsers/` | Public API, parser runtime, models, and utilities |
| `src/main/kotlin/org/koitharu/kotatsu/parsers/site/` | Concrete source parsers and shared site-engine parsers |
| `src/test/kotlin/` | Unit and live parser integration tests |
| `kotatsu-parsers-ksp/` | KSP processor that generates the parser source registry |
| `.github/summary.yaml` | KSP-generated source summary |

The parser class hierarchy is illustrated in [parser_classes.png](parser_classes.png). Detailed parser conventions are in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Common commands

| Task | Command |
|------|---------|
| Compile the library | `./gradlew compileKotlin` |
| Compile the KSP processor | `./gradlew :kotatsu-parsers-ksp:compileKotlin` |
| Run all tests | `./gradlew test` |
| Run all checks | `./gradlew check` |
| Run one test class | `./gradlew test --tests "fully.qualified.TestClass"` |
| Run parser integration tests | `./gradlew test --tests "org.koitharu.kotatsu.parsers.MangaParserTest"` |
| Generate an HTML parser report | `./gradlew generateTestsReport` |

Live parser tests depend on third-party websites and may fail because of rate limits, blocking, downtime, or site changes. Record enough response context to distinguish infrastructure failures from parser regressions.

## Test selected parsers

Temporarily edit `src/test/kotlin/org/koitharu/kotatsu/parsers/MangaSources.kt`:

1. Add parser enum names to `names`.
2. Keep `mode = EnumSource.Mode.INCLUDE`.
3. Run `MangaParserTest`.
4. Restore the file before committing.

## Generated files

KSP generates source code under `build/generated/` and updates `.github/summary.yaml`. Do not edit either output manually. Change the parser annotations or KSP processor, then rerun the relevant Gradle task.
