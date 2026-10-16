plugins {
    alias(libs.plugins.android).apply(false)
    alias(libs.plugins.kotlinAndroid).apply(false)
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.detekt).apply(false)
    // 2026-10-16 owner decision: all apps must be Compose; libs:ui-kit and the app resolve the compiler from here.
    alias(libs.plugins.kotlinCompose).apply(false)
}
