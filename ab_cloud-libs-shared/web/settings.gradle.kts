// #876 wasm web root: a Kotlin Multiplatform composite root that compiles the pure-Compose
// sources of the shared Android libs (src/commonMain/kotlin) to Kotlin/Wasm. Apps consume it
// through includeBuild("../../ab_cloud-libs-shared/web") from their own <app>/web root.
// The lib list is NOT here: fleet-ui/build.gradle.kts reads ../web.json::libs.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Kotlin/Wasm toolchain downloads. Declared HERE because PREFER_SETTINGS ignores the
        // repositories the Kotlin plugin adds to the project, which left Node unresolvable.
        ivy {
            name = "Node.js distributions"
            url = uri("https://nodejs.org/dist")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn distributions"
            url = uri("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy {
            name = "Binaryen distributions"
            url = uri("https://github.com/WebAssembly/binaryen/releases/download")
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "libs-shared-web"
include(":fleet-ui")
