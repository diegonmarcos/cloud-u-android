# ac_cloud-sheets — Collabora Office (MIRROR)

This directory contains **no source code**, and that is the design.

Every other `ac_cloud-*` directory in this monorepo holds a tree we compile.
Collabora Office is the exception: its engine *is* LibreOffice core, built from
Collabora's own Gerrit with an NDK megabuild over the entire `external/` tree.
That is a toolchain we do not carry, hours of compute we do not want to spend
per push, and the artifact still could not legitimately be signed as
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
