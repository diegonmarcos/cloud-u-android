pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Camera"
include(":app")

// Task #461 — the ONE shared image-scan engine (ZXing barcode decode + ML Kit
// OCR + typed-payload parsing), shared BY REFERENCE from ab_cloud-libs-shared.
// Cloud-drive and cloud-media-center compile in the SAME directory; this app
// now does too — one engine, no private copy to drift (#170/#261). projectDir
// is mandatory: the default would resolve to ./libs/ml-l-image-mlkit, which
// does not exist in this app. Same AGP-9 consumption path media-center uses.
include(":libs:ml-l-image-mlkit")
project(":libs:ml-l-image-mlkit").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-image-mlkit")
// Gradle 9 fails outright on a project directory that does not exist, and the
// implicit ':libs' container resolves to <root>/libs which this app does not
// have. Point it at the shared root, exactly as media-center does; the leaf
// above already names its own projectDir, so this only gives the container
// something real to point at.
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")
