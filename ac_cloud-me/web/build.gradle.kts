import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Cloud Me, web edition: the app's shell (the fleet island and tab strips from build.json::ui)
// over a working Standard keypad, drawn with the shared ui-kit and compiled to Kotlin/Wasm.
// `./gradlew wasmJsBrowserDistribution` writes build/dist/wasmJs/productionExecutable (what
// ship-cloud-me-wasm zips and releases).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// build.json::ui (bottom_nav, sections, default_section) is what the Android shell bakes into
// BuildConfig; the island and the tab strips here read the same three fields, copied (never forked)
// into the compose resources at build time.
val navResources = layout.buildDirectory.dir("generated/me-res")
val navJson = layout.buildDirectory.file("generated/me-nav/me-nav.json")
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
val copyNav by tasks.registering(Copy::class) {
    from(genNav)
    into(navResources.map { it.dir("files") })
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("cloud-me")
        browser {
            // No wasm browser tests: the page is asserted by test/test-me-web.sh against the
            // dist (Karma's npm fork comes from a GitHub tarball a locked-down network refuses).
            testTask { enabled = false }
            commonWebpackConfig {
                outputFileName = "cloud-me.js"
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
    packageOfResClass = "com.diegonmarcos.cloudme.web.generated"
    customDirectory(
        sourceSetName = "wasmJsMain",
        directoryProvider = copyNav.map { navResources.get() },
    )
}
