// Vendored openpgp-api client library (see NOTICE). Java-only, no Kotlin.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.openintents.openpgp"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
