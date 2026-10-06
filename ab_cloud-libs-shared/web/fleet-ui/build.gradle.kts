import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// fleet-ui: the wasm compile of every shared lib's pure-Compose sources. The lib list is data:
// ab_cloud-libs-shared/web/web.json::libs. Each entry <lib> contributes
// ../libs/<lib>/src/commonMain/kotlin to wasmJsMain, the same directory the Android lib
// build adds with `android.sourceSets.main.kotlin.srcDirs += 'src/commonMain/kotlin'`.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "com.diegonmarcos.fleet"

@Suppress("UNCHECKED_CAST")
val webLibs: List<String> = (JsonSlurper().parse(file("../web.json")) as Map<String, Any?>)["libs"] as List<String>

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        // The libs' commonMain is compiled as part of wasmJsMain, not commonMain: the libs are Android
        // libraries, not multiplatform ones, so their drawing code reaches the platform through
        // functions the platform defines (libs/<lib>/src/commonMain/.../NavPlatform.kt names them,
        // src/wasmJsMain/ here defines them), and common code may not see a platform declaration.
        wasmJsMain {
            webLibs.forEach { kotlin.srcDir("../../libs/$it/src/commonMain/kotlin") }
        }
        commonMain {
            dependencies {
                api(libs.compose.runtime)
                api(libs.compose.foundation)
                api(compose.material3)
                api(libs.compose.ui)
                api(libs.compose.resources)
            }
        }
    }
}
