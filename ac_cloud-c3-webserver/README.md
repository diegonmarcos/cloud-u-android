# cloud-c3-webserver

The phone's local web server, as an APK, in the cloud-c3 family: Rust and web only. It is a Tauri 2
Android app: the Rust library in `src-tauri/` starts the web servers on threads of the app's own
process, waits for the bind, and opens the app's one webview on `http://127.0.0.1:8002/`.

**What :8002 serves.** ONLY the emulated root, `/storage/emulated/0`, with the EXACT web UI of the
Linux `my-webserver`: folder tree on the left, viewer/renderer on the right (md, json, yaml, html with
Eruda, images, text), toolbar on top (search, hidden files, raw). That UI is not written in this app.
It is `ab_cloud-libs-shared/libs/fleet-webserver`, the one web server of the fleet (termux :8000 and
nix-on-droid :8001 consume the same crate), whose `vendor/` is a byte-identical, sha256-checked mirror
of `cloud-u-linux/da_my-webserver/src`, bundled at build time (nothing is downloaded at runtime).
Refresh it with `ab_cloud-libs-shared/libs/fleet-webserver/sync.sh --from <cloud-u-linux checkout>`; there is
no custom page, tab, route or script of :8002 in this directory.

| Piece | Where |
|---|---|
| The :8002 server and its Linux UI | `ab_cloud-libs-shared/libs/fleet-webserver` (`start`, `vendor/`, `sync.sh`) |
| Start-up, :8003 listener, host allow-list | `server/src/lib.rs` |
| 8003 `cloud.app`: the SuperApp's About, Logcat, Diagnose | `server/src/diag.rs` + `server/ui/diag.html`, read through the SuperApp's `<pkg>.fleetdiag` provider (`src-tauri/src/bridge.rs`, JNI) |
| Private DNS, udp 127.0.0.1:5353, four exact names, refuses the rest | `server/src/dns.rs`; asked by cloud-browser (`libs:browser PrivateNames`) only |
| The name -> port map, ONE declaration | `build.json::serving` (+ `runtime.port`, `runtime.diag.port`) |
| The app: bind, then the window; storage permission over JNI | `src-tauri/src/lib.rs` |
| Port and served root (baked by `src-tauri/build.rs`) | `build.json::runtime` |
| Tauri's Android project (template, committed) | `src-tauri/gen/android` |
| Toolchain pins: tauri CLI + sha256, NDK, AGP, gradle | `build.json::toolchain` |

**Kotlin.** The app has none of its own. Tauri's Android template commits a
three-line `MainActivity` and two gradle plugin classes, and its build writes
the webview classes into `generated/`; `build.json::tauri.glue` pins the first
three by sha256 and `tauri.generated_kotlin` names the rest.
`test/test-c3-webserver-no-kotlin.sh` fails CI, and `build.sh` fails the
build, on any other `.kt`/`.java`.

Build: the ship workflow checks out cloud-u-linux, verifies the vendored UI against it
(`sync.sh --check --from`), runs the shared server's `cargo test` and this crate's, installs the pinned
tauri CLI, the NDK and the Rust Android target, then `./build.sh build`
(`cargo tauri android build --apk --target <abi>`, release profile) and re-signs the APK with the
shared key.

Testers: `test/test-c3-webserver.sh` (no custom :8002 UI, the shared server is the only :8002 code,
Tauri shape, rename ledger), `test/test-c3-webserver-render.sh` (the real server in headless Chrome at
Galaxy A37 density: tree left, viewer right, toolbar on top), `test/test-c3-webserver-ports.sh` (map,
owners, readers, loopback-only DNS), `test/test-c3-webserver-no-kotlin.sh`, plus `cargo test` here and
in the shared server (vendor hash == the Linux copy, every endpoint's contract, parity with the real
Linux server).
