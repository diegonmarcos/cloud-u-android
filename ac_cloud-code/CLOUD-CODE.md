# cloud-code — Acode, vendored, with a 7-tab bottom nav (#562)

Owned clone (not a fork: #253/#469) of
[Acode-Foundation/Acode](https://github.com/Acode-Foundation/Acode) at
`b7dbe3d69dcf5165e19581fe3927f0838dce09f4`, with its one git submodule
(`codemirror-lsp-client` @ `db6930c2b6db67e14978f08ae8f5aeb149d29468`) inlined.
No `.git`, no upstream remote. Pins live in `build.json::upstream`.

## Name

The brief proposed `ac_cloud-ide-acode`. That collides with the brand #561
retired: `test-app-names-pattern.sh` T9 fails any build.json value matching
`cloud[-_ ]?ide` as a whole word. The app is **cloud-code** (`ac_cloud-code`,
`com.diegonmarcos.code`, `Cloud-Code.apk`, GHCR `cloud-code`).

## Licence (the brief said "Acode is GPL — verify")

Measured at the pinned revision, it is **not**:

| Part | Licence | In this tree |
|---|---|---|
| Acode (`license.txt`, `package.json`) | MIT | vendored |
| `codemirror-lsp-client` | MIT | vendored |
| `src/plugins/admob`, `cordova-plugin-buildinfo` | MIT | vendored (AdMob never built: paid flavour) |
| `src/plugins/ftp`, `src/plugins/sftp` | Apache-2.0, ftp4j LGPL-2.1 | vendored |
| `src/plugins/proot`: prebuilt `libproot*.so` (GPL-2.0), `libtalloc.so` (LGPL-3.0), `libaxs.so`, three `alpine.rootfs` images | GPL/LGPL **binaries without source** | **PRUNED** |

The proot plugin was the only GPL content, and it was 21 MB of binaries with no
corresponding source. Redistributing those from a public repo and APK would
carry the GPL's source obligation. Pruning them costs nothing here: upstream's own
F-Droid flavour removes that plugin, and the terminal need is served by
the MyTerminal tab. `test/test-cloud-nav.sh` keeps them out: no `*.so` or
`*.rootfs` anywhere, and every `build.json::upstream.pruned` path stays absent.

The fleet-level guard #239 requires of every vendored upstream is the `acode`
entry of `1_cicd/src/data/licence-boundaries.json` (run by `licence-guard.yml`
on every push): it finds this tree by shape, fails if `src/plugins/proot`
returns, and fails if `package.json`, `package-lock.json` or `config.xml`
name the plugin again. Case 11 of `licence-boundary-guard.test.sh` holds it
red against upstream's pristine `package.json` and green against ours.

## What differs from upstream (the whole list)

1. `.github/`, `src/plugins/proot/` removed; proot dropped from `package.json`
   (`devDependencies`, `cordova.plugins`) and `package-lock.json` (3 entries).
2. `.gitmodules` removed, submodule inlined; its `.gitignore` no longer hides
   `dist/`, which upstream commits and `package.json` points `main` at.
3. `.gitignore`: `/build.json` un-ignored. It is the fleet declaration here, and
   the engine hands Cordova an explicit empty `--buildConfig` instead.
4. `build-extras.gradle`: applicationId/version from `build.json` in
   `androidComponents.onVariants`. The widget id stays `com.foxdebug.acode`
   because it is also the Java namespace, and plugin sources import
   `com.foxdebug.acode.R`.
5. `src/plugins/system/plugin.xml`: the FileProvider authority was the literal
   `com.foxdebug.provider`, so installing next to a real Acode would be refused by
   Android (`INSTALL_FAILED_CONFLICTING_PROVIDER`; the Android rule, not observed here). It is now `${applicationId}.foxdebug.provider`
   (nothing referenced the literal).
6. `src/lib/settings.js`: first run no longer adopts `navigator.language` (#299,
   English base). The default `en-us` stands; Settings still offers every language.
7. `src/main.js`: **the seam**, one import plus one `mountCloudNav()` after
   `root.appendOuter(...)`.
8. New: `src/cloud/` (the nav), `tools/resolve-targets.py`, `test/`,
   `build.json`, `build.sh` (vendored engine), this file.

The editor itself is untouched.

## Flavour

Built as upstream's `paid dev apk fdroid`. **Paid** means no AdMob. **fdroid** means no
proot and no Google Play Billing (`cordova-plugin-iap` removed after setup), and
`hooks/post-process.js` pins targetSdk 28. These are upstream's own axes, declared in
`build.json::upstream.flavor` and reproduced by `cloud-code-engine.sh`.

## The nav

`src/cloud/nav.json` is the one declaration. The tabs, in order, are
Backlog | Editor | Repos | Home | Agents | Browser | MyTerminal.

- **Editor**: Acode, untouched. Selecting it hides every cloud panel.
- **Backlog**: renders `cloud-data-my-ai-memory/1.1.Product-Backlog/dist/*.md`
  as-is and follows the files' own "Views:" links. That repo is private and this
  APK is public, so it is read from the owner's on-device clone (the folder can be
  changed in the tab). Nothing is re-derived.
- **Agents**: the SAME source as Backlog, by design. The backlog engine
  derives task-effort, task-complexity and agent-model per task and curates
  agent-slot, and emits them as its own "agent batches" and "per agent-model"
  views; this tab renders `nav.json::agents.entry` through the one renderer
  Backlog uses, from the same folder. Only the live half (running status,
  token use) is behind `seams.js::listAgents`, and that is the stub.
- **Repos / Home**: UI over `src/cloud/seams.js`, where every provider
  is a `SEAM:`-marked function. Repos is a small real engine (children of a root
  that hold `.git`, tap opens the folder in the editor). Home is a stub.
- **Browser**: local pages in Acode's own browser plugin (the one Run uses).
- **MyTerminal**: link-out. `nav.json::myterminal.target` is a fleet id
  (`cloud-myterminal`, #561). `tools/resolve-targets.py` derives package and
  launcher activity from that app's own `build.json` and manifest at build
  time, and fails the build if the id resolves to nothing.

### Fit (measured, not assumed)

7 tabs at the 360 px narrowest viewport is 51.4 px per tab, above the 48 px
touch floor (`tabs.js::clearsTouchFloor`, asserted by the tester). The label
"MyTerminal" at 10 px is wider than its tab, so labels are never truncated
(#565's rule). `fit()` measures every label against its own tab on the device, and
on any overflow the bar goes compact: icons everywhere, label only on the
active tab.

### Why not the fleet's Compose bottom nav (declared #565 exception)

`libs:bottomnav` is the one nav for **native** apps. This app is a Cordova web
shell, and its entire UI is DOM in a single WebView. A Compose view would need a
second Android window stacked over the editor. So the bar is built in Acode's own UI
layer: its theme variables, icon font, `fileSystem`, markdown-it, browser plugin
and `openFolder`. It follows the same behavioural rule (never truncate a label).

## i18n (#299)

English base: see (6) above, held by `test/test-cloud-nav.sh`. No module
entry goes in `1_cicd/src/i18n-policy.json`, because the Android project
is generated by Cordova at build time and there is no committed
`src/main/res/values/strings.xml` for the guard to read. The i18n guard refuses
entries for modules without one. Acode's UI strings are its own
`src/lang/*.json` catalogue.

## Not verified (needs a phone)

The WebView layout, the Backlog read from the device clone, the Repos listing
under scoped storage, and the MyTerminal launch.
