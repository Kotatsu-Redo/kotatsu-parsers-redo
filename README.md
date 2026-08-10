# Kotatsu Parsers Redo

A maintained fork of [KotatsuApp/kotatsu-parsers](https://github.com/KotatsuApp/kotatsu-parsers) for [Kotatsu-Redo](https://github.com/Kotatsu-Redo/Kotatsu-Redo) and other Kotlin/JVM or Android clients.

[![Sources](https://img.shields.io/badge/dynamic/yaml?url=https%3A%2F%2Fraw.githubusercontent.com%2FKotatsu-Redo%2Fkotatsu-parsers-redo%2Frefs%2Fheads%2Fmaster%2F.github%2Fsummary.yaml&query=total&label=manga%20sources&color=%23E9321C)](.github/summary.yaml)
[![JitPack](https://jitpack.io/v/Kotatsu-Redo/kotatsu-parsers-redo.svg)](https://jitpack.io/#Kotatsu-Redo/kotatsu-parsers-redo)
[![License: GPL-3.0](https://img.shields.io/github/license/Kotatsu-Redo/kotatsu-parsers-redo)](LICENSE)

The fork preserves the original parser API while maintaining source integrations and compatibility needed by Kotatsu-Redo. The project contains the parser library and a KSP module that generates the source registry and summary metadata.

## Use the library

Add JitPack to your repositories:

```kotlin
repositories {
    maven("https://jitpack.io")
}
```

Add a tagged version, commit hash, or JitPack snapshot as the dependency version:

```kotlin
dependencies {
    implementation("com.github.Kotatsu-Redo:kotatsu-parsers-redo:<version>")
}
```

For Android, exclude the JVM `org.json` implementation:

```kotlin
dependencies {
    implementation("com.github.Kotatsu-Redo:kotatsu-parsers-redo:<version>") {
        exclude(group = "org.json", module = "json")
    }
}
```

Android consumers must enable [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring), including the required NIO APIs.

Create a parser through your `MangaLoaderContext` implementation:

```kotlin
val parser = mangaLoaderContext.newParserInstance(MangaParserSource.MANGADEX)
```

Reference implementations are available for [Android](https://github.com/KotatsuApp/Kotatsu/blob/devel/app/src/main/kotlin/org/koitharu/kotatsu/core/parser/MangaLoaderContextImpl.kt) and [JVM](https://github.com/KotatsuApp/kotatsu-dl/blob/master/src/main/kotlin/org/koitharu/kotatsu/dl/parsers/MangaLoaderContextImpl.kt) clients.

## Develop

Requirements:

- JDK 17
- the checked-in Gradle wrapper

```shell
git clone https://github.com/Kotatsu-Redo/kotatsu-parsers-redo.git
cd kotatsu-parsers-redo
./gradlew check
```

See:

- [Development guide](docs/development.md) for project layout and commands
- [Parser contribution guide](CONTRIBUTING.md) for parser implementation details
- [Fork maintenance policy](docs/maintenance.md) for scope and upstream handling
- [Plan](docs/plan.md) for current initialization and maintenance work

## License and content disclaimer

This fork retains the upstream [GPL-3.0 license](LICENSE).

The maintainers are not affiliated with the websites accessed by the parsers and do not host their content. Parsers access information already available through a web browser. Source owners can use the repository's issue templates to report a concern or request removal.
