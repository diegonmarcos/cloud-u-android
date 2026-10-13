import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// CI runs `testReleaseUnitTest` (build.json tests.unit.task), a task only the Android modules have,
// so this plain-JVM module's tests never ran — which is how a multipart/alternative test sat red,
// unseen, while the send path still wrote one text/html part. The alias puts them in that run.
tasks.register("testReleaseUnitTest") { dependsOn(tasks.named("test")) }
