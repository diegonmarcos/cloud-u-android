// #876 wasm root of Cloud Store: ships alone from this directory (see ../build.json::web).
// The shared libs' pure-Compose sources arrive through the ab_cloud-libs-shared/web composite
// (module fleet-ui); the version catalog is that root's, so Kotlin/CMP are pinned once.
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
    versionCatalogs {
        create("libs") {
            from(files("../../ab_cloud-libs-shared/web/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "cloud-store-web"
includeBuild("../../ab_cloud-libs-shared/web")
