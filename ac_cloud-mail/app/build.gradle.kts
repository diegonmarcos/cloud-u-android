import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
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

// cloud-mail's OWN text-tool registry: build.json::mail_ai — providers, model lists, the
// Enhance prompts, the Resume prompts, the translation defaults.
//
// READ FROM THIS APP'S build.json AND NOWHERE ELSE, and that is the whole point. It began
// as a copy of ab_cloud-libs-shared/build.json::keyboard_ai and is now independent of it:
// an edit to the keyboard's registry cannot reach this build, and an edit here cannot reach
// the keyboard's. Falling back to the shared file when this key is missing would quietly
// re-link the two apps the first time someone deleted the block, so a missing mail_ai is a
// build failure with a sentence explaining what to restore.
@Suppress("UNCHECKED_CAST")
val mailAiRouting: Map<String, Any> = ((groovy.json.JsonSlurper()
    .parse(rootProject.file("build.json")) as Map<String, Any>)["mail_ai"] as Map<String, Any>?)
    ?: error(
        "build.json::mail_ai is missing. It is cloud-mail's own copy of the text-tool " +
            "registry (AI routing, Enhance, Resume, Translation) and this app reads no other. " +
            "Restore it from git rather than pointing this build at keyboard_ai — sharing that " +
            "block is exactly what made mail's Text settings uneditable."
    )
// THIS APP'S ENTRY in the constellation fleet manifest, and only this app's, baked so
// Configs ▸ Update can say where the installed build came from.
//
// :libs:updater already bakes a fleet manifest, from `${rootDir}/data/constellation-fleet.json`
// — the CONSUMING app's own data/ dir. ac_cloud-mail has no data/ dir, so that file does not
// exist here and the library's CONSTELLATION_FLEET_B64 is the EMPTY STRING in every cloud-mail
// build ever shipped. A screen reading it would have found no entry for itself and drawn no
// links at all, which is a blank page that looks exactly like a working one.
//
// ONE ENTRY, NOT SIXTY, and that is deliberate twice over. Baking the whole manifest would put
// 60 other apps' install addresses inside a mail APK and hand Fleet.installAll a fleet to walk;
// build.json::release.auto_update says it in as many words — "Mail updates only itself … it is
// not a fleet host and must not behave like one". It would also be a second copy of a file that
// already exists once, free to drift.
//
// Selected by PACKAGE ID, against the same commsApplicationId the identity-assert step checks
// the built APK with, so a renamed entry cannot hand this app someone else's release URL. A
// missing file or a missing entry FAILS THE BUILD with a sentence: a mail APK that cannot say
// where it came from is the defect this block exists to remove, not something to ship quietly.
@Suppress("UNCHECKED_CAST")
val mailFleetB64: String = run {
    val manifest = rootProject.file("../aa_cloud-superapp/data/constellation-fleet.json")
    if (!manifest.isFile) {
        error(
            "constellation-fleet.json is not at ${manifest.path}. It is the fleet's single " +
                "source of truth for this app's release, repository and package addresses, and " +
                "Configs ▸ Update reads nothing else. It lives in the sibling aa_cloud-superapp " +
                "tree, which this build already needs for :libs:updater — a checkout without it " +
                "cannot build cloud-mail at all."
        )
    }
    val apps = (groovy.json.JsonSlurper().parse(manifest) as Map<String, Any>)["apps"]
        as List<Map<String, Any>>
    val entry = apps.firstOrNull { it["package"] == commsApplicationId }
        ?: error(
            "constellation-fleet.json has no entry whose \"package\" is $commsApplicationId. " +
                "Configs ▸ Update takes every address it shows from that entry and invents " +
                "none, so without it the page has nothing true to display. Add the entry rather " +
                "than writing the URLs into this build file."
        )
    groovy.json.JsonOutput.toJson(mapOf("apps" to listOf(entry)))
        .toByteArray(Charsets.UTF_8)
        .let { Base64.getEncoder().encodeToString(it) }
}

val mailAiRoutingB64: String = groovy.json.JsonOutput.toJson(mailAiRouting)
    .toByteArray(Charsets.UTF_8)
    // Base64, NOT java.util.Base64 — imported above and referred to by its simple
    // name, exactly like Properties and the java.time trio. The comment under
    // commsVersionCode already spells out why, and this line is what it was
    // warning about: inside a Kotlin DSL build script the leftmost `java` resolves
    // to the Android plugin's generated `val Project.java: JavaPluginExtension`
    // accessor, not to the package, so the qualified form cannot compile.
    .let { Base64.getEncoder().encodeToString(it) }

// Minutes since 2026-01-01 plus a 3,000,000 base, from the stamp CI already bakes
// into the release tag. Monotonic, independent of how the tree was assembled, and
// well inside the 2^31 ceiling for centuries. Falls back to upstream's own number
// when the stamp is absent or unparseable (a local build, where CI substitutes the
// literal "dev"), so `./gradlew assembleRelease` still works off a bare checkout.
//
// Everything below refers to LocalDateTime/Duration/DateTimeFormatter by their imported
// simple names, never as java.time.X. Inside a Kotlin DSL build script the identifier
// `java` is already taken: the Android plugin applies java-base, so the generated
// accessor `val Project.java: JavaPluginExtension` is in scope and wins the leftmost
// segment of an expression. `java.time.Duration` then parses as "read property `time`
// off the JavaPluginExtension" and cannot resolve. The imports here are a type-only
// context where no accessor competes, so they bind to the real package. Do not
// re-qualify these — it puts run 34325048405 back.
fun commsVersionCode(upstreamVersionCode: Int): Int {
    val stamp = providers.gradleProperty("COMMS_BUILD_TIMESTAMP").orNull
        ?: System.getenv("COMMS_BUILD_TIMESTAMP")
        ?: return upstreamVersionCode
    val built = try {
        LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
    } catch (parseFailed: DateTimeParseException) {
        return upstreamVersionCode
    }
    val epoch = LocalDateTime.of(2026, 1, 1, 0, 0)
    val minutes = Duration.between(epoch, built).toMinutes()
    return if (minutes > 0) (3_000_000L + minutes).toInt() else upstreamVersionCode
}

android {
    namespace = "app.sterna"
    compileSdk = 36

    defaultConfig {
        // FLEET RULE (#299): English is the fleet's base language. Unlisted locales are dropped
        // from resources.arsc, so a Spanish phone resolves the English strings in values/.
        resourceConfigurations += listOf("en")
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
        // build.json::mail_ai — read by app.sterna.ui.text.MailAiRegistry. THIS APP'S OWN
        // registry, not the keyboard's: base64 for the same reason the keyboard bakes its
        // copy that way, because the prompts carry quotes and newlines that a plain
        // buildConfigField string would not survive.
        buildConfigField("String", "MAIL_AI_ROUTING_B64", "\"$mailAiRoutingB64\"")
        // constellation-fleet.json's `mail` entry, wrapped as {"apps":[…]} so Fleet.parse reads
        // it unchanged. Base64 for the same reason as the line above: the entry carries quotes.
        buildConfigField("String", "MAIL_FLEET_B64", "\"$mailFleetB64\"")
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

    // Enhance and Translate. NOT the engines - this is the binder they are reached
    // across; both live in the Cloud Keyboard, along with the routing settings and the
    // provider key. Taking :libs:keyboard instead would pull an IME with an ndk-build
    // native decoder into an email client, and copying the engine would put a second
    // API key in a second store.
    implementation(project(":libs:text-tools"))

    // Self-update. The SAME library Constellation - the owner's app store - drives its
    // own updates with, pulling the SAME GHCR image the store distributes for this app.
    // Linking it is reusing the store's engine, not adding a second one: Constellation
    // exposes no component another app can invoke (libs:appstore declares none, and the
    // SuperApp exports only its MAIN launchers), so "update mail now, on demand" has
    // nothing to delegate TO. Pulls :libs:core transitively, which the install-result
    // notification needs and which merges the constellation's signature-level
    // permission into this app.
    implementation(project(":libs:updater"))

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
