# ac_cloud-sheets — Cloud Office (MIRROR)

This directory contains **no source code**, and that is the design.

Every other `ac_cloud-*` directory in this monorepo holds a tree we compile.
Cloud Office is the exception. Upstream is **Collabora Office for Android**: the
Collabora Online web app running inside a WebView, over a LibreOffice-core
engine, built from Collabora's own Gerrit monorepo with an NDK megabuild over
the entire `external/` tree. That is a toolchain we do not carry, hours of
compute we do not want to spend per push, a native build upstream documents as
Linux-only, and the artifact still could not legitimately be signed as
`com.collabora.libreoffice` by us.

So `ship-cloud-sheets.yml` **mirrors** instead:

1. downloads the official APK from Collabora's F-Droid repo,
2. verifies it byte-for-byte against the `sha256` pinned in `build.json`,
3. republishes those *exact, unmodified* bytes as
   - GH Release asset `Cloud-Sheets.apk` on the rolling `latest` tag, and
   - `ghcr.io/diegonmarcos/cloud-sheets:latest` (ORAS, media type
     `application/vnd.android.package-archive`),

so the Constellation AppStore installs and auto-updates it like any other fleet
member. Installing ours is byte-identical to installing from Collabora's own
repo — same package id, same upstream signature.

## The pin

`build.json::upstream` is the security boundary. An unpinned mirror is a
supply-chain hole: whatever upstream happened to serve would go out under our
name. A sha256 (or size) mismatch **fails the job** and publishes nothing.

Currently pinned:

| field        | value                                                          |
|--------------|----------------------------------------------------------------|
| package      | `com.collabora.libreoffice`                                    |
| versionName  | `26.04.3.1`                                                    |
| versionCode  | `155`                                                          |
| apk          | `collabora-office-mobile-26.04.3.1-155-release-arm64-v8a-2026-09-03.apk` |
| size         | `267216449`                                                    |
| sha256       | `b7ab381de96f429c2e762d0e54ee0fac7db03ad2fecfede9f4c558c767d18513` |
| abi          | `arm64-v8a` only (upstream ships no x86_64)                    |
| minSdk       | `26`                                                           |
| license      | `MPL-2.0`                                                      |

## Bumping the version

Read the new entry out of
<https://www.collaboraoffice.com/downloads/fdroid/repo/index-v1.json> and update
`version_name`, `version_code`, `apk_name`, `url`, `sha256` and `size` together
in **one** commit. Never update the sha alone — a sha that no longer matches its
declared version is a pin that documents nothing.

Take `upstream.source_repo` with you when you do. It currently names LibreOffice
core, which is the *engine* but not where this app is built from (see
`build.json::_doc_upstream_source`). Correcting it is deferred to a pin bump on
purpose: `source_repo` lives inside `upstream`, and `source_identity.mode=pin`
hashes that whole object, so editing it on its own re-runs a 267 MB mirror job
to publish a comment. A bump pays that cost anyway.

Two channels share the `com.collabora.libreoffice*` namespace. Only the
**release** channel is ours to mirror: `com.collabora.libreoffice.snapshot` is a
separate package id and runs ahead (it was at `26.04.4.0` / vc163 while release
was at vc155). A snapshot version number is not evidence that the pin is stale.

**And do not let it go stale.** A pin that is merely OLD still matches its own
sha256, so `ship-cloud-sheets.yml` publishes it happily — nothing in the
workflow can tell "correct" from "six months behind". That gap is not cosmetic
here, because this is the one fleet entry with a SECOND publisher: a phone can
follow Collabora's own F-Droid repo and end up on a HIGHER versionCode than we
mirror. The Constellation AppStore decides "outdated" by comparing
sha256(installed base.apk) against the published `Cloud-Sheets.apk.sha256` — a
difference, with no direction to it — so such a phone reads as permanently
outdated, while `Fleet.commit` correctly refuses to install, because a lower
versionCode over a higher one is a downgrade Android rejects. Download, no
error, still outdated, forever. The pin sat at versionCode 115 from the day it
was added while upstream reached 155.

`aa_cloud-superapp/test/test-sheets-mirror-pin.sh` is what notices: T3 asserts
the pin is upstream's NEWEST arm64-v8a release, against the live index.

## What "Cloud Office" can and cannot rename

The app has two names and only one of them is ours.

**Ours — data, renameable today:**

| surface | source of the string |
|---------|----------------------|
| Constellation AppStore row | `ac_cloud-sheets/build.json::name` → `data/regen.sh` → `constellation-fleet.json::apps[sheets].label` → `BuildConfig.CONSTELLATION_FLEET_B64` |
| Launcher tile under `ic_sheets` | `aa_cloud-superapp/build.json` — the `ui.sections[…].tile_groups[…].tiles[…]` entry whose `target` is `extapp:cloud-sheets` |
| Updater install/progress notifications | `aa_cloud-superapp/build.json::ui.external_apps[cloud-sheets].label` → `Fleet.App.label` |

`constellation-fleet.json` is **generated**, not authored: `app/build.gradle`
re-runs `data/regen.sh --constellation-only` on every build, and
`ship-superapp-data-regen.yml` reverts the file rather than committing it. Edit
`build.json::name`; never hand-edit the snapshot.

**Not ours — inside the APK, needs a fork:**

- The Android launcher label and task-switcher title. Those come from
  `android:label` in upstream's manifest and say "Collabora Office". We
  republish upstream's bytes unmodified; relabelling means rebuilding and
  re-signing, which changes the package identity.
- The app icon, splash and about screen.
- The in-app menus, including where the config/settings entry sits.

## Text Enhance inside the document

`ac_cloud-keyboard` carries Text Enhance on its toolbar and, being an input
method, is present in every text field on the device including this one. It does
**not** reach the document, and the reason is structural rather than a missing
permission.

`TextEnhancer.target()` reads the field through `RichInputConnection` —
`getExtractedText()`, falling back to `getTextBeforeCursor` /
`getSelectedText` / `getTextAfterCursor` — and writes the rewrite back over the
range it resolved. That is the standard Android editing contract, and upstream
does honour it: `COWebView.onCreateInputConnection` delegates to
`super` (the stock WebView/Chromium `InputConnection`), so keystrokes reach the
document normally.

What defeats it is what sits behind that connection. The document is rendered by
Collabora Online, and its text input is a hidden `contenteditable` element
(`browser/src/layer/marker/TextInput.js`) that never holds document text: it
carries a pre-space/post-space sentinel pair plus whatever composition is in
flight, and `_emptyArea()` resets it to `_initialContent` after every commit.
So `getExtractedText()` returns that near-empty buffer, not the paragraph on
screen, and a document selection lives in Collabora's own canvas rather than in
the connection at all.

The observable result: pressing ENHANCE inside a document hits the
`target.text.isBlank()` branch in `TextEnhancer.run` and returns. In the default
`auto` scope that branch is silent — the toast is only raised for `selection`
scope — so the key reads as dead. Typing, autocorrect and the suggestion strip
are unaffected; only the read-and-replace features are.

**Not verified without a device.** This is read from upstream's
`co-25.04-mobile` branch and from our own keyboard source. Nobody has pressed
the key on the phone.

## Google Drive

Reachable through the Storage Access Framework, with no change to the APK and
nothing for the fleet to provision. Upstream implements the whole round trip:

- **Open** — `LibreOfficeUIActivity` fires `ACTION_OPEN_DOCUMENT` (falling back
  to `ACTION_GET_CONTENT`) and calls `takePersistableUriPermission` with
  `READ | WRITE`. Any installed DocumentsProvider appears in that picker, and
  Google Drive ships one.
- **Open from elsewhere** — `LOActivity` registers `VIEW`/`EDIT` for
  `scheme="content"` across the full office MIME list, so a document tapped in
  the Files app or in Drive's own app opens here.
- **Edit** — `copyFileToTemp()` stages the `content://` URI to a temp file and
  the engine edits that.
- **Save back to the same Drive file** — `copyTempBackToIntent()` writes the
  temp file back to the *original* URI via
  `contentResolver.openOutputStream(uri, "wt")`, falling back to plain mode if
  `"wt"` is refused. It runs on the `SAVE` message from Collabora Online, so
  autosave round-trips, not only close.
- **Save As to Drive** — `ACTION_CREATE_DOCUMENT` with
  `EXTRA_LOCAL_ONLY=false`, so Drive is offered as a destination.

Where it breaks, all of it upstream's own code path:

- Editing is disabled outright unless the opening intent carried
  `FLAG_GRANT_WRITE_URI_PERMISSION`; a read-granted URI opens read-only with a
  toast.
- Google's own formats (Docs/Sheets/Slides) are not files. Drive's provider
  exposes them for export only, so there is nothing to write back to. Only real
  uploaded `.odt`/`.docx`/`.xlsx`-style files round-trip.
- Upstream already hard-codes Google Drive on ChromeOS as non-writable
  (`content://org.chromium.arc.*`), which is a standing signal that Drive's
  write support is provider-dependent.
- Write failures are logged, not surfaced: `copyTempBackToIntent` catches
  `FileNotFoundException` and `Exception` and only writes to logcat. A refused
  write back to Drive is silent on the phone.

**Not verified without a device.** Whether Drive's provider grants write for a
given file on this phone is a runtime answer.
