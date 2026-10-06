package com.diegonmarcos.superapp.bottomnav

/*
 * THE PLATFORM SEAM (#876). The libs are com.android.library, not Kotlin Multiplatform, so there
 * is no expect/actual: the four functions below are declared by NAME here (as the contract) and
 * DEFINED once per platform, in the one source tree that platform compiles:
 *
 *   Android  libs/bottomnav/src/main/.../NavPlatformAndroid.kt   (resources, dynamic palette,
 *            animator scale + battery saver, the Vibrator haptics, includeFontPadding)
 *   Wasm     ab_cloud-libs-shared/web/fleet-ui/src/wasmJsMain/.../NavPlatformWasm.kt
 *            (SuperApp's literals, no haptics, no platform font padding)
 *
 * A host that wants different values provides [LocalNavTokens] / [LocalReduceMotion] /
 * [LocalFleetHaptics] and never reaches this.
 *
 *   internal fun platformNavTokens(): NavTokens                 @Composable
 *   internal fun platformBarMotion(key: Any?): Boolean          @Composable
 *   internal fun platformNavHaptics(): NavHaptics               @Composable
 *   internal fun TextStyle.withPlatformFontPadding(): TextStyle
 */
