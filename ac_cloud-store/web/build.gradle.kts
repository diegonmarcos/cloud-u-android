import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Cloud Store, web edition: the constellation fleet as a dense tile grid, drawn with the shared
// ui-kit and compiled to Kotlin/Wasm. `./gradlew wasmJsBrowserDistribution` writes
// build/dist/wasmJs/productionExecutable (what ship-cloud-store-wasm zips and releases).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// The fleet manifest is the one data file the Android store and this page both read; it is
// copied (never forked) into the compose resources at build time. So is the store's nav
// declaration: build.json::ui (bottom_nav, sections, default_section) is what the Android shell
// bakes into BuildConfig, and the island and the tab strips here read the same three fields.
val fleetResources = layout.buildDirectory.dir("generated/fleet-res")
val navJson = layout.buildDirectory.file("generated/store-nav/store-nav.json")
val genNav by tasks.registering {
    val source = rootProject.file("../build.json")
    inputs.file(source)
    outputs.file(navJson)
    doLast {
        val ui = (JsonSlurper().parse(source) as Map<*, *>)["ui"] as Map<*, *>
        val out = linkedMapOf(
            "sections" to ui["sections"],
            "bottom_nav" to ui["bottom_nav"],
            "default_section" to ui["default_section"],
        )
        navJson.get().asFile.apply { parentFile.mkdirs() }.writeText(JsonOutput.toJson(out))
    }
}
val copyFleet by tasks.registering(Copy::class) {
    from(rootProject.file("../../aa_cloud-superapp/data/constellation-fleet.json"))
    from(genNav)
    into(fleetResources.map { it.dir("files") })
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("cloud-store")
        browser {
            // No wasm browser tests: the page is asserted by test/test-store-web.sh against the
            // dist, and Karma's npm fork is fetched from a GitHub tarball (codeload) that a
            // locked-down network refuses, which would fail the whole tooling setup.
            testTask { enabled = false }
            commonWebpackConfig {
                outputFileName = "cloud-store.js"
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
            implementation(libs.compose.resources)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

compose.resources {
    packageOfResClass = "com.diegonmarcos.cloudstore.web.generated"
    customDirectory(
        sourceSetName = "wasmJsMain",
        directoryProvider = copyFleet.map { fleetResources.get() },
    )
}
