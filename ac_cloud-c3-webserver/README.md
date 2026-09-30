# cloud-c3-webserver

The phone's local web server, as an APK, in the cloud-c3 family.

The server is a Rust crate in `server/`, compiled per ABI as a static musl
executable and exec'd from the APK's `nativeLibraryDir` (the one place an app
may exec from on API 29+; the same shape `libs:gix`, `libs:rclone` and
`libs:gh` already ship). No rootfs, no proot, no loader is baked any more: the
glibc Node binary and the #288 Route D machinery that carried it are gone.

| Piece | Where |
|---|---|
| Route table, the ONE declaration | `server/src/routes.rs::ROUTES` |
| HTTP/1.1 server, loopback only, GET/HEAD | `server/src/main.rs` |
| About this phone (getprop, /proc, /sys, statvfs) | `server/src/about.rs` |
| Five-tab shell: Pages, API, Home, Files, Configs | `server/ui/shell.html` |
| Binary pin: crate, bin name, jni name, Rust targets | `build.json::server` |
| Port and served root | `build.json::runtime` |
| Staging into `jniLibs/<abi>/libc3webserver.so`, PT_INTERP refused | `app/build.gradle::stageServer` |
| Foreground service + WebView on `http://127.0.0.1:<port>/` | `app/src/main/java/.../*.kt` |

The Pages tab and the API tab render `/__api__/routes`, which is the route
table serialised. Dispatch is a walk over that same table, so served equals
declared by construction, and the server refuses to start on a malformed table.
Add a row to `ROUTES` and it appears in the tabs; remove one and it disappears.

Build: the ship workflow's `server` job runs `cargo test` and `cargo build
--release --target <musl target>` on a runner of the ABI's architecture and
hands the binary to the gradle job through `C3_WEBSERVER_BIN_DIR`. Locally,
`./build.sh server` then `./build.sh build`.

Testers: `test/test-c3-webserver.sh` (static, runs here and in CI) and the
crate's own `cargo test` (runs the real server end to end, in CI).
