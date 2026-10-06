# No consumer ProGuard rules needed — the lib exposes plain Kotlin
# objects + an AIDL stub that Shizuku instantiates by reflection (the
# stub class name is referenced via ComponentName, which R8 keeps because
# it's reachable from ShizukuAdb). If minification ever strips
# ShellUserService, add: -keep class com.diegonmarcos.superapp.adbdebug.ShellUserService { *; }

# BouncyCastleProvider registers its algorithms by Class.forName on string
# names (org.bouncycastle.jcajce.provider.asymmetric.RSA$Mappings …) and
# silently skips what it cannot load. R8 removed those never-referenced
# classes, so the bundled provider instance registered no RSA signer and
# JcaContentSignerBuilder failed with "no such algorithm: SHA256WITHRSA for
# provider BC" — the adb wireless-debugging pairing cert never minted. A
# consumer rule so every app that pulls this lib keeps the provider whole.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
