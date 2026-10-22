import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

configure<LibraryExtension> {
    namespace = "com.bitwarden.network"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        minSdk {
            version = release(libs.versions.minSdk.get().toInt())
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    // #1093 the fleet endpoint fallback (mesh `<service>.app` first, then public + bearer) is ONE source tree shared with
    // Cloud Mail and Cloud Account (ab_cloud-libs-shared/libs/fleet-endpoints, plain JVM Kotlin). It is compiled here BY
    // REFERENCE, against this tree's own OkHttp and coroutines, so no second copy exists and the strict dependency
    // locks of this build see no new module.
    sourceSets["main"].kotlin.directories.add("../../ab_cloud-libs-shared/libs/fleet-endpoints/src/main/kotlin")
    compileOptions {
        sourceCompatibility(libs.versions.jvmTarget.get())
        targetCompatibility(libs.versions.jvmTarget.get())
    }
    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

dependencies {
    implementation(project(":annotation"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core) // StateFlow of the shared fleet-endpoints router
    implementation(libs.kotlinx.serialization)
    implementation(platform(libs.square.okhttp.bom))
    implementation(libs.square.okhttp)
    implementation(libs.square.okhttp.logging)
    implementation(platform(libs.square.retrofit.bom))
    implementation(libs.square.retrofit)
    implementation(libs.square.retrofit.kotlinx.serialization)
    implementation(libs.timber)

    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.vintage)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk.mockk)
    testImplementation(platform(libs.square.okhttp.bom))
    testImplementation(libs.square.okhttp.mockwebserver)
    testImplementation(libs.square.turbine)

    testFixturesImplementation(project(":core"))
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesRuntimeOnly(libs.junit.platform.launcher)
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.junit.vintage)
    testFixturesImplementation(libs.kotlinx.serialization)
    testFixturesImplementation(libs.mockk.mockk)
    testFixturesImplementation(platform(libs.square.okhttp.bom))
    testFixturesImplementation(libs.square.okhttp)
    testFixturesImplementation(libs.square.okhttp.mockwebserver)
    testFixturesImplementation(platform(libs.square.retrofit.bom))
    testFixturesImplementation(libs.square.retrofit)
    testFixturesImplementation(libs.square.retrofit.kotlinx.serialization)
}
