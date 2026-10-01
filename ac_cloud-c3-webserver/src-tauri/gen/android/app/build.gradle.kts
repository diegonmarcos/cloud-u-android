import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

// cloud-c3-webserver: this is the Tauri 2.12 template's app module. Every
// value the fleet owns — identity, SDK levels, version, launcher label — is
// read from the app's ONE build.json (four levels up: gen/android/app ->
// ac_cloud-c3-webserver) instead of from the template's literals and
// tauri.properties. Anything not commented here is the template verbatim.
@Suppress("UNCHECKED_CAST")
val buildJson = groovy.json.JsonSlurper().parse(file("../../../../build.json")) as Map<String, Any?>
@Suppress("UNCHECKED_CAST")
val androidJson = buildJson["android"] as Map<String, Any?>

// versionCode: minutes since 2026-01-01 UTC over a 3,000,000 base, the fleet's
// encoding, so it only ever moves forward whichever workflow built it.
// COMMS_BUILD_TIMESTAMP (yyyyMMdd.HHmmss) wins when CI sets it. (java.time is
// imported above: inside a build script a bare `java.` resolves to the
// project's `java` extension, not the package.)
val fleetVersionCode: Int = run {
    val base = LocalDateTime.of(2026, 1, 1, 0, 0)
    var built = LocalDateTime.now(ZoneOffset.UTC)
    val stamp = System.getenv("COMMS_BUILD_TIMESTAMP")
    if (!stamp.isNullOrBlank()) {
        runCatching {
            built = LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
        }
    }
    val mins = Duration.between(base, built).toMinutes()
    if (mins > 0) (3000000L + mins).toInt() else 3000000
}

android {
    compileSdk = (androidJson["compile_sdk"] as Number).toInt()
    namespace = androidJson["application_id"] as String
    defaultConfig {
        // "true" in every build type, not only debug: the webview's one
        // destination is the in-process server on http://127.0.0.1.
        manifestPlaceholders["usesCleartextTraffic"] = "true"
        applicationId = androidJson["application_id"] as String
        minSdk = (androidJson["min_sdk"] as Number).toInt()
        targetSdk = (androidJson["target_sdk"] as Number).toInt()
        versionCode = fleetVersionCode
        versionName = androidJson["version_name"] as String
        // Launcher label = build.json::name, the application's one name (#351).
        resValue("string", "app_name", buildJson["name"] as String)
        resValue("string", "main_activity_title", buildJson["name"] as String)
    }
    // English is the fleet's base language: drops the ~80 locales appcompat
    // and material would otherwise merge in.
    androidResources {
        localeFilters += listOf("en")
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            optimization {
               enable = true
            }
            proguardFiles(
                *fileTree(".") {
                  include("**/*.pro")
                  exclude("build/**")
                }.files.toTypedArray()
            )
        }
    }
    // The Rust library is the bulk of the APK: stored compressed it is the
    // download size budget, stored uncompressed (the default at minSdk >= 23)
    // it would be its full unpacked size.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    lint {
        // targetSdk 28 is the storage model, see build.json::android._doc_target_sdk.
        disable.add("ExpiredTargetSdkVersion")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        buildConfig = true
        resValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

rust {
    rootDirRel = "../../.."
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = file("tauri.build.gradle.kts"))
