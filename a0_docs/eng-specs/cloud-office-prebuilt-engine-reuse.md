# Cloud Office — can we ship the engine we already download instead of compiling it?

Status: **SUPERSEDED IN PART BY §12 (2026-09-11). "Can the engine be salvaged
into a new application?" — NO, and §1-§11 prove it. "Does anything the owner
asked for need the engine rebuilt?" — ALSO NO, and that is the question that
decides the money. Read §12 first; #240's ~$35 is not needed for #224 or #225.**

Original status (still correct for the question it asks): **NO. Extraction does
not work, and the reason is not the size of the `.so`.** Extends `cloud-sheets-clone-feasibility.md` (commit `62515ee90`); does
not replace it. Nothing was built and nothing paid was enabled. §1-§10 were
written without downloading any APK in full; **§11 is a second pass that did
download all 267,216,449 bytes**, confirms §2's blocker, and corrects three
claims — the ABI limit, the trademark blocker, and the reach of §4's vtable
argument. The verdict is unchanged.

**Verdict in one sentence:** the prebuilt engine cannot be reused, because the
engine hands the shell a *link-time* half — eight static archives and three
header trees that `configure` and CMake read by absolute path out of
`$LOBUILDDIR` — that an APK has never contained and never will, and because on
the 26.04 line the shell/engine seam stopped being a `nSize`-guarded C struct
and became a **119-slot C++ vtable with no version guard**, so pairing
Collabora's engine binary with our shell source is an unverifiable silent-
corruption risk rather than a supported configuration.

Investigated 2026-09-10 against the revision `ac_cloud-office/build.json`
actually pins — `online@distro/collabora/co-26.04-mobile`
`794af0088b0369878461cc53de45129eaf08824a` — read file-by-file from Collabora's
Gerrit REST API, and against the ZIP central directory of the pinned official
APK, read by HTTP range request.

---

## 0. What is measured here, and what is not

Everything below is quoted from a file that was fetched or from command output
that is reproduced. Two things are **not** established and are marked where they
appear:

- whether the engine commit behind the official APK (`20a46c332c38…`) is an
  ancestor of our pinned commit. Gerrit's REST API resolves only full 40-char
  shas and refuses abbreviated ones, and Gerrit's git server refuses arbitrary
  sha fetches, so settling this needs a full monorepo clone (~471 MB, ~10 min).
  **It was not done, and the recommendation does not depend on it** — it is one
  more thing the reuse path would have to prove and the compile path never has
  to ask.
- ~~whether `liblo-native-code.so` itself carries "Collabora Office" product
  strings.~~ **Answered in §11**: the full 199 MB was scanned. Two occurrences,
  neither a product identity, and `Collabora Productivity` appears zero times.
  This weakens §5's trademark finding — see §11, correction 2.

Gerrit's **gitiles** web UI is behind a GitHub OAuth redirect and returned a
login page to every fetch. Its **REST API** (`/projects/<p>/commits/<sha>/files/
<path>/content`) serves anonymously and is what every upstream quote here comes
from.

---

## 1. A correction that changes the ground: 26.04 is a MONOREPO

`cloud-sheets-clone-feasibility.md` §1 and `build.json::upstream.core` both
describe two repositories, `online` and `core`. **On the revision we pin, that
is no longer true**, and the difference is not cosmetic.

`android/README.md` at `online@794af008`, verbatim:

> "All the source code now lives in a single Gerrit monorepo; the former
> Collabora Office core is the `engine/` subdirectory of the `online` repo, so
> there is no separate repository to clone any more."

Probed directly, at the pinned revision:

| path in `online@794af008` | HTTP |
|---|---|
| `engine/README.md` | **200** — and its first line is `# Collabora Office` |
| `engine/vcl/README.md` | **200** |
| `engine/download.lst` | **200** |
| `vcl/README.md` | 404 |
| `download.lst` | 404 |

The pinned commit's own parent is `b966b497…` *"vcl: keep the alpha channel of
Quartz bitmap contexts"* — an office-suite commit sitting in the `online`
history. One repo.

### What that means for `build.json`, today

`ac_cloud-office/build.json::upstream.core` pins
`https://gerrit.collaboraoffice.com/core` at `distro/collabora/co-26.04`,
revision `c1013fad0d38683630b48c01b6d5208b8d81181e`. That ref exists — but
`git ls-remote` (15,301 refs, read today) shows `c1013fad` is its tip, and the
Gerrit REST API dates that commit **2026-04-01**. The APK it is supposed to
match shipped **2026-09-03**. The `core` repo's 26.04 branch has not moved in
five months because the work moved into `online/engine/`.

**So `upstream.core` is not merely redundant — following it would build a
five-month-stale engine against a September shell.** That is the same class of
silent drift the 25.04→26.04 pin already caught once, and it is live in the
repository right now.

### A second live bug in the same block

`build.json::upstream.online.configure` sets `--with-android-package-name`.
At the pinned revision that option **does not exist**. `configure.ac:293` reads:

```
AS_HELP_STRING([--with-app-package-name="org.collabora.app"],
```

and there is no `--with-android-package-name` anywhere in the file. Autoconf
does not fail on unknown `--with-*` options; it prints
`configure: WARNING: unrecognized options:` and carries on, so the app would be
built with `configure.ac`'s default and nobody would see an error. Collabora's
own build, recovered from the shipped binary (§4), uses
`'--with-app-package-name=com.collabora.libreoffice'`.

**FIXED 2026-09-11, and the default named here was wrong.** This section first
said the fallback is `com.collabora.office.Mobile`. Re-read, the branch decides
it — `configure.ac:1157-1163`:

```
if test -z "$with_app_package_name" -o "$with_app_package_name" = "no"; then
    if test "$enable_iosapp" = "yes"; then
        APP_PACKAGE_NAME="com.collabora.office.Mobile"
    else
        APP_PACKAGE_NAME="org.collabora.app"
```

`com.collabora.office.Mobile` is the **iOS** bundle identifier. An Android
build takes the `else` and would have shipped as **`org.collabora.app`** —
upstream's own namespace either way, so the consequence is unchanged, but the
value is `org.collabora.app`. `build.json` now passes
`--with-app-package-name`, and `ac_cloud-office/tests/test-configure-flags-exist.sh`
derives the required flag from upstream on every run (the `applicationId`
placeholder in `android/app/appSettings.gradle.in` → the `$with_*` variable
`configure.ac` assigns it from → the flag name) so it cannot regress silently.

`--with-app-branding` (`configure.ac:228`) is also absent from our configure
block, and it is what carries the launcher icon, splash and online theme.

---

## 2. ONE — everything the engine contributes, and where it is

`android/lib/src/main/cpp/CMakeLists.txt.in` and `configure.ac` between them
name every path the shell reads out of `$LOBUILDDIR`. Checked against the live
central directory of the pinned APK (6,375 entries, 591,403 bytes fetched of
267,216,449 — see `ac_cloud-office/tests/test-engine-artefacts-in-apk.sh`).

### Present in the APK — the runtime half (20 rows checked, all found)

| `$LOBUILDDIR` path | APK path | bytes | required by |
|---|---|---|---|
| `android/jniLibs/arm64-v8a/liblo-native-code.so` | `lib/arm64-v8a/liblo-native-code.so` | **199,472,488** | `CMakeLists.txt.in:91,145,166` |
| `instdir/program/lib{freebl3,nspr4,nss3,nssckbi,nssdbm3,nssutil3,plc4,plds4,smime3,softokn3,sqlite3,ssl3}.so` | `lib/arm64-v8a/…` (12 files) | 7,040,424 | `CMakeLists.txt.in:109-143` |
| `instdir/program/services.rdb`, `program/services/services.rdb` | `assets/program/…` | 234,910 | `build.gradle:184` |
| `instdir/program/types/{offapi,oovbaapi}.rdb`, `instdir/program/types.rdb` | `assets/unpack/program/{offapi,oovbaapi,udkapi}.rdb` | 1,058,947 | `build.gradle:104-112` |
| `instdir/program/resource/**` | `assets/unpack/program/resource/**` | — | `build.gradle:116` |
| `instdir/share/{registry,filter,gallery,palette}/**` | `assets/share/…` | 14,324,095 | `build.gradle:188` |
| `instdir/share/config/{soffice.cfg/**,images_colibre.zip}` | `assets/share/config/…` | 35,668,885 | `build.gradle:198` |
| `instdir/share/fonts/truetype/*.ttf` (filtered) | `assets/unpack/user/fonts/*.ttf` | 10,136,216 | `build.gradle:125` |
| `instdir/share/liblangtag/**`, `share/extensions/dict-*` | `assets/unpack/share/…` | 29,059,475 | `build.gradle:152-168` |
| `instdir/LICENSE.html`, `instdir/NOTICE` | `assets/license.html`, `assets/notice.txt` | 378,277 | `build.gradle:175` |

The renames and filters gradle applies (`types.rdb`→`udkapi.rdb`,
`LICENSE.html`→`license.html`, the font include-list, the dictionary extension
filter) are all mechanical and invertible, and a rebuild would re-apply the same
filters, so nothing useful is lost by round-tripping through the APK. **The
asset half of the engine's contribution is genuinely, completely present.**

The runtime closure is complete too. `DT_NEEDED` of the two libraries, read from
the ELF `.dynamic` section over HTTP ranges:

```
liblo-native-code.so -> libfreebl3 libnspr4 libnss3 libnssckbi libnssdbm3
                        libnssutil3 libplc4 libplds4 libsmime3 libsoftokn3
                        libsqlite3 libssl3 libGLESv2 libandroid libjnigraphics
                        liblog libz libc++_shared libm libdl libc
libandroidapp.so     -> libandroid liblog libz liblo-native-code libm
                        libc++_shared libdl libc
```

Every non-platform entry is in `lib/arm64-v8a/` of the APK.

### NOT in the APK — the link-time half (13 rows checked, none found)

This is the answer to the question.

| `$LOBUILDDIR` path | what needs it | file:line |
|---|---|---|
| `instdir/program/setuprc` | **`configure`'s only gate on `$LOBUILDDIR`**, and the source of `CORE_VERSION_HASH` | `configure.ac:921,927` |
| `workdir/LinkTarget/StaticLibrary/liblibpng.a` | static link + the per-ABI gate | `configure.ac:930,1107`; `CMakeLists.txt.in:157` |
| `…/libPoco{Foundation,Net,Util,XML,JSON}.a` | static link | `configure.ac:1023-1026`; `CMakeLists.txt.in:158-162` |
| `…/libexpat.a` | static link | `CMakeLists.txt.in:163` |
| `…/libzstd.a` | static link + gate | `configure.ac:1055-1058`; `CMakeLists.txt.in:164` |
| `workdir/UnpackedTarball/poco/include/Poco/Poco.h` | include dir + gate | `configure.ac:1009-1012`; `CMakeLists.txt.in:101` |
| `workdir/UnpackedTarball/zstd/lib/zstd.h` | include dir + gate | `configure.ac:1051-1054`; `CMakeLists.txt.in:102` |
| `workdir/UnpackedTarball/libpng/png.h` | include dir + gate | `configure.ac:1106`; `CMakeLists.txt.in:103` |
| `$LOBUILDDIR/include` (`COKit/COKit.hxx`) | the shell/engine ABI header | `CMakeLists.txt.in:104`; `kit/Kit.hpp:24` |

`target_link_libraries(androidapp …)` at `CMakeLists.txt.in:154-167` lists eight
`.a` archives before it lists `liblo-native-code.so`. The archive count is not a
detail: **`libandroidapp.so`, the 6.3 MB file we would be rebuilding, cannot be
linked without them**, and the APK contains zero `.a` entries and zero `.h`
entries out of 6,375. The tester asserts that by category, not by name, so it
turns red if that ever stops being true.

Note also that POCO and zstd are no longer separate builds. `configure.ac:390-
416` marks `--with-poco-includes`, `--with-poco-libs`, `--with-zstd-*` and
`--with-libpng-*` **"Obsolete and ignored"**, and `configure.ac:996-997,1038-
1039,1101-1102` derive all of them from `$engine_builddir/workdir/`. Upstream's
`android/README.md` says the same in prose:

> "POCO and libzstd are built as part of the engine, one copy per ABI, and taken
> from its workdir, so they no longer need to be built separately for Android."

So the third-party archives are not a side dependency we could satisfy some
other way and stay on the supported path — upstream deliberately folded them
*into* the engine build during this release cycle.

One further engine artefact, `$LOBUILDDIR/android/default-document/example.odt`
(`libSettings.gradle.in`), is copied by `build.gradle:180` and is **absent from
the shipped APK** — evidently optional in Collabora's own configuration. Noted
for completeness; it changes nothing.

---

## 3. TWO — the build-directory check, and why passing it proves nothing

The prior scoping recorded the gate as `liblibpng.a`. On the pinned 26.04
revision that is **the second** check; the first one is different:

```sh
# configure.ac:914-924
   # Also use the setuprc or setup.ini file existence as a sanity check.
   if test "$enable_windowsapp" = "yes"; then setuprc=setup.ini; else setuprc=setuprc; fi
   if test -f "$LOBUILDDIR/instdir/program/$setuprc"; then
      AC_MSG_RESULT([$LOBUILDDIR])
   else
      AC_MSG_ERROR([This is not a CollaboraOffice core build directory: $LOBUILDDIR])
   fi
# configure.ac:927
   CORE_VERSION_HASH=`grep buildid $LOBUILDDIR/instdir/program/$setuprc | sed -e 's/buildid=//' -e 's/............................$//'`
```

**`setuprc` is not in the APK.** `assets/program/` contains exactly six entries —
`bootstraprc`, `fundamentalrc`, `lounorc`, `services.rdb`, `unorc`, `versionrc` —
and no member anywhere in the archive is named `setuprc`. So even the *first*
gate fails on a purely extracted tree.

### What the minimum is, and why it is a lie

Replayed verbatim on a fabricated `$LOBUILDDIR`, watched:

```
A) empty fabricated dir (what pure extraction gives you):
  configure.ac:921 ERROR: This is not a CollaboraOffice core build directory: /tmp/lobd
  configure.ac:930 ERROR: This is not a CollaboraOffice ARM64 core build directory: /tmp/lobd
  CORE_VERSION_HASH=''

B) + a one-line setuprc written by hand:
  configure.ac:921 PASS (/tmp/lobd)
  configure.ac:930 ERROR: This is not a CollaboraOffice ARM64 core build directory: /tmp/lobd
  CORE_VERSION_HASH='20a46c332c3800'

C) + a ZERO-BYTE file named liblibpng.a:
  configure.ac:921 PASS (/tmp/lobd)
  configure.ac:930 PASS
  CORE_VERSION_HASH='20a46c332c3800'
```

The minimum to satisfy `configure` is **two files, one of which may be empty**:
a hand-written `setuprc` and a zero-byte `liblibpng.a`. Both gates are
`test -f`. They check for a *name*, not for a library.

That is exactly the difference the question asked about. `configure` passing
means the paths exist. The build then proceeds to `CMakeLists.txt.in:154`, hands
`ld` a zero-byte archive and seven more that do not exist at all, and dies at
link with undefined references to Poco, expat, zstd and libpng — several
thousand of them, after the ~40-file C++ compile has already run. **A check that
passes on a stub proves the stub exists. Nothing else.**

Honest options to fill the gap: cross-compile Poco, zstd, expat and libpng for
`arm64-v8a` ourselves (they are ordinary NDK cross-builds, order of an hour),
and write `setuprc` by hand from the `buildid` recovered from
`assets/program/versionrc`. Both would *work* in the narrow sense. Both mean we
are no longer running the configuration upstream supports, we own four
third-party builds forever, and we still have not addressed §4.

---

## 4. THREE — the seam is narrow, and it is not stable

### The good half: the seam really is three symbols wide

Read from the ELF `.dynsym` of both libraries, over HTTP ranges, without
downloading either in full (0.18 % of the 199 MB engine was fetched):

```
libandroidapp.so : 9,232 defined, 435 undefined
liblo-native-code.so : 5,040 defined,  606 undefined

shell UNDEF satisfied by engine: cokit_hook_2, cokit_initialize,
                                 cokit_set_javavm  (+ 32 C++ typeinfos)
engine UNDEF satisfied by shell: 0
```

Three plain-C function symbols, one direction, no back-calls. `cokit_hook` is
also exported but unused by the shell. `androidapp.cpp:241` declares
`cokit_initialize` `extern "C"` without defining it, which is the source-level
half of the same fact.

### The fatal half: what is behind `cokit_hook_2`

`kit/Kit.hpp:24` is `#include <COKit/COKit.hxx>`, resolved through
`CMakeLists.txt.in:104`'s `${LOBUILDDIR_ABI}/include`. That header, fetched from
`online@794af008:engine/include/COKit/COKit.hxx` (92,254 bytes), is **not** the
old LibreOfficeKit C interface:

```cpp
struct COKit
{
    virtual ~COKit() = default;
    virtual void destroy() = 0;
    virtual COKitDocument* documentLoad(const char* pURL) = 0;
    ...
```

**119 pure-virtual methods — 35 in `COKit`, 84 in `COKitDocument` — and no
`nSize` field anywhere in the file.**

Compare the interface it replaced — `core`'s `LibreOfficeKit.h`, which is still
what `cloud-sheets-clone-feasibility.md` §8 describes:

```c
struct LibreOfficeKitDocumentClassStruct { size_t nSize; void (*destroy)(...); ... };
#define LIBREOFFICEKIT_HAS_MEMBER(strct,member,nSize) (offsetof(strct, member) < (nSize))
```

That was a table of function pointers with a self-describing size, so a caller
could ask `LIBREOFFICEKIT_DOCUMENT_HAS(pDoc, member)` before calling and an
older engine degraded loudly. The 26.04 replacement is a raw C++ vtable:
**slot order is the ABI, and there is no way to interrogate it at runtime.**
Insert one virtual, remove one, reorder two, or change one signature anywhere in
those 119 declarations, and every later slot shifts. The shell then calls the
wrong engine function with the wrong argument types. No link error. No runtime
error. Silent memory corruption.

The four methods our Text Enhance patch depends on sit *late* in the table —
`postUnoCommand` at `COKit.hxx:1977`, `paste` at `:2004`, `getClipboard` at
`:2263`, `setClipboard` at `:2278` — which is the maximally exposed position:
every earlier insertion moves them.

### Do the versions actually match? No.

`android/lib/build.gradle:307` writes `buildid=${liboCoreVersionHash}` into
`assets/program/versionrc`, and `libSettings.gradle.in` defines
`liboCoreVersionHash = '@CORE_VERSION_HASH@'`, which `configure.ac:927` read out
of the engine build's `setuprc`. So the shipped APK records the engine commit
that produced it. Extracted from the pinned APK:

```
$ cat assets/program/versionrc
[Version]
AllLanguages=en-US
BuildVersion=
buildid=20a46c332c38
ReferenceOOoMajorMinor=4.1
```

And the shell binary agrees — `libandroidapp.so` carries the 10-char
`COOLWSD_VERSION_HASH` string `20a46c332c`, the same commit, because in a
monorepo the engine hash and the online hash *are* the same hash.

`build.json::upstream.online.revision` is **`794af008…`**. The APK's engine is
**`20a46c332c38…`**. Two different commits of the same repository. Under the
reuse plan we would be pairing an engine binary built at commit A with shell
source at commit B, across an unguarded 119-slot vtable — and, as recorded in
§0, we cannot even establish their ancestry without cloning the monorepo.

Under the compile-it-ourselves plan this question does not arise: one checkout,
one commit, `engine/` and `android/` from the same tree, `--with-lo-builddir`
pointing at a build of that same tree — which is exactly what Collabora does
(§5).

---

## 5. FOUR — the licence, for an unmodified compiled artefact

**Permitted in principle; and there is one genuine doubt plus one hard blocker.**

`liblo-native-code.so` is Covered Software in Executable Form (MPL-2.0 §1.6:
*"any form of the work other than Source Code Form"*). §2.1's grant covers
reproduction and distribution in Executable Form, and nothing in the licence
requires that you be the one who compiled it. Redistribution of someone else's
build, inside a Larger Work (§3.3) under our own package id, is within the
licence's design.

What attaches, specifically because we did not compile it:

**§3.2(a) — the source obligation does not shrink; it gets harder.**

> "If You distribute Covered Software in Executable Form then: (a) such Covered
> Software must also be made available in Source Code Form, as described in
> Section 3.1, and You must inform recipients of the Executable Form how they
> can obtain a copy of such Source Code Form by reasonable means in a timely
> manner, at a charge no more than the cost of distribution to the recipient"

Under the compile path this is discharged by the pin: `build.json` names a full
40-char sha in a public repository. **Under the reuse path we would have to
point recipients at the source of a binary whose source we cannot identify.**
All we have is `buildid=20a46c332c38` — twelve characters, resolving to no ref
tip in Collabora's core Gerrit (15,301 refs checked) and not resolvable through
Gerrit's REST API, which rejects abbreviated shas. And the distributor gives us
nothing: the F-Droid index entry for `com.collabora.libreoffice` has
`sourceCode: None` and `license: "Unknown"`.

That is the genuine doubt, and it is not one to resolve by picking the
convenient answer. It is very probably satisfiable — the tree is public and the
commit exists — but we would be asserting an obligation we have not verified we
can meet, on every APK we ship.

**§3.4 — notices.** Satisfied, and already satisfied today.
`android/lib/build.gradle:175` copies `instdir/LICENSE.html` → `assets/license.html`
(376,625 bytes) and `instdir/NOTICE` → `assets/notice.txt` (1,652 bytes, read in
full: Apache/OpenOffice/Oracle/IBM/Lucene/redland/OpenSSL attributions). Both
travel with the assets under either plan. Do not disable that task.

**§2.3 and trademark — this is the hard blocker, and it is measurable.**

> "The licenses granted in this Section 2 are the only rights granted under this
> License. No additional rights or licenses will be implied from the
> distribution or licensing of Covered Software under this License."

No trademark grant, so we cannot ship as "Collabora Office" or "LibreOffice".
The prior scoping treated that as a requirement of the rebrand, satisfied by
`--with-app-name` and a new `applicationId`. **Under the reuse path it is not,
because the product name is baked into the engine's own artefacts, not the
shell's.** From `assets/share/registry/main.xcd` — an *engine* file, copied
verbatim from `instdir/share/registry/` by `build.gradle:188`:

```xml
<prop oor:name="ooName"><value>Collabora Office</value></prop>
<prop oor:name="ooVendor"><value>Collabora Productivity Limited</value></prop>
<prop oor:name="ooSetupVersionAboutBox"><value>26.04.3.1</value></prop>
```

These come from the *engine's* configure, not the shell's `--with-app-name`.
An app extracted from this APK and shipped as "Cloud Office" would identify its
document engine as Collabora Office by Collabora Productivity Limited — in the
About box, and in the generator string written into every saved document. We
cannot fix that with a shell flag, and rewriting another vendor's compiled
artefacts to remove their name is not a fix either.

Under the compile path this is a configure input on the engine build and costs
nothing.

---

## 6. FIVE — what happens to the sha256 pin

**The pin survives in form and is weaker in substance than it looks.**

Under reuse, `ac_cloud-office` would fetch
`collabora-office-mobile-26.04.3.1-155-release-arm64-v8a-2026-09-03.apk`
and check `b7ab381de96f429c2e762d0e54ee0fac7db03ad2fecfede9f4c558c767d18513`
before extracting — the same URL and the same hash the mirror used until commit
`05b0c6c15`. So yes: 96 % of the native payload would again be bytes we verified
against a published hash. That is a real property and the owner was right that
it is worth asking about.

Three things qualify it, and one of them is operational rather than
philosophical:

1. **The pinned URL has a shelf life of about three releases.** Collabora's
   F-Droid index (`index-v1.json`, read today) keeps exactly 12 entries for
   `com.collabora.libreoffice` — 3 versions × 4 ABIs: `26.04.3.1`, `25.04.9.1`,
   `25.04.7.3`. When the fourth release lands, our pinned APK stops being
   served and **the build stops working entirely**, not degrading, just 404. The
   fix is to mirror the 267 MB APK to GHCR ourselves — at which point we are
   hosting a large engine artefact on GHCR with its own sha256 sidecar, which is
   precisely the mechanic the compile path already needs for its engine SDK.
   The mechanism does not get simpler; only what we put in it changes.
2. **It pins the wrong thing for the risk that matters.** The hash proves
   Collabora published those bytes. It says nothing about whether those bytes'
   vtable matches our shell's headers (§4). A verified engine paired with a
   mismatched shell is a verified crash.
3. **We would be verifying a binary we cannot point at a source for** (§5).

Set against the compile path's honest position — a pinned 40-char sha plus a
sha256 on our own engine SDK — this is **a different shape of trust, not a
better one**. It moves the strong guarantee back onto bytes and gives up the
ability to say what source those bytes came from. The prior scoping's §6 ranking
stands unchanged.

---

## 7. SIX — cost, and the thing that actually decides it: upgrades

### Cost, if reuse worked

| | reuse | compile |
|---|---|---|
| one-time engine build | none | ~12 h, x86_64, ~$10 rented / ~$30 on `linux_16_core` |
| per-push shell build | free `ubuntu-latest`, tens of minutes | identical |
| extra work to make it run | cross-build Poco + zstd + expat + libpng for `arm64-v8a`; fabricate `setuprc`; invert the gradle asset renames; mirror a 267 MB APK to GHCR before upstream prunes it | publish one engine SDK to GHCR |
| storage | 267 MB APK mirror | ~1–2 GB engine SDK |

The saving is real but smaller than "$10 and 12 hours versus zero", because the
reuse path acquires four third-party cross-builds and an APK mirror it did not
have before.

Both paths need an **x86_64** host for the shell build. `android/lib/build.gradle:16`
pins `ndkVersion "29.0.14206865"`; the central directory of
`android-ndk-r29-linux.zip` (10,305 entries, read by range request) contains
exactly one toolchain prefix, `toolchains/llvm/prebuilt/linux-x86_64/`, 8,637
files, and no aarch64 host toolchain. The fleet's four aarch64 VMs remain
unusable for either plan; free `ubuntu-latest` covers both.

### Upgrades — and this is what should decide it

**Compile.** Collabora tags a release. We bump one 40-char sha in
`build.json::upstream.online.revision`, re-run `tests/patches-apply.sh` (which
already exists and already refuses anything that is not a full sha), fix
whatever the patch series rejects, and run the engine workflow once. Engine and
shell come from one commit of one repo, so the vtable in §4 is by construction
consistent. The only thing that can break is our own nine-file patch series, and
it breaks *loudly*, at `git am`, before anything is built.

**Reuse.** Collabora tags a release. We must now:
- find the new APK's URL and sha256 in the F-Droid index, and mirror it before
  it is pruned;
- read the new `versionrc` to learn the new engine `buildid`;
- **guess which monorepo commit to pin the shell to**, because the buildid is 12
  characters and resolves to no public ref — pick wrong and the vtable shifts
  silently;
- re-derive the asset inversion map if any gradle rename changed;
- rebuild Poco/zstd/expat/libpng if the engine changed their versions, with no
  signal that it did;
- and then hope, because there is no assertion available anywhere in that chain.
  A vtable mismatch does not fail the build. It ships.

Every release. Forever. **The one-time saving buys a permanent, unassertable
risk at exactly the moment — an upgrade — when we are least able to notice it.**
That is worse than the money it saves, by a wide margin, and it is the reason
this recommendation is not close.

---

## 8. Recommendation

**Compile our own engine. The ~$10 rented build from
`cloud-sheets-clone-feasibility.md` §4 stands.**

What killed reuse, in order of how hard each is to argue with:

1. The APK contains no `.a` and no `.h`, and `libandroidapp.so` cannot be linked
   without eight of the former and three trees of the latter
   (`CMakeLists.txt.in:154-167`, `configure.ac:1009-1107`). Not fixable by
   extracting harder — an APK is a runtime container.
2. The shell/engine ABI on 26.04 is a 119-slot C++ vtable with no `nSize`
   (`COKit.hxx`), and the APK's engine is commit `20a46c332c38…` while we pin
   `794af008…`. A mismatch there is silent.
3. `assets/share/registry/main.xcd` hardcodes `ooName=Collabora Office` and
   `ooVendor=Collabora Productivity Limited`, which MPL-2.0 §2.3 gives us no
   right to ship under our own app.
4. The pinned APK URL survives about three upstream releases
   (`index-v1.json`: 12 entries, 3 versions).
5. Upgrades become a guessing game with no assertion available (§7).

The owner was right to ask, and right that nobody had tested it. The 199 MB
`.so` really is copied rather than compiled, and the seam really is only three
symbols wide. It is the *other* things the engine build produces — and what
happened to the ABI behind those three symbols in this release cycle — that make
it a dead end.

### Fix these three things in `ac_cloud-office/build.json` regardless

They are live now and are wrong under either plan:

1. **Delete `upstream.core`.** The engine is `engine/` inside the pinned
   `online` monorepo. Following the current `core` pin builds a tree last
   touched 2026-04-01 against a September shell.
2. ~~**`--with-android-package-name` → `--with-app-package-name`.**~~ **DONE
   2026-09-11.** The old key did not exist at the pinned revision; autoconf
   warned and moved on, and an Android build would have shipped as
   `org.collabora.app` (`configure.ac:1162`; the `com.collabora.office.Mobile`
   named in the first draft of this spec is the iOS branch). `build.json` now
   passes `--with-app-package-name`, and
   `tests/test-configure-flags-exist.sh` asserts it against upstream.
3. **Add `--with-app-branding=<path>`** (`configure.ac:228`). It is what carries
   the launcher icon, splash and online theme; without it the rebrand is a name
   change only.

### And when the engine workflow is written

Configure it the way Collabora does. Recovered verbatim from a string inside the
shipped `libandroidapp.so`:

```
'--enable-androidapp' '--with-app-name=Collabora Office'
'--with-vendor=Collabora Productivity Limited'
'--with-info-url=https://www.collaboraoffice.com'
'--with-app-package-name=com.collabora.libreoffice'
'--with-android-package-versioncode=26.04.3.1-155'
'--with-android-abi=armeabi-v7a arm64-v8a x86 x86_64'
'--with-lo-builddir=<ws>/build-engine-armeabi-v7a:<ws>/build-engine-arm64-v8a:<ws>/build-engine-x86:<ws>/build-engine-x86_64'
'--with-app-branding=<ws>/branding/out'
'--enable-android-google-play'
```

We need one ABI, so one `build-engine-arm64-v8a` and
`--with-android-abi=arm64-v8a`. Upstream's `android/README.md` gives the engine
side: `autogen.input` with `--with-distro=CPAndroidAarch64`,
`--with-android-ndk=`, `--with-android-sdk=`, `--build=x86_64-unknown-linux-gnu`,
then `make` in `engine/`.

---

## 9. The tester

`ac_cloud-office/tests/test-engine-artefacts-in-apk.sh`, table in
`ac_cloud-office/tests/engine-artefacts.json`. It range-fetches the pinned APK's
ZIP central directory (~600 KB, not 267 MB) and checks all 33 declared
artefacts, plus asserts by category that no `.a`/`.h`/`.hxx` entry exists
anywhere in the archive. If Collabora ever starts shipping the link-time half,
this goes red and §2's conclusion is worth revisiting.

Watched, not assumed:

```
$ ./ac_cloud-office/tests/test-engine-artefacts-in-apk.sh --self-test
# central directory: 6375 entries, 591,403 bytes fetched of 267,216,449
# engine artefacts present in the APK : 20
# engine artefacts the APK cannot hold: 13
# every row matches the archive
ok: engine-artefacts.json matches the pinned APK
── self-test: flipping in_apk on the setuprc row (it must now FAIL) ──
ok: self-test: the broken table fails, as it must

$ # and the failure watched directly, by claiming liblibpng.a ships:
MISMATCH: lib/arm64-v8a/liblibpng.a: in_apk=True but archive says False
  (needed for: static link into libandroidapp.so + per-ABI configure gate;
   configure.ac:930,1107; CMakeLists.txt.in:157)
FAIL: 1 row(s) disagree with the archive
EXIT=1
```

Not wired into CI: it needs the network and it answers a decision that is now
made. Run it by hand if the question is reopened.

---

## 10. Inspected directly vs read from build files

**Inspected directly (bytes fetched and parsed here):**

- ZIP central directory of the pinned APK — 6,375 entries, sizes, compression
  methods, local-header offsets. 591,403 bytes fetched of 267,216,449.
- ELF headers, section headers, `.dynsym`, `.dynstr` and `.dynamic` of
  `liblo-native-code.so`, by HTTP range **inside** the APK (the member is
  STORED, method 0). 365,655 bytes fetched of 199,472,488 = 0.18 %.
- `libandroidapp.so` in full (6,322,632 bytes; STORED) — symbol tables,
  `DT_NEEDED`, and the embedded configure command line and version strings.
- `assets/program/versionrc`, `assets/program/bootstraprc`, `assets/notice.txt`,
  `assets/share/registry/main.xcd`, `classes.dex` — extracted individually by
  range request and read.
- `android-ndk-r29-linux.zip` central directory — 10,305 entries; toolchain host
  prefixes.
- Collabora's F-Droid `index-v1.json` — retention, hashes, `sourceCode`,
  `license`, signer fingerprint.
- MPL-2.0 text, §1.6, §2.3, §3.1, §3.2, §3.4.
- `git ls-remote https://gerrit.collaboraoffice.com/core` — 15,301 refs.
- Gerrit REST commit metadata for `core@c1013fad…` and `online@794af008…`.
- The `configure.ac` gates, replayed as shell against a fabricated
  `$LOBUILDDIR` and watched to pass on a zero-byte file.
- The tester, watched to pass and watched to fail.

**Read from build files at `online@794af008` (Gerrit REST):**

- `configure.ac` (3,057 lines), `android/lib/src/main/cpp/CMakeLists.txt.in`,
  `android/lib/build.gradle`, `android/app/build.gradle`,
  `android/lib/libSettings.gradle.in`, `android/lib/src/main/cpp/androidapp.cpp`,
  `kit/Kit.cpp`, `kit/Kit.hpp`, `engine/include/COKit/COKit.hxx`,
  `android/README.md`, `engine/README.md`, `android/.gitignore`.
- `core@c1013fad`: `include/LibreOfficeKit/LibreOfficeKit.h` (for the contrast in
  §4 only).
- `ac_cloud-office/build.json` and its state before `05b0c6c15`.

**Not established:** the ancestry of `20a46c332c38…` relative to `794af008…`
(needs a full monorepo clone). *The `.rodata` question that stood here is now
answered — see §11.*

**Not reachable:** Gerrit gitiles (`/plugins/gitiles/...`) — 302s to a GitHub
OAuth login page on every path. The REST API was used instead and is quoted
throughout.

---

## 11. Second pass: the whole APK, not a range request (2026-09-10)

§1-§10 were written from the ZIP central directory and 0.18 % of the engine,
read over HTTP ranges. This section re-checks the load-bearing claims against
**all 267,216,449 bytes**, downloaded once and deleted afterwards.

Provenance, checked two ways rather than assumed: the bytes published as our own
`Cloud-Sheets.apk` hash to
`b7ab381de96f429c2e762d0e54ee0fac7db03ad2fecfede9f4c558c767d18513`, which is
byte-for-byte the `hash` field Collabora's F-Droid `index-v1.json` publishes for
`collabora-office-mobile-26.04.3.1-155-release-arm64-v8a-2026-09-03.apk`. Two
independent sources, one hash. **The artefact on our `latest` release is
Collabora's APK unmodified** — its manifest declares `com.collabora.libreoffice`
and it is signed `META-INF/COLLABOR.RSA`, subject *Collabora Productivity
Limited*. That is the mirror artefact from before `05b0c6c15`; it is stale, it is
not what `build.json` describes any more, and it is the likely mechanism behind
the "installs silently / reported outdated" reports.

### Confirmed, from the full archive

| claim | §  | verified |
|---|---|---|
| 6,375 central-directory entries | 2 | 6,375 |
| `liblo-native-code.so` 199,472,488 bytes, STORED | 2 | exact |
| `libandroidapp.so` 6,322,632 bytes, STORED | 2 | exact |
| zero `.a` and zero `.h`/`.hxx` anywhere | 2 | 0 of 6,375 |
| no `setuprc` / `setup.ini` | 3 | absent |
| `assets/program/` holds exactly six files | 3 | exact |
| `buildid=20a46c332c38` | 4 | exact |
| F-Droid keeps 3 versions × 4 ABIs | 6 | 12 entries |
| `license: Unknown`, `sourceCode: None` | 5 | exact |

Every one of the 15 `lib/arm64-v8a/*.so` files is `ELF64`, `e_machine=0xB7`
(AArch64). `target_link_libraries(androidapp …)` re-fetched from
`online@794af008` still lists the eight `.a` archives at `CMakeLists.txt.in:154-167`.
**§2's conclusion holds on the full archive: an APK is a runtime container and
this one carries no link-time half.**

### Three corrections

**1. The ABI limit is not real.** §6 and the surrounding discussion imply
arm64-v8a is all upstream offers. It is not — the same `26.04.3.1-155` release
ships four ABIs, and `x86_64` is the largest of them:

| ABI | bytes | sha256 |
|---|---|---|
| arm64-v8a | 267,216,449 | `b7ab381de96f429c2e762d0e54ee0fac7db03ad2fecfede9f4c558c767d18513` |
| x86_64 | 278,455,828 | `58f44ce7492fc1ad3e543accd0620d15dd6eabcdc7839b95e60b00589618cacb` |
| armeabi-v7a | 199,722,591 | `85b12a5dcd1c02bbaef86c90a9ee71bb65f9145a88a02d9dfadc346fd3321151` |
| x86 | 254,285,287 | `f0a492219d20a10c6bc0afe99a34dcd7d7fecb1caa7c2ecf12ef1e94fad753fe` |

The *pinned* APK carries one ABI because `build.json::release.variants` asks for
one. ABI coverage is therefore not an argument against reuse in either
direction, and it is not a reason to fund anything.

**2. The trademark blocker in §5 is overstated.** It was argued that the product
name is "baked into the engine's own artefacts" and cannot be fixed. Both halves
of that are weaker than stated:

- `assets/share/registry/main.xcd` is **plain XML**, and holds exactly one
  `ooName` value (`Collabora Office`) and one `ooVendor` value (`Collabora
  Productivity Limited`). Editing them is a Modification of Covered Software,
  which MPL-2.0 §2.1 and §3.2 expressly permit; it is not "rewriting a compiled
  artefact".
- Scanning **100 %** of `liblo-native-code.so` — the check §10 listed as not
  established — finds `Collabora Office` exactly **twice**, and neither is a
  product identity: one is a Calc help string ("You may automate formula
  extension in Collabora Office Calc / General settings."), one is an MPL
  licence header inside embedded source. `Collabora Productivity` appears
  **zero** times. The engine binary carries no vendor identity to strip.

The `--with-app-name=Collabora Office` / `--with-vendor=Collabora Productivity
Limited` configure line does appear in `libandroidapp.so` — but that is the
*shell*, which is rebuilt under every plan, so it becomes ours.

Trademark is a **checklist item, not a blocker**. It is still real: MPL-2.0 §2.3
grants no trademark rights, so shipping unedited would misidentify our app's
engine. It is satisfied by editing two XML values.

**3. §4's vtable argument does not reach the strongest version of the idea.**
§4 is correct that pairing *Collabora's engine* with *our rebuilt
`libandroidapp.so`* crosses an unguarded 119-slot vtable. But there is a variant
it does not address: reuse **both** `.so` files verbatim — they were built
together, so the vtable is consistent by construction — and rebuild only
`classes.dex` and `assets/dist`.

That variant matters because **patch 0001 touches no engine file and no C++ at
all**. Its nine files are three browser sources
(`Control.Menubar.ts`, `CanvasTileLayer.js`, `Clipboard.js` → `assets/dist/`,
which ships as 3,384 asset entries), four Android Java/manifest/gradle files
(→ `classes.dex`), and `settings.gradle`. Nothing in Text Enhance needs the
engine recompiled. So the honest answer to "does the shortcut deliver the
feature" is **yes, it could** — #224's rebrand, #225's Drive integration and
#236's canvas text-input path are all reachable without touching the engine.

### Why the answer is still no

Not impossibility — cost of ownership. To rebuild `classes.dex` at all, gradle
runs `android/lib`'s `externalNativeBuild`, which is the CMake target that needs
the eight missing archives. Skipping it means deleting `externalNativeBuild` and
pointing `jniLibs.srcDirs` at extracted binaries: **a permanent fork of
upstream's build system**, carried forever, to save one $35 compile once. Added
to that, and unchanged by this pass:

- We would redistribute a 199 MB binary whose source commit we cannot name.
  `buildid=20a46c332c38` is 12 characters; Gerrit's REST API refuses abbreviated
  shas, and the GitHub mirror returns **HTTP 422** for it — and for our own
  pinned `794af008` too, because the 26.04 mobile line exists only on Gerrit.
  MPL-2.0 §3.2(a) is probably satisfiable by pointing at the branch plus the
  buildid, but we would be asserting it unverified on every APK we ship.
- The pinned URL is pruned after about three releases (12 index entries).
- Upgrades stay a guessing game with no assertion available (§7), and we could
  never fix an engine bug or take an engine security patch except by swapping
  the whole binary and hoping.

**Recommendation unchanged: compile. Fund #240.** The reuse path is cheaper once
and more expensive every time after.

### Also true, and separate from this decision

`ac_cloud-office/build.json::build.host` is `null`, so nothing builds Cloud
Office today, and the `latest` release still serves Collabora's own signed APK
under the name `Cloud-Sheets.apk` while `release.gh_release.asset_name` now says
`Cloud-Office.apk`. Whatever is decided about #240, that stale artefact should
be pulled — a phone auto-updating from `latest` is installing Collabora Office,
not ours.

---

## 12. Second question: the one that decides the money (2026-09-11)

§1-§11 answer **"can the engine be taken out of the APK and a new application
built around it from our own sources?"** and the answer is still no, on the
evidence given: the link-time half is absent (§2), `configure`'s build-directory
check is not satisfiable (§3), and the COKit seam is a 119-slot vtable with no
version guard (§4).

**Every one of those is an objection to REBUILDING `libandroidapp.so`. None of
them is an objection to leaving it alone.** That is the question this section
asks, and it has a different answer.

### 12.1 There are two native halves, not one

Read from the downloaded artefact, not from the central directory:

| entry | bytes | what it is |
|---|---|---|
| `lib/arm64-v8a/liblo-native-code.so` | 199,472,488 | **layer 1** — the LibreOffice engine. The thing #240 would pay to compile. |
| `lib/arm64-v8a/libandroidapp.so` | 6,322,632 | **online's own C++** — `wsd/`, `kit/`, `net/`, `common/`, built by `android/lib/src/main/cpp/CMakeLists.txt.in`. |
| 13 further `.so` | 8,414,760 | NSS, sqlite3, `libc++_shared`. Engine dependencies, copied. |

§2's eight static archives and four header trees exist for exactly one purpose:
linking `libandroidapp.so`. Nothing else in the tree consumes them. The same is
true of §4's vtable: the COKit seam is **between these two binaries**, and
`${LOBUILDDIR_ABI}/include` is on `libandroidapp.so`'s include path and nowhere
else.

### 12.2 Nothing the owner asked for is native

`ac_cloud-office/patches/0001` is 591 lines across nine files:

```
android/app/src/main/AndroidManifest.xml                            |  17 +-
android/lib/build.gradle                                            |   6 +
android/lib/src/main/assets/cloud_text_enhance.json                 |  new
android/lib/src/main/java/org/libreoffice/androidlib/CloudTextEnhance.java | new
android/lib/src/main/java/org/libreoffice/androidlib/LOActivity.java |  ...
android/settings.gradle                                             |  12 +-
browser/src/control/Control.Menubar.ts                              |  31 +-
browser/src/layer/tile/CanvasTileLayer.js                           |  17 +
browser/src/map/Clipboard.js                                        |  14 +
```

No `.cpp`, no `.h`, no `.mk`, no `CMakeLists`. The `LOActivity.java` hunks add
Java branches; they declare, remove and reorder **no `native` method**, and JNI
binds by mangled name (`Java_org_libreoffice_androidlib_*`, 13 such symbols in
the shipped `libandroidapp.so`) rather than by slot.

So `libandroidapp.so` does not have to be relinked — and the moment that is
true, §2's missing link-time half stops being required and §4's unguarded
vtable stops being a risk, because **both sides of that seam are taken as
Collabora's own matched pair, built together by their build**. The pairing §4
warns against — their engine against our freshly compiled shell — is the one
thing this shape never does.

Checked on every run by `ac_cloud-office/tests/test-no-engine-build-required.sh`,
which fails if a native file ever enters the series.

### 12.3 The mechanism, in upstream's own gradle

`android/lib/build.gradle`, in the `android { }` block:

```groovy
    sourceSets {
        main {
            // let gradle pack the shared library into apk
            jniLibs.srcDirs = ['src/main/cpp/lib']
        }
    }
```

A `.so` in a `jniLibs` source directory is **packaged, never compiled**. And
`CMakeLists.txt.in` does not produce the engine either — it copies it:

```cmake
set(LIBLO_NATIVE_CODE ${LOBUILDDIR_ABI}/android/jniLibs/${ANDROID_ABI}/liblo-native-code.so)
...
COMMAND ${CMAKE_COMMAND} -E copy ${LIBLO_NATIVE_CODE} "${CMAKE_CURRENT_SOURCE_DIR}/lib/${ANDROID_ABI}"
```

**Upstream already treats the engine as an external prebuilt binary.** The
megabuild's entire contribution to the APK is filling one directory. We fill the
same directory from the sha256-pinned artefact.

### 12.4 Layer 2 — the COOL web UI needs no engine build at all

`#224`'s menu item is `{name: _('Text Enhance'), id: 'cloudtextenhance', type:
'action'}` in `browser/src/control/Control.Menubar.ts`. That compiles into
`assets/dist/bundle.js` — 10,465,815 bytes, one of 3,384 entries under
`assets/dist/` in the pinned APK — through
`make -C browser DIST_FOLDER=... BUNDLE=RELEASE`, wired by
`android/lib/build.gradle`'s `generateCoolReleaseAssets`. That is `tsc` plus
webpack: Node, minutes, free.

`browser/Makefile.am` names a *built* engine in exactly one variable,
`ENGINE_WORKDIR = @LO_PATH@/../workdir` (line 117), and uses it in exactly one
place: the `npm test` mocha target (line 1682). Every other engine reference is
`ENGINE_SRCDIR` — engine **source**, which a clone of the monorepo already
contains, and which each recipe guards with `test ! -d "$(ENGINE_UI_XCU_DIR)" ||`
so it no-ops when absent. Both facts are asserted, with the count pinned at one,
by the tester.

### 12.5 What is genuinely still in the way, and it is not a compile

`configure.ac:884` refuses `--enable-androidapp` without `--with-lo-builddir`,
then probes the named directory for `instdir/program/setuprc` (`:921`),
`workdir/LinkTarget/StaticLibrary/liblibpng.a` (`:930`) and
`workdir/UnpackedTarball/poco/include/Poco/Poco.h` (`~:1009`). None is in an
APK. **Manufacturing a directory of decoys to satisfy an existence probe is the
hack this repository forbids**, so the fix is a declared option carried as
`patches/0002`:

- `--with-prebuilt-engine=<dir>` — the unpacked APK. Accepted in place of
  `--with-lo-builddir`, and it switches off the per-ABI archive probes rather
  than faking them.
- `CORE_VERSION_HASH` read from `assets/program/versionrc`, which the APK **does**
  contain: `buildid=20a46c332c38`, already the 12-character form `configure.ac:927`
  derives. §0's open question — is that commit an ancestor of our pin — stops
  mattering, because the engine's ancestry is no longer a build input.
- The `androidapp` CMake target skipped, and `jniLibs.srcDirs` left pointing at
  the extracted directory.
- The `copyUnpackAssets` / `copyAssets` / `createFullConfig` tasks sourced from
  the extracted `assets/` instead of `${liboInstdir}`.

**This is not written.** `build.host` stays `null` until it is, and Cloud Office
still does not build today.

### 12.6 §5's trademark blocker survives, and is smaller than it looked

§5 is right that `assets/share/registry/main.xcd` — an engine asset — carries

```xml
<oor:component-data oor:name="Setup" oor:package="org.openoffice"><node oor:name="Product">
  <prop oor:name="ooName"><value>Collabora Office</value></prop>
  <prop oor:name="ooVendor"><value>Collabora Productivity Limited</value></prop>
```

and that `--with-app-name` cannot reach it. But §5 calls the fix "rewriting
another vendor's compiled artefacts", and that is not what this is:
`main.xcd` is 1,678,077 bytes of **plain XML**, the two strings occur **once
each**, and `share/registry/` is a layered configuration store that already
ships an override directory in this very APK (`assets/share/registry/res/`,
2 entries). Setting `/org.openoffice.Setup/Product/ooName` from an overlay
generated out of `build.json` is a declarative build step of the same kind
`--with-app-name` performs upstream.

Two further trademark reaches are in the artefact and are **not** covered by
that: `assets/dist/images/collabora-office-white.svg`, `full-logo.svg`,
`full-logo-white.svg` and `toolbar-bg-logo*.svg`. Those are layer 2, they are
regenerated by the `browser/` build we are already running, and upstream's
`APP_BRANDING_DIR` / `liboHasBranding` knobs are the supported way to replace
them.

**MPL-2.0 §3.2(a)**, §5's genuine doubt, is unchanged in substance and improved
in one respect: `build.json::upstream.engine.engine_revision` now records where
the buildid is read from, so the source offer points at
`distro/collabora/co-26.04` plus `20a46c332c38` from a file rather than from
memory. §3.4 notices are satisfied exactly as §5 found — `assets/license.html`
and `assets/notice.txt` come across with the assets.

### 12.7 What the licence-boundary guard should have

`1_cicd/src/data/licence-boundaries.json` has one upstream, `affine`, and no
Collabora entry — so it would not catch anything here either way. It also could
not: its model is **source copyleft** (`restricted_dirs` that must not be
vendored, `markers` that must not be named), and Collabora Online is uniformly
MPL-2.0, so every one of those fields would be empty or false. The boundary that
actually binds Cloud Office is **trademark, in the signed artefact**:

> `collabora-online` — permissive `MPL-2.0`, restricted `Collabora and
> LibreOffice trade marks`. Forbidden **in the APK we sign**, not in the tree:
> `assets/dist/images/collabora-office-white.svg`, `full-logo.svg`,
> `full-logo-white.svg`, `toolbar-bg-logo*.svg`, and the values `Collabora
> Office` / `Collabora Productivity Limited` at
> `/org.openoffice.Setup/Product/{ooName,ooVendor}` in
> `assets/share/registry/main.xcd`.

**The entry is not added here**, because adding it to the present schema would
be a lie in both directions: `restricted_present()` would report vendored MPL
source as a licence violation, which it is not, and nothing would ever scan an
APK, so it would report clean on the one artefact that matters. The guard needs
an artefact mode — a scan of the built APK's entry names and of named config
values — before the entry means anything. That is one new field
(`boundary_kind`) plus an APK reader, and it is a decision about a legal model
that belongs to the owner, not a silent schema extension.

### 12.8 Cost

**Zero dollars.** No NDK, no `external/` tarball tree, no engine build host, no
megabuild. A GitHub-hosted `ubuntu-latest` runner with the Android SDK and Node:
one 267 MB download, a `browser/` bundle build, a Gradle `assembleArm64-v8aRelease`
that mostly repackages, and a signature. CI minutes only.

`#240` becomes necessary only in cases nobody has asked for: changing a line of
LibreOffice core, taking an engine security fix Collabora has not yet shipped as
an APK, or adding a second ABI. For `#224` and `#225` it is not needed at all.

### 12.9 Verdict

**§1-§11's verdict stands for the question they asked, and is not the answer to
the question that was being paid for.** The engine cannot be salvaged into a
new application; it does not have to be. Leave both native libraries exactly as
Collabora built them, rebuild the two layers above them, and Cloud Office is a
free build — once `patches/0002` exists.
