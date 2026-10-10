# cloud-code — Acode, vendored, with a 7-tab bottom nav (#562) and a Chat tab

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
8. New: `src/cloud/` (the nav, the app chrome, the Chat tab), `src/plugins/cloudchat/`
   (the Chat's native half, MIT, ours), `tools/resolve-targets.py`, `test/`,
   `build.json`, `build.sh` (vendored engine), this file.
9. `package.json` / `package-lock.json`: the one local plugin `cordova-plugin-cloudchat`
   (`file:src/plugins/cloudchat`), added the way upstream lists its own local plugins.

The editor itself is untouched.

## Flavour

Built as upstream's `paid dev apk fdroid`. **Paid** means no AdMob. **fdroid** means no
proot and no Google Play Billing (`cordova-plugin-iap` removed after setup), and
`hooks/post-process.js` pins targetSdk 28. These are upstream's own axes, declared in
`build.json::upstream.flavor` and reproduced by `cloud-code-engine.sh`.

## The nav

`src/cloud/nav.json` is the one declaration. The tabs, in order, are
Backlog | Editor | Repos | Chat | Agents | Browser | MyTerminal. Chat replaced Home (its
tiles duplicated the bar; its Configs card, the all-files grant, moved to Profile & Config).

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
- **Repos**: UI over `src/cloud/seams.js`, where every provider
  is a `SEAM:`-marked function. Repos is a small real engine (children of a root
  that hold `.git`, tap opens the folder in the editor).
- **Chat**: see "The Chat tab" below.
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

## App chrome

Every cloud panel opens with `src/cloud/chrome.js::topBar`: the **hamburger** (left) opens a
drawer of the ACTIVE page's own items (each renderer registers its provider in
`index.js::MENUS`; `tabs.js::menuFor` picks the active tab's and never another's), and
**Profile & Config** (right). Same theme variables as the bar, data-dense rows. libs:ui-kit's
top bar and drawer are Compose, so they cannot sit in this WebView (the #565 exception above);
this follows their shape: menu left, title, account right.

- Chat's drawer: New chat, Chats (history: Pinned / Today / Previous 7 days / Older), Search
  chats, Pinned, Export this chat (opens it as Markdown in an editor tab), Rename, Delete.
- Backlog / Agents: Reload, Back to the entry, Edit this file, Open the repository, Folder.
- Repos: Rescan, Root folder. Browser: the presets.
- Profile & Config (`src/cloud/profile.js`): Account (the fleet Account: whether the Cloud
  Account binder answers, Open Cloud Account, use its OpenRouter key when no token is set),
  the OpenRouter token (password field, saved encrypted by the plugin, shown masked, Test,
  Remove), Chat defaults (agent, model, effort, permission mode, MCP per agent, the gateway),
  Storage (the all-files grant), About (version, commit, licences, source).

## The Chat tab

Cloud Search's chat (Chat › Search) without the search boxes: one message list, streamed
replies, one composer. Declared in `nav.json::chat`; the fleet-shaped data is DERIVED at build
time by `tools/resolve-targets.py` into `targets.gen.json::chat`:

- **Agents** — every service named `cloud-agi-*` in `ac_cloud-c3/data/services_private.json`,
  nav.json's declared order first (Hermes, OpenClaw, Goose, Claude), then any other (shown "no
  app API yet"), then OpenRouter direct. OpenClaw is declared but not in the fleet, so it is
  shown **not deployed** and disabled. The app reaches the agents through the fleet's agent
  gateway, my-ai-api (`cloud-agi-goose`, WireGuard-only): `X-Agent-Mode` picks goose / hermes /
  claude-cli, `/health` says which it serves now. The cloud-agi-hermes container has its own
  API server off (Telegram only), so Hermes is the gateway's hermes mode.
- **Model** — libs:model-catalogue (Cloud Search's catalogue, ONE implementation: its logic
  package and assets are compiled in by reference, the plugin hands its `CatalogueJson` to the
  page), narrowed to **A0 Code** by the lib's own section filter. Hermes and Goose answer with
  the gateway's own model (the button shows it); Claude can pick Anthropic rows (passed as the
  CLI id); OpenRouter direct any A0 row.
- **Effort** Low / Medium / High / Max → OpenRouter `reasoning.effort` (the gateway passes extra
  body fields through), disabled when the model in use does not reason (OpenRouter's
  `supported_parameters`), hidden for Claude (the CLI takes none per request).
- **Permission mode** Auto / Accept / Plan, on the composer. No backend the app reaches takes a
  per-request approval switch, so it is sent as an instruction (via=prompt); hidden for
  OpenRouter direct (no tools).
- **MCP** — the gateway's live `/v1/mcp/status` for Hermes and Goose, else the fleet's derived
  `.mcp.json` names (`0_apps/src/root/mcp.json`); toggles saved per agent. The gateway has no
  per-request server switch yet, so the toggles are advisory there (the sheet says so).
- **More** — `nav.json::chat.functions`, filtered by the gateway's live `/health.plugins`:
  gateway sessions (resume one), tool search, models, health; Headroom / RTK / Caveman /
  Principles / compression mode as per-request headers; Hermes skills and memory; Claude's OAuth
  login; retry, undo, refresh prices. Offline, the declared list, marked offline.
- **+** — the Android photo picker or SAF (no storage permission), and a voice message recorded
  as 16 kHz WAV (RECORD_AUDIO, asked on first use) sent as `input_audio`. The app has no
  speech-to-text, so the audio is sent, not transcribed.

`src/plugins/cloudchat` is the native half: the OpenRouter token in EncryptedSharedPreferences
(Keystore master key; fleet manifest store `cloud_code_chat_secrets`, class secret), never
returned to the page, never logged, added by the plugin to requests to https://openrouter.ai
only; streamed HTTP; the catalogue; attachments. `test/test-chat.sh` holds all of it.

## i18n (#299)

English base: see (6) above, held by `test/test-cloud-nav.sh`. No module
entry goes in `1_cicd/src/i18n-policy.json`, because the Android project
is generated by Cordova at build time and there is no committed
`src/main/res/values/strings.xml` for the guard to read. The i18n guard refuses
entries for modules without one. Acode's UI strings are its own
`src/lang/*.json` catalogue.

## Not verified (needs a phone)

The WebView layout, the Backlog read from the device clone, the Repos listing
under scoped storage, the MyTerminal launch, and the Chat on the mesh: the gateway reached at
its private DNS name, a streamed OpenRouter reply, the photo picker, the microphone prompt,
and the Cloud Account binder answering this app.
