# cloud-c3-webserver

The phone's local web server, as an APK, in the cloud-c3 family — Rust and
web only. It is a Tauri 2 Android app: the Rust library in `src-tauri/` starts
the `server/` HTTP server on a thread of the app's own process, waits for the
bind, and opens the app's one webview on `http://127.0.0.1:<port>/`. The
server's own shell is the whole UI: five bottom tabs, Pages, API, Home, Files,
Configs.

| Piece | Where |
|---|---|
| Route table, the ONE declaration | `server/src/routes.rs::ROUTES` |
| HTTP/1.1 server, loopback only, GET/HEAD, `start(port, root)` | `server/src/lib.rs` |
| Home: about this phone, in superapp's Configs ▸ About groups | `server/src/about.rs::MACROS` / `groups` |
| Five-tab shell: Pages, API, Home, Files, Configs | `server/ui/shell.html` |
| The app: bind, then the window; storage permission over JNI | `src-tauri/src/lib.rs` |
| Port and served root (baked by `src-tauri/build.rs`) | `build.json::runtime` |
| Tauri's Android project (template, committed) | `src-tauri/gen/android` |
| Toolchain pins: tauri CLI + sha256, NDK, AGP, gradle | `build.json::toolchain` |

The Pages and API tabs render `/__api__/routes`, the route table serialised;
dispatch is a walk over that same table, so served equals declared by
construction. Add a row to `ROUTES` and it appears in the tabs; remove one and
it disappears.

**Kotlin.** The app has none of its own. Tauri's Android template commits a
three-line `MainActivity` and two gradle plugin classes, and its build writes
the webview classes into `generated/`; `build.json::tauri.glue` pins the first
three by sha256 and `tauri.generated_kotlin` names the rest.
`test/test-c3-webserver-no-kotlin.sh` fails CI, and `build.sh` fails the
build, on any other `.kt`/`.java`.

Build: the ship workflow installs the pinned tauri CLI, the NDK and the Rust
Android target, runs `cargo test` on the server crate, then `./build.sh build`
(`cargo tauri android build --apk --target <abi>`, release profile) and
re-signs the APK with the shared key.

Testers: `test/test-c3-webserver.sh` (route table, tabs, derived shell, Home
shape, Tauri shape, rename ledger), `test/test-c3-webserver-no-kotlin.sh`,
`test/test-c3-webserver-about-mirror.sh` (Home's groups against superapp's),
and the server crate's own `cargo test` (the real server end to end).

What changed against the Kotlin shell: the server is no longer a separate
process kept up by a foreground service, it lives as long as the app's
process does.
