# libs:keyboard — Cloud Keyboard (owned keyboard provider)

The `LatinIME` `InputMethodService` behind Cloud Keyboard. This tree is
**owned code**: edit `src/main` directly and commit. There is no upstream
sync, no overlay patch, no regeneration step.

## Heritage

Cloud Keyboard is derived from [HeliBoard](https://github.com/Helium314/HeliBoard)
(GPL-3.0, itself derived from OpenBoard / AOSP LatinIME). The GPL heritage and
upstream attribution stay: `SPDX-License-Identifier` headers, the license link
in the About screen, and the upstream issue/PR references in code comments are
kept intact. Only the product name changed.

## Layout

| Path | Notes |
|------|-------|
| `build.gradle` | Module build. Toolchain pins in `aa_cloud-superapp/build.json::toolchain`. |
| `src/main/{java,res,assets,jni,AndroidManifest.xml}` | The keyboard. Edit directly. |
| `dicts-data/` | Extra dictionaries vendored by `./build.sh sync-keyboard-dicts` (aa_cloud-superapp) from codeberg.org/Helium314/aosp-dictionaries. |

Logic that does not need the `helium314.keyboard` package lives in its own
module (`libs/translate`, `libs/voice`, `libs/media`).

## Manifest reconciliation (in `app/`, not here)

The module manifest declares its own `<application android:name=…App>`, makes
`SettingsActivity` a **LAUNCHER** entry and `SpellCheckerSettingsActivity` a
**MAIN** entry, and registers a spellchecker service, two content providers,
boot receivers, and `<queries>`.

Consumer-specific reconciliation lives in each app's `AndroidManifest.xml` as
explicit `tools:` overlays:

- `tools:replace="android:name"` on `<application>` → the app's own `.App`
  wins the merge over `helium314.keyboard.latin.App`.
- SuperApp only: `tools:node="remove"` on the `SettingsActivity` LAUNCHER
  filter and the `SpellCheckerSettingsActivity` MAIN filter → **no extra
  launcher icons**.

Because `.App` wins the merge, the module's `App.onCreate()` never runs; each
consumer replicates its prefs/subtype init (see `initVendoredKeyboard` in the
app `App.kt`).

### Provider authority collision

The content providers use fixed `@string` authorities
(`clipboard_provider_authority`, `gesture_data_provider_authority`). Two apps
bundling this module with the same authorities cannot be installed side by side.

## Swipe / glide typing — FOSS base + in-app loader

Glide needs Google's proprietary `libjni_latinimegoogle.so`, which is **never**
bundled (keeps the APK FOSS). **Settings → Gesture typing → "Load gesture
typing library"** is the in-app loader: the user fetches the blob once at
runtime into app data. Without it the open-source swipe path applies.

## Native build

`src/main/jni/Android.mk` → `libjni_latinime.so` via `ndk-build` (NDK 28,
arm64-v8a). The two NDKs (26.1 for `libs:net`, 28 for keyboard) are both
provisioned in `flake.nix`.

## Package namespace

The Java/Kotlin package stays `helium314.keyboard.*`: the manifest resolves the
IME service, providers and the `LatinIME` class against that namespace, and
installed users' prefs/databases (`heliboard.db`) are keyed on it. Renaming it
is a refactor, not a rebrand.

## Tests

Static proofs (no gradle needed) live in `aa_cloud-superapp/test/`:
`test-keyboard-translate.sh`, `test-keyboard-ai-routing.sh`. CI builds prove
compilation.
