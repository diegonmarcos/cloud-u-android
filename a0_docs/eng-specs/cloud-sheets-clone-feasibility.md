# Cloud Office (Collabora) — cloning the Android shell, and what it costs

Status: **A PATH EXISTS. Awaiting owner decision on one paid, one-time build.**
Nothing was cloned, no build was started, no paid runner was enabled.

**Verdict in one sentence:** the LibreOffice engine and the Android shell are
separated by a *filesystem seam* in upstream's own build system — the shell
consumes the engine as a set of prebuilt `.so` files, `.a` files, headers and an
`instdir` data tree, not as source — so cloning and editing the shell is a
**days** project on free `ubuntu-latest`, gated behind **one** engine build that
does not fit on any machine the fleet currently owns.

> **EXTENDED, AND PARTLY SUPERSEDED, by
> `cloud-office-prebuilt-engine-reuse.md`.** That document answers a question
> this one did not ask — can we extract the prebuilt engine from the official
> APK instead of compiling it? (No.) In doing so it read the revision
> `build.json` actually pins, `online@794af008` on the 26.04 line, and found two
> things here that no longer hold on it:
> **§1's two repositories are now one monorepo** (the former core is `engine/`
> inside `online`), and **§2c's `configure` gate is `instdir/program/setuprc`,
> not `liblibpng.a`** (`configure.ac:921`; `liblibpng.a` is a second, per-ABI
> check at `:930`). §8's LibreOfficeKit `nSize` interface has also been replaced
> by a C++ vtable. Everything else here — the cost, the runners, the NDK, the
> MPL analysis, the recommendation — stands.

Investigated against Collabora's mobile branch
`CollaboraOnline/online@distro/collabora/co-25.04-mobile` and upstream's own
build guide, 2026-09-10.

---

## 0. Confidence, and what is *not* measured

Everything in §1–§4, §6–§9 is read from fetched files or live MCP output and is
quoted with its source. Three things are **not** measured and are marked
`[ESTIMATE]` where they appear:

- the on-disk size of a completed engine `workdir/` + `instdir/`,
- cold wall-clock for the engine build on a specific machine,
- incremental wall-clock.

`wiki.documentfoundation.org` is behind an Anubis bot-wall and returned a
challenge page to every fetch, including `?action=raw`. **The TDF wiki was not
readable and nothing here is sourced from it.** The substitutes used are better:
upstream's own build guide, `configure.ac`, the gradle/CMake files, and F-Droid's
production build recipe for the same code.

---

## 1. The build is three stages, not one

Source: <https://collaboraonline.github.io/post/build-code-android/>, which is
what `android/README` on the mobile branch points to (that README is 63 bytes and
contains only that URL).

| # | Stage | What it builds | Where |
|---|---|---|---|
| 1 | **Engine** | LibreOffice core → `liblo-native-code.so` + `instdir/` + Poco/zstd/libpng static libs | `engine/` in the Gerrit monorepo (was `core`) |
| 2 | **Online** | `libandroidapp.so` (coolwsd in-process) + `browser/` JS bundle | monorepo root, `./configure --enable-androidapp --with-lo-builddir=$CO_BUILDDIR` |
| 3 | **App** | the APK | `cd android && ./gradlew build` |

Stage 1 is the expensive one and is **Linux-only** by upstream's own statement:
"The native parts of the Android app cannot currently be built on Windows."

### ABIs

Upstream supports `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`. Each is a
**separate, full engine build** — `configure.ac:640` shows `--with-lo-builddir`
taking a colon-separated list of *four different build directories*, and
`CMakeLists.txt.in` selects `LOBUILDDIR_ABI` per ABI. So ABIs multiply the cost
linearly.

**We need exactly one: `arm64-v8a`.** Collabora publishes only that ABI
(`ac_cloud-sheets/build.json::release.variants[0]` and the pinned index entry),
and it is what the fleet's phones run. So the multiplier is ×1.

### Memory

`LibreOffice/core@master:android/README.md`, verbatim:

> "Building with all symbols is also possible but the linking is currently slow
> (around 10 to 15 minutes) and you need lots of memory (around 16GB + some
> swap)."

That is the debug/symbols case. It is the only RAM figure upstream publishes and
it is the one that decides whether a machine can finish the link at all.

### Wall-clock

F-Droid builds the *same* code (TDF's LibreOffice Viewer, `--with-distro=
LibreOfficeAndroidAarch64`, one ABI, `--enable-release-build`) on its production
buildserver. Its recipe sets, for the current release:

```yaml
  - versionName: 26.2.6.3
    versionCode: 131
    timeout: 43200          # ← 12 hours
    ...
      - ./autogen.sh ... --with-distro=LibreOfficeAndroidAarch64
```

Source: `metadata/org.documentfoundation.libreoffice.yml` in `fdroid/fdroiddata`.

**A production packager gives this build a 12-hour ceiling.** GitHub-hosted
runners terminate any job at **6 hours**
(`github/docs:content/actions/reference/limits.md` — "All GitHub-hosted runners |
Job execution time | 6 hours"). The cold engine build does not fit, and that is
not a judgement call — it is one published number against another.

Incremental: `[ESTIMATE]` — irrelevant to the recommendation, because the
recommended path never rebuilds the engine on a push.

### Disk

Measured inputs, before a single object file is written:

| Component | Size | How measured |
|---|---|---|
| `LibreOffice/core` full git clone | **6.7 GB** | GitHub API `repos/LibreOffice/core.size` = 7,027,418 KB |
| `core` source tree, shallow (`.tar.gz`) | **0.33 GB** compressed | downloaded `codeload.github.com/LibreOffice/core/tar.gz/refs/heads/master`, measured |
| `external/` tarballs | **0.74 GB** compressed | summed `Content-Length` of all 132 tarballs in `download.lst` from `dev-www.libreoffice.org/src/` |
| Android NDK r28c linux zip | **689 MB** compressed | `Content-Length` on `dl.google.com` |
| `CollaboraOnline/online` clone | **0.43 GB** | GitHub API |

That is **~2.2 GB of compressed downloads** before anything is unpacked, and the
`external/` tarballs and the NDK both expand several-fold. `workdir/` + `instdir/`
on top: **`[ESTIMATE]`, not measured.** Against `ubuntu-latest`'s **14 GB SSD**
(4 vCPU / 16 GB RAM / 14 GB SSD, x64 — GitHub's published standard-runner table)
that is not credible, but the disk argument rests on an unmeasured term, so it is
**not** what this document concludes on. The 6-hour cap in §"Wall-clock" is
measured on both sides and is sufficient on its own.

---

## 2. THE FINDING: the shell can be built against a prebuilt engine

This is the whole answer, so here is the evidence rather than the conclusion.

### 2a. The engine crosses into the app as *files*, never as source

`android/lib/build.gradle` on the mobile branch:

```groovy
sourceSets {
    main {
        // let gradle pack the shared library into apk
        jniLibs.srcDirs = ['src/main/cpp/lib']
    }
}
```

and `android/.gitignore`:

```
/lib/src/main/cpp/lib
/lib/src/main/assets/program/
/lib/src/main/assets/share/
/lib/src/main/assets/unpack/
...
```

Every directory the engine contributes is **gitignored** — they are build
products dropped into the tree, and gradle packs whatever it finds.

`android/lib/src/main/cpp/CMakeLists.txt.in` says exactly what "dropped in"
means. The engine is not compiled here; it is **copied**:

```cmake
set(LIBLO_NATIVE_CODE ${LOBUILDDIR_ABI}/android/jniLibs/${ANDROID_ABI}/liblo-native-code.so)
...
add_custom_command(OUTPUT ".../lib/${ANDROID_ABI}/liblo-native-code.so"
   COMMAND ${CMAKE_COMMAND} -E copy ${LOBUILDDIR_ABI}/instdir/program/libnss3.so   "..."
   COMMAND ${CMAKE_COMMAND} -E copy ${LOBUILDDIR_ABI}/instdir/program/libsqlite3.so "..."
   ... 12 such copies ...
   COMMAND ${CMAKE_COMMAND} -E copy ${LIBLO_NATIVE_CODE} "..."
   COMMENT "Copied liblo-native-code.so and its dependencies to the tree.")

target_link_libraries(androidapp
   android log
   ${LOBUILDDIR_ABI}/workdir/LinkTarget/StaticLibrary/liblibpng.a
   ${POCOLIB_ABI}/libPoco{Encodings,Net,Util,XML,JSON,Foundation}.a
   ${ZSTDLIB_ABI}/libzstd.a
   ".../liblo-native-code.so")
```

### 2b. The native code the *shell* actually compiles is small

The same `CMakeLists.txt.in` lists the sources of `libandroidapp.so`:
`androidapp.cpp` plus ~40 files from `common/`, `kit/`, `net/`, `wsd/`. That is
the whole native compile on the shell side.

The size ratio, measured directly out of the pinned official APK by
range-fetching its ZIP central directory (no full download):

| Entry | Uncompressed bytes |
|---|---|
| `lib/arm64-v8a/liblo-native-code.so` | **199,472,488** (the engine) |
| `lib/arm64-v8a/libandroidapp.so` | **6,322,632** (the shell) |
| 12 × NSS / sqlite / nspr `.so` | 8,414,688 total |
| `assets/dist/bundle.js` | 10,465,815 (the `browser/` JS) |
| `classes.dex` + `classes2.dex` | 10,730,496 (the Java shell) |

**The engine is 96% of the native payload and it is one file.**

### 2c. `configure` accepts a directory, and validates it by file presence

`configure.ac:690` — the only thing that makes a path "a LibreOffice core build
directory":

```sh
if test \( "$enable_androidapp" = "yes" -a -f "$LOBUILDDIR/workdir/LinkTarget/StaticLibrary/liblibpng.a" \); then
    AC_MSG_RESULT([$LOBUILDDIR])
else
    AC_MSG_ERROR([This is not a LibreOffice core build directory: $LOBUILDDIR])
fi
```

plus `CORE_VERSION_HASH` read from `$LOBUILDDIR/instdir/program/setuprc`, and
`--with-poco-includes` / `--with-poco-libs` / `--with-zstd-includes` /
`--with-zstd-libs` given as explicit paths and checked with `CHK_FILE_VAR` for
`Poco/Poco.h`, `libPocoFoundation.a`, `zstd.h`, `libzstd.a`.

**Nothing requires `LOBUILDDIR` to be a live build tree.** An unpacked artefact
laid out at those paths satisfies every check. No engine source edit, no
`configure.ac` patch, no hack — this is the interface upstream designed.

### 2d. So: what a cached "engine SDK" artefact must contain

| Path in `$LOBUILDDIR` | Content |
|---|---|
| `android/jniLibs/arm64-v8a/liblo-native-code.so` | ~199 MB |
| `instdir/program/lib{freebl3,nspr4,nss3,nssckbi,nssdbm3,nssutil3,plc4,plds4,smime3,softokn3,sqlite3,ssl3}.so` | ~8 MB |
| `instdir/program/setuprc` | version hash |
| `instdir/{program,share}/…` | the asset trees `copyAssets`/`copyUnpackAssets`/`createFullConfig` read |
| `workdir/LinkTarget/StaticLibrary/liblibpng.a` | link + sanity check |
| `workdir/UnpackedTarball/libpng/` | headers |
| `include/` | LibreOfficeKit headers |
| Poco `.a` ×6 + headers, `libzstd.a` + `zstd.h` | link |

Order of **1–2 GB**. The fleet already publishes a 267 MB APK to
`ghcr.io/diegonmarcos/cloud-sheets` as an OCI artifact via ORAS
(`1_cicd/src/cicd/ship-cloud-sheets.yml`), so the publishing mechanism exists and
needs no new infrastructure. GHCR, not `actions/cache` — cache entries are
evicted and rate-limited; a GHCR tag is durable and content-addressed.

**Does upstream publish this artefact? No.** Collabora ships APKs through its
F-Droid repo and source through Gerrit; there is no engine SDK, no AAR, no maven
coordinate. We would produce it once, ourselves.

---

## 3. What the fleet actually has

Live, from `cloud-infra-mcp` (`obs_finops_all`, `obs_debug_vm_status oci-apps`),
2026-09-10 06:23 UTC.

| VM | Shape | Arch | Cores | RAM | Disk | Load | What runs there |
|---|---|---|---|---|---|---|---|
| **oci-apps** | OCI A1.Flex (free) | **aarch64** | 4 | 23.4 G (12.3 G used, 6.4 G avail, **swap 0**) | 144.3 G, **54.3 G free** | 1.26 | **60 containers** — gitea, mattermost, matrix, vaultwarden, photoprism, postgres ×4, all 9 MCP servers, crowdsec, dagu, borg, and this agent |
| oci-mail | OCI E2.1.Micro (free) | aarch64 | 1 | 954 MB (82% used) | 45 G | 0.43 | stalwart, maddy, mail-puller |
| oci-analytics | OCI E2.1.Micro (free) | aarch64 | 1 | 954 MB (77% used) | 48 G | 0.19 | umami, matomo feed, c3-public-api, dns64 |
| gcp-proxy | GCP E2-micro (free) | x86_64 | 0.25 | 944 MB (81% used) | 29 G | 0.64 | caddy, authelia, hickory-dns, wg hub |
| gcp-gpu-embed | GCP g2-standard-4 + L4 | **x86_64** | 4 | 16 G | 100 G | — | **on-demand, STOPPED by default**; started/stopped around `cgc-db-index.yml` via `devops_vm_start`/`devops_vm_stop` |

Self-hosted runners already exist: `a_solutions/infra-bld_gha-runner/build.json`
registers `oci-apps-arm64`, `oci-apps-arm64-unix`, `oci-apps-arm64-data` with
labels `self-hosted,linux,arm64,oci-apps`, running
`myoung34/github-runner:ubuntu-noble` on oci-apps. All three containers are up.
Its own description: *"ARM64, no 6h timeout cap"* — self-hosted job limit is
**5 days** (`limits.md`).

### The disqualifier for every fleet VM

**Google publishes no aarch64-Linux NDK.** Verified two ways:

1. `dl.google.com/android/repository/repository2-3.xml` — every NDK from r23
   onward offers exactly one Linux archive (`android-ndk-r28c-linux.zip`); the
   only `<host-arch>aarch64</host-arch>` entries in the whole file pair with
   `darwin` (Apple Silicon).
2. Range-fetched the tail of `android-ndk-r28c-linux.zip` (the exact version
   `android/lib/build.gradle` pins as `ndkVersion "28.2.13676358"`) and parsed
   its central directory: the only toolchain prefix present is
   `toolchains/llvm/prebuilt/**linux-x86_64**/`.

Four of the five fleet VMs are aarch64. **They cannot run the Android toolchain
at all**, except under qemu-user emulation, which turns a 12-hour build into a
multi-day one. oci-apps is not a slow option for the engine build; it is not an
option.

Even setting the NDK aside, oci-apps fails on memory: upstream wants ~16 GB + swap
for the link, and oci-apps has **6.4 GB available and zero swap** while carrying
60 production containers. The fleet's own protection stack would fire first —
`config.json::protection` sheds load at `mem_psi_crit: 50` / `mem_psi_page: 65`.
The precedent for long jobs strangling the fleet is on record: `cgc-db-index.yml`
carries the comment *"the 5.5-hour index matrix held the lock too"*, describing a
day of container deploys silently not happening.

---

## 4. Recommendation

**Build the engine ONCE on a rented x86_64 machine, publish the ~1–2 GB SDK
artefact to GHCR, and build the shell on free `ubuntu-latest` forever after.**

Concretely: two workflows, mirroring how `ship-cloud-sheets.yml` already works.

- `engine-cloud-sheets.yml` — `workflow_dispatch` only, never on push. Builds
  stage 1 for `arm64-v8a` at a pinned Collabora commit, tars the paths in §2d,
  ORAS-pushes to `ghcr.io/diegonmarcos/cloud-sheets-engine:<core-hash>`, emits a
  `.sha256` sidecar. Runs maybe twice a year.
- `ship-cloud-sheets.yml` (rewritten) — `ubuntu-latest`, on push. Pulls the
  engine artefact, verifies its sha256, unpacks it to `$LOBUILDDIR`, runs
  `./configure --enable-androidapp --with-lo-builddir=… --with-app-name=…
  --with-android-package-name=… --with-app-branding=…`, `make`, `./gradlew
  assembleRelease`, signs with the fleet keystore, publishes as today.

The second workflow compiles ~40 C++ files, a webpack bundle and some Java. It
fits in 14 GB and finishes in tens of minutes. `[ESTIMATE]` on the exact figure —
but its inputs are 0.43 GB of online source + a 1–2 GB artefact + the NDK, and
its outputs are the 6.3 MB `libandroidapp.so` and a bundle.js, so the bound is not
close.

### Where the one-time engine build runs — and why the alternatives lost

| Option | Verdict |
|---|---|
| **oci-apps self-hosted runner** | **Impossible.** No aarch64-Linux NDK exists. Also 6.4 GB free RAM, no swap, 60 production containers, and a documented history of long jobs holding the fleet deploy lock. |
| oci-mail / oci-analytics / gcp-proxy | Impossible. aarch64 and/or ~950 MB RAM. |
| `ubuntu-latest` (free) | **Impossible.** 6-hour job cap against F-Droid's 12-hour timeout for the same build. Disk (14 GB SSD vs ~2.2 GB of compressed inputs plus an entire build tree) almost certainly fails too, but that term is unmeasured — the time cap alone settles it. |
| `ubuntu-24.04-arm` (free, already used 3× in this repo) | Impossible. Same NDK problem. |
| **GitHub larger runner, `linux_16_core`** | **Possible but still capped at 6 hours.** 16 vCPU / 64 GB / 600 GB fixes RAM and disk; 4× the cores may or may not bring a 12-hour-budgeted build under 6h. Genuinely uncertain — a 6-hour timeout that fires at hour 5:55 wastes the whole spend. |
| **A rented VM we start and stop (recommended)** | **No time cap at all.** The fleet already does exactly this with `gcp-gpu-embed`: an on-demand x86_64 GCP instance, declared in `c_vps/vps_gcloud/src/terraform.json`, STOPPED by default, started and stopped around a workflow via `devops_vm_start`/`devops_vm_stop`. Declarative, already-proven, and the build can take 14 hours without anything failing. |

**Recommended: a temporary GCP instance, same pattern as `gcp-gpu-embed`, ~16
vCPU / 64 GB RAM / 200 GB disk, x86_64, destroyed when the artefact is pushed.**
It removes the only real risk in the plan (a timeout killing a nearly-finished
build) for less money than the larger-runner option.

---

## 5. Cost

**Money.** Both repos are **public** (`api.github.com/repos/…` → `visibility:
public`), so standard runners are free — but GitHub's pricing doc states plainly:
*"The hosted runners are not free for public repositories."* Rates
(`github/docs:content/billing/reference/actions-runner-pricing.md`):

| SKU | $/min | 12 h |
|---|---|---|
| `linux_16_core` | $0.042 | **$30.24** |
| `linux_16_core_arm` | $0.026 | — (no aarch64 NDK; unusable) |
| `linux_32_core` | $0.082 | $59.04 |
| `linux_8_core` | $0.022 | $15.84 |

A rented GCP instance of comparable size is on the order of **$0.60–0.80/hour
on-demand**, i.e. **~$8–11 for a 14-hour build**, or roughly a quarter of that on
Spot — and it cannot be killed by a 6-hour cap. Exact GCP list price was not
fetched and is `[ESTIMATE]`; it should be confirmed before spending.

Either way the one-time engine build is **under $35**, and the recurring cost of
every shell build afterwards is **$0** (free `ubuntu-latest`). Storage: one
1–2 GB GHCR artefact, free for a public repo.

**Time.** Engine build ~12 h wall-clock, unattended, once. Wiring the two
workflows and the clone: `[ESTIMATE]` 2–4 days of work. The three features the
owner asked for are hours each once the tree exists (§7, §8).

**The owner's standing rule applies: nothing paid is enabled here. This is the
number to say yes or no to.**

---

## 6. What is lost: the sha256 pin

Today `ship-cloud-sheets.yml` downloads Collabora's official APK and does this:

```sh
ACTUAL="$(sha256sum "$ASSET" | awk '{print $1}')"
[ "$ACTUAL" = "$EXPECT" ] || { echo "::error::…"; exit 1; }
```

A substituted or compromised upstream artefact **fails the job instead of
shipping**. That property is real and it does not survive cloning: once we build,
there is no upstream binary to compare against.

What replaces it, honestly:

1. **A pinned upstream source commit.** `build.json::upstream.commit` naming an
   exact Gerrit/GitHub sha, with CI asserting the checkout matches. This moves
   the trust boundary from "these bytes" to "this commit" — strictly weaker than
   a binary hash, because we cannot verify Collabora's commit the way we can
   verify their signed APK.
2. **A pinned sha256 on the engine artefact.** The GHCR engine SDK gets its own
   `.sha256`, verified on every shell build, exactly as the APK pin works today.
   This restores the pin property for 96% of the native payload — the part we
   build once and then only consume.
3. **Signature verification of the source.** Collabora's F-Droid repo publishes a
   fingerprint (`573258C84E149B5F4D9299E7434B2B69A8410372921D4AE586BA91EC767892CC`)
   for APKs, not for git. There is no upstream signing of the Gerrit tree to
   verify. This mitigation is **not available**.
4. **Cross-check against the official APK.** Optional and cheap: diff our built
   `liblo-native-code.so` against the one inside the pinned official APK. They
   will not be byte-identical (different build host, no reproducible-build
   guarantee upstream), so this is a smoke test, not a proof.

**Stated plainly: the "upstream cannot substitute bytes on us" guarantee is
downgraded from a verified binary hash to a pinned source commit plus a hash on
our own rebuild. That is a genuine loss and the owner should choose it
knowingly.** The second-publisher hazard in `ac_cloud-sheets/README.md` — a phone
on Collabora's own F-Droid repo reading as permanently outdated — *disappears*,
because a rebranded package id is no longer `com.collabora.libreoffice` and the
two no longer collide.

---

## 7. What is gained

All three things established as impossible while mirroring:

| Ask | How | Cost |
|---|---|---|
| **Rebrand** (launcher label, icon, splash, about screen) | Configure flags, **no source edit**: `--with-app-name=<name>` (`configure.ac:224`), `--with-android-package-name=` (`:202`), `--with-app-branding=<path>` (`:172`). `app/build.gradle::copyBrandFiles` copies `${liboBrandingDir}/android` over `src/main/res`; `lib/build.gradle::copyBrandTheme` copies `online-theme`. `--with-vendor` and `liboInfoURL` feed the about screen. | hours |
| **Text Enhance in the viewer menu** | See §8 — reachable from the shell. | days |
| **Config into the main menu** | `android/app/src/main/java/org/libreoffice/androidapp/SettingsActivity.java` (6.6 KB) and the `ui/` package are ordinary Java in the shell module. | hours |

Note that upstream *designed* the rebrand path. This is not a fork-and-patch; it
is a supported configure input, which is why re-cloning a newer upstream stays
cheap — exactly the property `ac_cloud-mail/VENDORING.md` protects ("the next
re-clone against a newer upstream is a diff over a handful of known files").

---

## 8. Text Enhance: reachable from the shell, no core change

The prior finding stands and is not contradicted: `TextEnhancer.target()` reads
through `RichInputConnection`, and Collabora's `contenteditable`
(`browser/src/layer/marker/TextInput.js`) never holds document text, so the
**keyboard** route is dead by construction.

But the shell has a different route, and it is already wired for the system
clipboard. `android/lib/src/main/java/org/libreoffice/androidlib/LOActivity.java`
declares four JNI natives:

```java
public native boolean getClipboardContent(LokClipboardData aData);   // :1372
public native void    setClipboardContent(LokClipboardData aData);   // :1374
public native void    paste(String mimeType, byte[] data);
public native void    postUnoCommand(String command, String arguments, boolean bNotifyWhenFinished);
```

All four are implemented in **`android/lib/src/main/cpp/androidapp.cpp`** — a
file in the shell's own CMake source list, compiled into `libandroidapp.so`, not
into the engine:

```cpp
Java_org_libreoffice_androidlib_LOActivity_getClipboardContent(...)
{ ... getLOKDocumentForAndroidOnly()->getClipboard(mimeTypes, &outCount, &outMimeTypes, &outSizes, &outStreams) ... }

Java_org_libreoffice_androidlib_LOActivity_postUnoCommand(...)
{ getLOKDocumentForAndroidOnly()->postUnoCommand(pCommand, pArguments, bNotifyWhenFinished); }
```

and `LOActivity.afterMessageFromWebView` already runs the read half on every
copy:

```java
case "uno":
    switch (messageAndParameterArray[1]) {
        case ".uno:Copy":
        case ".uno:Cut":
            populateClipboard();      // → getClipboardContent() → clipboardData.getText()/.getHtml()
```

So the path from **canvas selection → real document text** exists today and is
exercised every time the user copies:

1. `postUnoCommand(".uno:Copy", null, true)`
2. `getClipboardContent(…)` → `LokClipboardData.getText()` — the actual selected
   paragraph, not the sentinel buffer
3. hand it to `ab_cloud-libs-shared/libs/text-tools` (`ITextTools`, already served
   by `ac_cloud-keyboard`)
4. `setClipboardContent(…)` then `.uno:Paste` — which `beforeMessageFromWebView`
   already intercepts at `LOActivity.java:1104` — or `paste(mimeType, data)`
   directly.

**Every file in that chain is shell-side.** `liblo-native-code.so` is consumed
only through published LibreOfficeKit entry points (`getClipboard`,
`postUnoCommand`). **Text Enhance does not need the core, and the clone therefore
delivers what the owner wants.**

Caveat, same as the original note: **not verified on a device.** This is read from
upstream's `co-25.04-mobile` sources. Nobody has pressed the key.

---

## 9. MPL-2.0 — the obligations, not the permission

Settled and not re-litigated: MPL-2.0 permits cloning, modifying, rebranding and
redistributing. What attaches (MPL-2.0 text, §3):

- **§3.2(a) — source availability.** Distributing the APK obliges us to make the
  Source Code Form available *of the Covered Software including our
  modifications*, and to tell recipients how to get it, "at a charge no more than
  the cost of distribution". Our tree is a public GitHub repo, which satisfies
  this outright — **but only if the cloned tree is actually committed there.**
  This is the one obligation that constrains the plan: `ac_cloud-sheets/` must
  stop being an empty mirror directory and become a real, public source tree.
- **§3.4 — notices.** "You may not remove or alter the substance of any license
  notices … contained within the Source Code Form." Concretely: keep upstream's
  `COPYING`, `THIRDPARTYLICENSES`, `README.FILENOTICES.md` and every per-file
  MPL header. The APK already ships `assets/license.html` (376 KB) and
  `assets/notice.txt`, copied by `lib/build.gradle::copyAssets` from
  `${liboSrcRoot}/instdir/` — **do not disable that task.**
- **§3.3 — Larger Work.** Our additions (Text Enhance, fleet wiring) may be under
  terms of our choice; the Covered Software stays MPL-2.0. File-level copyleft,
  not project-level.
- **§3.1** — any modified MPL file we redistribute stays MPL-2.0.

**Must the rebranded app state its origin?** Not by §3 as such — MPL requires
notices, not attribution in the UI. But trademark: MPL-2.0 §2.3 grants no
trademark rights, so we must **not** ship as "Collabora Office" or "LibreOffice",
and must change `applicationId` off `com.collabora.libreoffice`. That is a
requirement of the rebrand, not an obstacle to it. Retaining the bundled
`license.html`/`notice.txt` (which name LibreOffice and Collabora) discharges the
notice obligation and states the origin as a side effect.

---

## 10. The concrete first step

If the owner says yes:

1. **Confirm the paid one-time build** — a temporary x86_64 GCP instance
   (~16 vCPU / 64 GB / 200 GB), same start/stop pattern as `gcp-gpu-embed`,
   destroyed after. Estimated **under $15**, one occurrence. *(Alternative if the
   owner prefers pure-GitHub: `linux_16_core` at $0.042/min, ~$30 for a 12-hour
   attempt, with a real risk the 6-hour cap kills it.)*
2. Then, and only then: pin a Collabora commit in `ac_cloud-sheets/build.json`,
   clone `android/`, `browser/`, `common/`, `kit/`, `net/`, `wsd/` and the build
   files into `ac_cloud-sheets/`, and write `engine-cloud-sheets.yml`.

Do **not** clone first. A cloned tree with no way to build it is exactly the
"half-wired project" failure mode `cloud-notes-affine-feasibility.md` was written
to avoid.

---

## Sources

- Collabora build guide — <https://collaboraonline.github.io/post/build-code-android/>
  (the target of `android/README` on `distro/collabora/co-25.04-mobile`)
- `CollaboraOnline/online@distro/collabora/co-25.04-mobile` — `configure.ac`,
  `android/lib/build.gradle`, `android/app/build.gradle`,
  `android/lib/libSettings.gradle.in`, `android/lib/src/main/cpp/CMakeLists.txt.in`,
  `android/lib/src/main/cpp/androidapp.cpp`,
  `android/lib/src/main/java/org/libreoffice/androidlib/LOActivity.java`,
  `android/.gitignore`
- `LibreOffice/core@master` — `android/README.md`, `download.lst`,
  `distro-configs/LibreOfficeAndroidAarch64.conf`
- `fdroid/fdroiddata` — `metadata/org.documentfoundation.libreoffice.yml`
  (`timeout: 43200`)
- `github/docs@main` — `content/actions/reference/limits.md`,
  `content/billing/reference/actions-runner-pricing.md`,
  `content/actions/reference/runners/larger-runners.md`
- `dl.google.com/android/repository/repository2-3.xml`; ZIP central directory of
  `android-ndk-r28c-linux.zip`
- ZIP central directory of the pinned
  `collabora-office-mobile-26.04.3.1-155-release-arm64-v8a-2026-09-03.apk`
- MPL-2.0 text, <https://www.mozilla.org/MPL/2.0/>
- `cloud-infra-mcp`: `obs_finops_all`, `obs_debug_vm_status oci-apps`
- `cloud-infra`: `config.json`, `a_solutions/infra-bld_gha-runner/build.json`,
  `1_cicd/src/cicd/cgc-db-index.yml`, `1_cicd/src/cicd/cgc-db.yml`
- `cloud-u-android`: `ac_cloud-sheets/{build.json,README.md}`,
  `1_cicd/src/cicd/ship-cloud-sheets.yml`, `ac_cloud-mail/VENDORING.md`
- **Not reachable:** `wiki.documentfoundation.org` (Anubis bot-wall, HTTP 200
  challenge page on every path including `?action=raw`).
