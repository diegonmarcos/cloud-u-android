rootProject.name = "Phone"
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}
include(":app")

// Shared BY REFERENCE from ab_cloud-libs-shared/ — projectDir points at the one
// copy rather than a vendored duplicate, so the module can never drift per app.
// ':libs:analytics' implicitly declares an intermediate ':libs' project, whose
// default projectDir is <root>/libs. This app has no local libs/, and Gradle 9
// fails the build outright on a project directory that does not exist. Map
// ':libs' onto the shared libs root so the container resolves to a real
// directory. Apps that DO have their own libs/ (nav, media-center) must not do
// this — it would repoint their local modules.
include(":libs:analytics")
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")
project(":libs:analytics").projectDir = file("../ab_cloud-libs-shared/libs/analytics")

// Fleet mesh membership: libs:core manifest-merges the CONSTELLATION_DATA
// signature permission and, through libs:devtools, FleetTokenProvider +
// FleetMemberReceiver + the MESH_MEMBER <queries> intent — without them the
// SuperApp and every sibling cannot see or reach this app. devtools is not
// optional: core declares `api project(':libs:devtools')`, which resolves
// against THIS settings file. The ':libs' mapping above already covers both.
include(":libs:core")
project(":libs:core").projectDir = file("../ab_cloud-libs-shared/libs/core")
include(":libs:devtools")
project(":libs:devtools").projectDir = file("../ab_cloud-libs-shared/libs/devtools")
