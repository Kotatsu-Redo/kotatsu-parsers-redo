plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.korte)
    implementation(libs.simplexml)
    implementation("org.ow2.asm:asm:9.9.1")
    implementation(libs.kotlinx.coroutines.core)
}
