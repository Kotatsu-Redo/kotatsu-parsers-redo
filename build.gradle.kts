import tasks.ReportGenerateTask
import tasks.bundle.BundleKeepRulesTask
import tasks.bundle.CheckBundleLinksTask
import tasks.bundle.DexBundleTask
import tasks.bundle.GenerateSigningKeyTask
import tasks.bundle.ParsersApiTask
import tasks.bundle.SignBundleTask
import tasks.tests.CheckNewerThanPublishedTask
import tasks.tests.SelectParserTestsTask
import tasks.tests.SummarizeParserTestsTask
import java.util.Properties

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

group = "org.koitharu"
version = "1.0"

// What an application and the parsers it downloads have in common: see ParsersApi. Compiled apart from the parsers and
// before them, so that it cannot come to lean on anything of theirs.
val contract: SourceSet by sourceSets.creating

// Which sources to run the live tests for, as selectParserTests writes them: see tasks.tests
val testSourcesProperty = providers.gradleProperty("testSources")

tasks.test {
    useJUnitPlatform()
    if (testSourcesProperty.isPresent) {
        val testSources = testSourcesProperty.get()
        doFirst {
            // To the tests, no names at all means every source there is
            if (testSources.isBlank()) {
                throw GradleException("-PtestSources is empty: there is nothing to test")
            }
        }
        systemProperty("parsers.test.sources", testSources)
        // The sites are what is tested, so a run is never the same as the last one. Failures are for a person to read
        // in summarizeParserTests, and not for the build to stop at.
        outputs.upToDateWhen { false }
        ignoreFailures = true
    }
}

val sourcesMapFile = layout.buildDirectory.file("parsersTests/sources-map.tsv")

ksp {
    arg("summaryOutputDir", "${projectDir}/.github")
    arg("projectDir", projectDir.absolutePath)
    arg("sourcesMapFile", sourcesMapFile.get().asFile.absolutePath)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi",
            "-opt-in=org.koitharu.kotatsu.parsers.DirectParsersAccess",
        )
    }
}

fun git(vararg args: String): String? = runCatching {
    val process = ProcessBuilder("git", *args).directory(projectDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    output.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
}.getOrNull()

// Which of two sets of parsers is the newer one is told by the time of the commit they were built from
val parsersVersion: String = (findProperty("parsersVersion") as String?)
    ?: git("log", "-1", "--format=%ct")?.takeIf { it.toLongOrNull() != null }
    ?: (System.currentTimeMillis() / 1000).toString().also {
        // Without a commit there is no time to tell the build by, so it is taken to be made just now
        logger.warn(
            "This build is not from a git checkout: its version is the time now ($it), so it is taken to be " +
                "newer than anything published before it.",
        )
    }
val parsersRevision: String = (findProperty("parsersRevision") as String?)
    ?: git("rev-parse", "--short=10", "HEAD")
    ?: "unknown"

val generateParsersBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/parsersBuildInfo/kotlin")
    val version = parsersVersion
    val revision = parsersRevision
    inputs.property("version", version)
    inputs.property("revision", revision)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("org/koitharu/kotatsu/parsers/core/ParsersBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package org.koitharu.kotatsu.parsers.core

            internal object ParsersBuildInfo {

            	const val VERSION: Long = ${version}L

            	const val REVISION: String = "$revision"
            }

            """.trimIndent(),
        )
    }
}

kotlin {
    jvmToolchain(17)
    explicitApiWarning()
    sourceSets["main"].kotlin.srcDirs("build/generated/ksp/main/kotlin")
    sourceSets["main"].kotlin.srcDir(generateParsersBuildInfo)
    // One module to the compiler, as they were before being told apart: the parsers use what is internal to the
    // contract, and count on a property of a model keeping the type it was checked for
    target.compilations["main"].associateWith(target.compilations["contract"])
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

dependencies {
    "contractImplementation"(libs.kotlinx.coroutines.core)
    "contractImplementation"(libs.okhttp)
    "contractImplementation"(libs.okio)
    "contractImplementation"(libs.json)
    "contractImplementation"(libs.androidx.collection)
    "contractImplementation"(libs.jsoup)

    implementation(contract.output)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.json)
    implementation(libs.androidx.collection)
    api(libs.jsoup)

    ksp(project(":kotatsu-parsers-ksp"))

    testImplementation(contract.output)
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.engine)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.quickjs)
}

tasks.register<ReportGenerateTask>("generateTestsReport")

// ---- Parsers an application downloads after it is built: see docs/BUNDLE.md ----

val parsersApiVersion: Int = Regex("""const val VERSION: Int = (\d+)""")
    .find(file("src/contract/kotlin/org/koitharu/kotatsu/parsers/ParsersBundle.kt").readText())
    ?.groupValues?.get(1)?.toInt()
    ?: error("ParsersApi.VERSION was not found")
val parsersApiRecords = layout.projectDirectory.dir("api")

// Loading code from a file is only done on Android 8 and later, where it needs nothing compiled on the phone first
val bundleMinSdk = 26

// The libraries a bundle is built with and finds in the application. Not those that are only annotations, and not
// org.json, of which Android has its own.
val bundleLibraries = configurations.runtimeClasspath.map { classpath ->
    classpath.resolvedConfiguration.resolvedArtifacts
        .map { it.moduleVersion.id }
        .filterNot { it.group == "org.json" || it.group == "org.jetbrains" || it.group == "androidx.annotation" }
        .map { "${it.group}:${it.name}:${it.version}" }
}

val checkParsersApi by tasks.registering(ParsersApiTask::class) {
    group = "verification"
    description = "Fails if what is under src/contract differs from the record of its version."
    classes.from(contract.output.classesDirs)
    apiVersion.set(parsersApiVersion)
    libraries.set(bundleLibraries)
    write.set(false)
    overwrite.set(false)
    recordsDir.set(parsersApiRecords)
}

tasks.register<ParsersApiTask>("dumpParsersApi") {
    group = "verification"
    description = "Writes the record of a new version of what is under src/contract."
    classes.from(contract.output.classesDirs)
    apiVersion.set(parsersApiVersion)
    libraries.set(bundleLibraries)
    write.set(true)
    overwrite.set(providers.gradleProperty("overwriteParsersApi").map { true }.orElse(false))
    recordsDir.set(parsersApiRecords)
}

tasks.check {
    dependsOn(checkParsersApi)
}

val generateBundleKeepRules by tasks.registering(BundleKeepRulesTask::class) {
    classes.from(contract.output.classesDirs)
    outputDir.set(layout.buildDirectory.dir("generated/bundleKeepRules"))
}

tasks.jar {
    from(contract.output)
    from(generateBundleKeepRules)
}

val contractJar by tasks.registering(Jar::class) {
    archiveClassifier.set("contract")
    from(contract.output)
}

// The parsers alone: what is in the contract stays in the application and is never sent again
val parsersBundleJar by tasks.registering(Jar::class) {
    archiveClassifier.set("bundle-classes")
    from(sourceSets.main.get().output)
    exclude("META-INF/**")
    includeEmptyDirs = false
}

val r8: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    r8(libs.r8)
}

fun findAndroidJar(): File {
    (findProperty("androidJar") as String?)?.let { return file(it) }
    val sdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir")
        }
        ?: throw GradleException(
            "The Android SDK is needed to make a bundle of the parsers: set ANDROID_HOME, sdk.dir in " +
                "local.properties, or -PandroidJar=<path of an android.jar>",
        )
    val platform = File(sdk, "platforms").listFiles().orEmpty()
        .filter { File(it, "android.jar").exists() }
        .maxByOrNull { it.name.removePrefix("android-").substringBefore('.').toIntOrNull() ?: 0 }
        ?: throw GradleException("No platform is installed in the Android SDK at $sdk")
    return File(platform, "android.jar")
}

val androidJarFile = layout.file(provider { findAndroidJar() })

val parsersBundleDex by tasks.registering(DexBundleTask::class) {
    tools.from(r8)
    classes.set(parsersBundleJar.flatMap { it.archiveFile })
    libraries.from(contractJar)
    libraries.from(configurations.runtimeClasspath.map { classpath -> classpath.filter { it.name.endsWith(".jar") } })
    androidJar.set(androidJarFile)
    minApi.set(bundleMinSdk)
    outputDir.set(layout.buildDirectory.dir("parsersBundle/dex"))
}

// What applications made for this version of the contract are known to have: the libraries at the versions written in
// its record, however far the ones compiled with here have moved on since
val bundlePlatform = provider {
    val record = ParsersApiTask.recordFile(parsersApiRecords.asFile, parsersApiVersion)
    val libraries = if (record.exists()) ParsersApiTask.librariesOf(record) else emptyList()
    configurations.detachedConfiguration(*libraries.map { dependencies.create(it) }.toTypedArray())
        .apply { isTransitive = false }
        .files
}

val checkParsersBundle by tasks.registering(CheckBundleLinksTask::class) {
    group = "verification"
    description = "Fails if the parsers refer to anything an application made for this contract does not have."
    dependsOn(checkParsersApi)
    tools.from(r8)
    dexDir.set(parsersBundleDex.flatMap { it.outputDir })
    contract.set(contractJar.flatMap { it.archiveFile })
    libraries.from(bundlePlatform)
    androidJar.set(androidJarFile)
    bundleClasses.set(parsersBundleJar.flatMap { it.archiveFile })
    minApi.set(bundleMinSdk)
    // Not every SDK has it: the task says so itself when it is not there
    apiVersions.set(layout.file(provider { File(findAndroidJar().parentFile, "data/api-versions.xml").takeIf { it.exists() } }))
    report.set(layout.buildDirectory.file("parsersBundle/links.txt"))
    uses.set(layout.buildDirectory.file("parsersBundle/uses.pro"))
    platformUses.set(layout.buildDirectory.file("parsersBundle/platform-uses.pro"))
    platformReport.set(layout.buildDirectory.file("parsersBundle/platform-level.txt"))
}

val parsersBundleZip by tasks.registering(Zip::class) {
    dependsOn(checkParsersBundle)
    from(parsersBundleDex.flatMap { it.outputDir }) {
        include("*.dex")
    }
    archiveFileName.set("parsers-v$parsersApiVersion-$parsersVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("parsersBundle/out"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register<SignBundleTask>("parsersBundle") {
    group = "build"
    description = "Makes the two files to publish for applications to take newer parsers from, checked and signed."
    archive.set(parsersBundleZip.flatMap { it.archiveFile })
    dexDir.set(parsersBundleDex.flatMap { it.outputDir })
    apiVersion.set(parsersApiVersion)
    bundleVersion.set(parsersVersion.toLong())
    revision.set(parsersRevision)
    minSdk.set(bundleMinSdk)
    privateKey.set(
        provider {
            (findProperty("parsersSigningKeyFile") as String?)?.let { file(it).readText() }
                ?: System.getenv("PARSERS_SIGNING_KEY")
        },
    )
    publicKey.set(providers.gradleProperty("parsersPublicKey"))
    manifest.set(layout.buildDirectory.file("parsersBundle/out/parsers-v$parsersApiVersion.json"))
}

tasks.register<GenerateSigningKeyTask>("generateParsersSigningKey") {
    group = "build setup"
    description = "Makes the pair of keys parsers are signed and checked with. Needs -PkeyFile=<path>."
    keyFile.set(providers.gradleProperty("keyFile"))
}

// ---- Live tests of the sources a pull request changes: see docs/PULL_REQUESTS.md ----

val parsersTestsDir = layout.buildDirectory.dir("parsersTests")

val parsersBundleUrl: Provider<String> = providers.gradleProperty("parsersBundleUrl").orElse(
    providers.environmentVariable("GITHUB_REPOSITORY")
        .orElse("Kotatsu-Redo/kotatsu-parsers-redo")
        .map { "https://github.com/$it/releases/download/parsers-bundle" },
)

tasks.register<SelectParserTestsTask>("selectParserTests") {
    group = "verification"
    description = "Chooses the sources to run the live tests for. -PcomparedWith=<commit> compares with that commit: " +
        "for a pull request. Without it the comparison is with the last publication, and -Psince=<commit> can only " +
        "reach further back than that."
    dependsOn("kspKotlin")
    since.set(providers.gradleProperty("since"))
    comparedWith.set(providers.gradleProperty("comparedWith"))
    bundleUrl.set(parsersBundleUrl)
    apiVersion.set(parsersApiVersion)
    publicKey.set(providers.gradleProperty("parsersPublicKey"))
    gitDir.set(providers.gradleProperty("parsersGitDir").orElse(projectDir.absolutePath))
    projectPath.set(projectDir.absolutePath)
    maxSampledSources.set(providers.gradleProperty("maxSampledSources").map { it.toInt() }.orElse(30))
    maxSampledPerFile.set(providers.gradleProperty("maxSampledPerFile").map { it.toInt() }.orElse(10))
    sourcesMap.set(sourcesMapFile)
    outputDir.set(parsersTestsDir)
}

tasks.register<SummarizeParserTestsTask>("summarizeParserTests") {
    group = "verification"
    description = "Says how the live tests of the selected sources went, and whether a person has to look."
    testsDir.set(parsersTestsDir)
    resultsDir.set(
        layout.projectDirectory.dir(providers.gradleProperty("testResultsDir"))
            .orElse(layout.buildDirectory.dir("test-results/test")),
    )
    outputDir.set(parsersTestsDir)
}

tasks.register<CheckNewerThanPublishedTask>("checkNewerThanPublished") {
    group = "verification"
    description = "Fails if the last publication is not older than these parsers: an older build is not published over it."
    bundleUrl.set(parsersBundleUrl)
    apiVersion.set(parsersApiVersion)
    publicKey.set(providers.gradleProperty("parsersPublicKey"))
    buildVersion.set(parsersVersion.toLong())
    buildRevision.set(parsersRevision)
}
