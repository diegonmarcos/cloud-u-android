import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Side-by-side test app: build any variant with -PtestApp to get `app.sterna.test`, a separate
// package that installs NEXT TO the production app instead of overwriting it (own data, own
// launcher entry). On-device checks go through it, so production stays the Obtainium-tracked
// install and version codes are never inflated just to reinstall.
//
// The property is absent by default and everything below is gated on it, so the production
// recipe is untouched: same applicationId, same versionName, same resources, same merged
// manifest. F-Droid rebuilds this repo without the property and must get the same bytes.
val testApp = providers.gradleProperty("testApp").isPresent

// The package id comes from build.json, never a literal here. cloud-mail-engine.sh's
// identity-assert step compares the BUILT APK's package against
// build.json::forks.mail.app_id, so a second copy in this file could not make the
// two agree — it could only turn a rename into a ship that fails after the build.
@Suppress("UNCHECKED_CAST")
val commsApplicationId: String = (groovy.json.JsonSlurper()
    .parse(rootProject.file("build.json")) as Map<String, Any>)
    .let { it["forks"] as Map<String, Any> }
    .let { it["mail"] as Map<String, Any> }
    .let { it["app_id"] as String }

// Minutes since 2026-01-01 plus a 3,000,000 base, from the stamp CI already bakes
// into the release tag. Monotonic, independent of how the tree was assembled, and
// well inside the 2^31 ceiling for centuries. Falls back to upstream's own number
// when the stamp is absent or unparseable (a local build, where CI substitutes the
// literal "dev"), so `./gradlew assembleRelease` still works off a bare checkout.
fun commsVersionCode(upstreamVersionCode: Int): Int {
    val stamp = providers.gradleProperty("COMMS_BUILD_TIMESTAMP").orNull
        ?: System.getenv("COMMS_BUILD_TIMESTAMP")
        ?: return upstreamVersionCode
    val built = try {
        java.time.LocalDateTime.parse(
            stamp,
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"),
        )
    } catch (parseFailed: java.time.format.DateTimeParseException) {
        return upstreamVersionCode
    }
    val epoch = java.time.LocalDateTime.of(2026, 1, 1, 0, 0)
    val minutes = java.time.Duration.between(epoch, built).toMinutes()
    return if (minutes > 0) (3_000_000L + minutes).toInt() else upstreamVersionCode
}

android {
    namespace = "app.sterna"
    compileSdk = 36

    defaultConfig {
        // Our package id, not upstream's: the constellation already has installs of
        // com.diegonmarcos.comms.mail in the field and this app replaces what is on
        // them. `namespace` above stays app.sterna — it is the R/BuildConfig package
        // that every source file and every proguard -keep rule names, and renaming it
        // would be editing upstream code rather than rebranding a build.
        applicationId = commsApplicationId
        minSdk = 26
        targetSdk = 36
        // Upstream's 174 cannot be used as-is. The FairEmail app this replaces shipped
        // versionCode ≈ 3.35M (minutes since 2026-01-01 + 3,000,000), so Android would
        // reject 174 on every existing install as a downgrade and the replacement would
        // silently never arrive. Same monotonic clock as before, so the sequence the
        // field has already seen keeps going up; CI passes COMMS_BUILD_TIMESTAMP as both
        // a -P property and an env var, and a dev build with neither falls back to
        // upstream's own code.
        versionCode = commsVersionCode(174)
        versionName = "1.5.4"
        // Shown on the Settings About row. Bump alongside versionCode/versionName at each
        // release (a static literal, so builds stay reproducible — never derive from clock).
        buildConfigField("String", "VERSION_DATE", "\"2026-09-06\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The launcher/settings/notification label. Substituted verbatim into the manifest,
        // so without -PtestApp the merged manifest still reads android:label="@string/app_name"
        // (localised as before).
        manifestPlaceholders["appLabel"] = "@string/app_name"
        // The home-screen widget's name in the system widget picker. Same trick and same reason as
        // appLabel above: without -PtestApp the merged manifest still reads
        // android:label="@string/widget_unread_title" (localised as before), so the production
        // build is byte-for-byte what it was. With it, the picker lists two distinguishable
        // entries instead of two identical ones, which is what makes a bench pass playable.
        manifestPlaceholders["widgetLabel"] = "@string/widget_unread_title"
        // The SECOND widget's picker entry (the latest-messages list). Its own placeholder and
        // its own name: one placeholder for both would give the picker two entries with the same
        // label, and the whole reason the label is a placeholder is that a bench pass has to tell
        // the test install from the production one.
        manifestPlaceholders["latestWidgetLabel"] = "@string/widget_latest_title"
        if (testApp) {
            applicationIdSuffix = ".test"
            manifestPlaceholders["appLabel"] = "Cloud Mail (test)"
            manifestPlaceholders["widgetLabel"] = "Unread count (test)"
            manifestPlaceholders["latestWidgetLabel"] = "Latest messages (test)"
            // About row reads e.g. "1.3.13-test": tells the two apart from the inside.
            // A suffix only — versionName/versionCode themselves are never bumped for a test.
            versionNameSuffix = "-test"
        }
    }

    // Distinct launcher icon for the test app: one drawable overriding the adaptive icon's
    // background layer (the tern silhouette and monochrome layer are untouched). Registered on
    // the build-type source sets only under -PtestApp — build-type resources win over main —
    // so the production build never sees src/testApp/res at all.
    // Test-only sources: src/testApp/kotlin (bench entry points driven over adb) and the
    // manifest that declares them. Registered on the build-type source sets INSIDE this gate
    // only: without -PtestApp neither directory is part of any variant, so the production
    // build compiles no extra class and its merged manifest gains no component — which is what
    // keeps F-Droid's rebuild byte-identical. Never move these lines out of the gate.
    // ⚠ Second failure mode, latent: manifest.srcFile REPLACES the build type's manifest, it does
    // not merge with it. There is no src/debug/AndroidManifest.xml today, so nothing is lost; the
    // day someone adds one, every -PtestApp build would drop it without a word. Whoever adds it
    // must merge the two by hand (or move the bench receiver's declaration into it).
    // src/benchShared/kotlin holds the bench decisions that are PLAIN KOTLIN (no Android import),
    // so that a unit test can execute them: src/testApp/kotlin above is compiled only under
    // -PtestApp, and the test suite does not pass the property, so nothing in it can be run by a
    // test. With the property it rides along with the bench sources on the app variants; without
    // it, it is compiled into the unit tests instead. ONE copy on the test compile classpath in
    // either case, never two — and in neither case does it reach src/main, so the published APK
    // stays bit-for-bit what it was.
    if (testApp) {
        sourceSets.getByName("debug").res.srcDir("src/testApp/res")
        sourceSets.getByName("release").res.srcDir("src/testApp/res")
        sourceSets.getByName("debug").kotlin.srcDir("src/testApp/kotlin")
        sourceSets.getByName("release").kotlin.srcDir("src/testApp/kotlin")
        sourceSets.getByName("debug").kotlin.srcDir("src/benchShared/kotlin")
        sourceSets.getByName("release").kotlin.srcDir("src/benchShared/kotlin")
        sourceSets.getByName("debug").manifest.srcFile("src/testApp/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/testApp/AndroidManifest.xml")
    } else {
        sourceSets.getByName("test").kotlin.srcDir("src/benchShared/kotlin")
    }

    // Reproducible builds: the compiled ART baseline profile (assets/dexopt/baseline.prof)
    // is not byte-identical across build environments even when classes.dex is — F-Droid's
    // rebuild of 1.1.2 differed only there. Don't package it at all, per
    // https://f-droid.org/docs/Reproducible_Builds/. Release APKs must also be built from a
    // clean tree (clean --no-build-cache): incremental Kotlin/Compose compilation emits
    // slightly different bytecode than a from-scratch build.
    tasks.whenTaskAdded {
        if (name.contains("ArtProfile")) enabled = false
    }

    // No Google dependency-metadata blob in the APK/AAB (required by IzzyOnDroid/F-Droid).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release signing. If a git-ignored keystore.properties is present (real release
    // key), the release build is signed with it; otherwise it falls back to the local
    // debug keystore so debug builds and CI still work. keystore.properties holds:
    //   storeFile=/absolute/path/to/sterna-release.jks
    //   storePassword=...
    //   keyAlias=sterna
    //   keyPassword=...
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    val hasReleaseKey = keystoreProps.getProperty("storeFile") != null
    val debugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        if (debugKeystore.exists()) {
            create("debugSigned") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // R8 minify + resource shrink, unless built with -PnoR8 (faster test builds).
            val noR8 = providers.gradleProperty("noR8").isPresent
            isMinifyEnabled = !noR8
            isShrinkResources = !noR8
            // Reproducible builds: don't embed META-INF/version-control-info.textproto —
            // its content depends on whether AGP can read git in the build environment
            // (F-Droid's rebuild of 1.1.3 differed only there: NO_VALID_GIT_FOUND vs
            // our embedded revision).
            vcsInfo {
                include = false
            }
            // Prefer the real release key; fall back to the debug key when absent.
            // Keep on ONE line: F-Droid's reproducible-build signing strip is line-based,
            // so a multi-line `?:` would be left orphaned and break the build.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.findByName("debugSigned")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:jmap"))
    implementation(project(":core:data"))
    implementation(project(":libs:openpgp-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.unifiedpush.connector)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.paging.compose)
    // Installs the bundled baseline profile (src/main/baseline-prof.txt) on first launch so
    // ART AOT-compiles the hot startup/scroll paths. Needed because sideloaded/F-Droid installs
    // don't run install-time dexopt from the embedded profile on all ROMs (verified on the S7).
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Virtual time + a background scope, to drive the unfolded conversations' live member stream
    // (a flow of flows) without sleeping on a real clock.
    testImplementation(libs.kotlinx.coroutines.test)
}
