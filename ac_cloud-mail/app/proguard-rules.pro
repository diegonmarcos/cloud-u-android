# Add project-specific ProGuard rules here.

# --- kotlinx.serialization ---------------------------------------------------
# The serialization artifacts ship consumer rules, but the JMAP protocol leans
# heavily on @Serializable models (core:jmap) where a stripped/renamed generated
# serializer surfaces only at runtime as a sync failure. Keep them explicitly as
# belt-and-suspenders (the official kotlinx.serialization R8 rules).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the generated serializers and the companions that expose serializer().
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Sterna's own @Serializable models (JMAP wire types, settings) — keep wholesale
# so no protocol field is ever dropped by shrinking.
-keep,includedescriptorclasses class app.sterna.**$$serializer { *; }
-keepclassmembers class app.sterna.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- logging ------------------------------------------------------------------
# Drop debug/verbose logging from release builds, and ONLY those. Log.i/w/e stay:
# reporters send logcats, and on hardware I do not own that is the only diagnostic
# there is (a crash was fixed from a reporter's log, and another case still rides on
# one). The real risk was never the existence of the log, it was the content
# interpolated into it, which is handled at the source instead. If a debug line ever
# carries something needed for diagnosis, promote it to Log.i rather than widen this.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# The OpenPGP provider (OpenKeychain) sends its result Parcelables across the binder under
# their original class names, and unmarshalling looks them up BY NAME in our classloader —
# so the vendored openpgp-api classes must keep their names verbatim. Without this, every
# minified release crashed with BadParcelableException/ClassNotFoundException
# (OpenPgpSignatureResult) the moment a signed or encrypted mail was verified (Codeberg #14).
-keep class org.openintents.openpgp.** { *; }

# The avatar composables render nothing in a minified build while debug is fine: R8's optimizer
# inlines these small, non-inline @Composable functions into their callers (the mapping keeps
# only Monogram's synthetic lambda, the function itself is gone), which drops the restartable
# group they need to emit — so neither the contact photo nor the monogram slot draws in the
# recipient-suggestion menu, the message list or the reader. Keeping them whole stops the inline
# and restores the avatar. Same failure family as #14: an optimization that is correct for
# ordinary code but wrong for a compiler-managed calling convention.
-keep class app.sterna.ui.components.MonogramKt { *; }
-keep class app.sterna.ui.components.ContactAvatarKt { *; }

# Same family, and pre-emptive this time (#120). IncognitoKeyboard is a small, non-inline
# @Composable whose whole body is "provide an interceptor, call content()" — exactly the shape R8
# inlined above. What it installs is the request that asks the keyboard not to learn from a message
# being written, and unlike a missing avatar its loss is INVISIBLE: the composer looks and behaves
# the same, only the privacy request is gone, and only in a minified build. Keeping the file's
# class costs a few bytes and removes the one failure nobody would notice.
-keep class app.sterna.ui.compose.IncognitoKeyboardKt { *; }
# And the anonymous classes of that file, which is where the behaviour actually lives: the
# interceptor and the request it wraps around the field's own compile to
# IncognitoKeyboardKt$INCOGNITO_INTERCEPTOR$1 and a nested $1 of it. The rule above keeps the
# facade and would have left those two to be merged, renamed or inlined on their own.
-keep class app.sterna.ui.compose.IncognitoKeyboardKt$** { *; }

# Same family again, pre-emptive (#63). LoadingRing is a small, non-inline @Composable whose body
# is "read a setting, call one of two indicators" — the exact shape R8 inlined above, and it is now
# the ONLY centred loading indicator on fifteen screens. Inlined away, a minified build would show
# an empty screen wherever it used to say "loading".
-keep class app.sterna.ui.components.LoadingRingKt { *; }
# And its anonymous classes, same reason as IncognitoKeyboard above: the resting branch is a Box
# whose content lambda and `progress = { rest }` compile to LoadingRingKt$LoadingRing$… — that
# lambda IS the ring. The facade rule would leave them to be merged, renamed or inlined on their
# own, and a minified build would then paint an empty screen on all fifteen loading states.
-keep class app.sterna.ui.components.LoadingRingKt$** { *; }

# Same family, pre-emptive (#103). ReadingPane holds two small, non-inline @Composable functions:
# the row that puts the list beside the reading pane, and the pane's own "select a message" line.
# Inlined away, a wide window would show the list alone — or a blank right half — in release only.
-keep class app.sterna.ui.inbox.ReadingPaneKt { *; }
# And its anonymous classes, same reason as LoadingRing above: the row's content lambdas — the
# `{ list() }` and `{ detail() }` handed to each Box — compile to ReadingPaneKt$ListDetailPanes$…,
# outside the facade the rule above keeps; merged or inlined on their own, a wide window would
# paint one of the two halves empty in a minified build.
-keep class app.sterna.ui.inbox.ReadingPaneKt$** { *; }
