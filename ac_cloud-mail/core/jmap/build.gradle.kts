import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Pin Kotlin's JVM target to 17 too. Without this, the Kotlin compiler follows the
// JDK running Gradle, so on a JDK 21 buildserver (F-Droid) compileKotlin targets 21
// while compileJava stays 17 -> "Inconsistent JVM-target compatibility" build failure.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Exposed in public API (JmapSession.capabilities uses JsonObject), so `api`.
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
