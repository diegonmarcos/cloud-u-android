import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Cloud Office, web edition: one dense page of mock data, drawn with the shared ui-kit and compiled to Kotlin/Wasm.
// `./gradlew wasmJsBrowserDistribution` writes build/dist/wasmJs/productionExecutable (what
// ship-cloud-office-wasm zips and releases).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("cloud-office")
        browser {
            // No wasm browser tests: the page is asserted by test/test-office-web.sh against the
            // dist (Karma's npm fork comes from a GitHub tarball a locked-down network refuses).
            testTask { enabled = false }
            commonWebpackConfig {
                outputFileName = "cloud-office.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("com.diegonmarcos.fleet:fleet-ui")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            implementation(libs.compose.ui)
        }
    }
}

