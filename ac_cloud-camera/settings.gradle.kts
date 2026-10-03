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
// is mandatory: the default would resolve to ./libs/ml-l-image, which
// does not exist in this app. Same AGP-9 consumption path media-center uses.
include(":libs:ml-l-image")
project(":libs:ml-l-image").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-image")
// #798 sound identification's contract, by reference the same way (the engine is a Store lib).
include(":libs:ml-l-sound")
project(":libs:ml-l-sound").projectDir = file("../ab_cloud-libs-shared/libs/ml-l-sound")

// Fleet mesh membership: libs:core manifest-merges the CONSTELLATION_DATA
// signature permission and, through libs:devtools, FleetTokenProvider +
// FleetMemberReceiver + the MESH_MEMBER <queries> intent — without them the
// SuperApp and every sibling cannot see or reach this app. devtools is not
// optional: core declares `api project(':libs:devtools')`, which resolves
// against THIS settings file. Same pair, same reason as media-center.
include(":libs:core")
project(":libs:core").projectDir = file("../ab_cloud-libs-shared/libs/core")
include(":libs:devtools")
project(":libs:devtools").projectDir = file("../ab_cloud-libs-shared/libs/devtools")
// Gradle 9 fails outright on a project directory that does not exist, and the
// implicit ':libs' container resolves to <root>/libs which this app does not
// have. Point it at the shared root, exactly as media-center does; the leaf
// above already names its own projectDir, so this only gives the container
// something real to point at.
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")
