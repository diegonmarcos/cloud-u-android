# fleet-webserver (shared)

The ONE web server of the phone fleet: c3-webserver `:8002`, termux `:8000`, nix-on-droid `:8001`
all consume this crate (`fleet_webserver`, or the static binary `fleet-webserver <port> [root]`).
No app carries a copy of any of it.

* **Frontend** = the Linux `my-webserver` UI (folder tree left, viewer right, toolbar on top).
  Source of truth: `cloud-u-linux/da_my-webserver/src` (`lib/browse.html`, `lib/*.css|js`, and
  the server-rendered markdown/JSON/YAML pages that live as template literals in
  `my-webserver.cjs`).
* `vendor/` is a **byte-identical, hash-checked mirror** of those files. Never edit it:
  `./sync.sh --from <cloud-u-linux checkout>` refreshes it and `vendor/VENDOR.sha256`;
  `./sync.sh --check --from <checkout>` fails on any drift.
* `build.rs` refuses a `vendor/` whose hashes do not match, cuts the two page templates out of the
  vendored `my-webserver.cjs` (an unknown `${expr}` fails the build) and compiles everything into
  the binary. Nothing is downloaded at runtime; Eruda (not in the Linux repo) is bundled from
  `assets/eruda.min.js` and served from `/__lib__/`.
* API contract (what the frontend calls): `GET /`, `/<path>`, `/<file>?raw`,
  `/__api__/ls`, `/__api__/read`, `/__api__/search` (+ `x-search-partial`), `/__lib__/*`.

Tests (`cargo test`; `LINUX_REPO=<cloud-u-linux checkout>`, `node` on PATH):
`tests/vendor.rs` (bundled hash == Linux repo copy), `tests/contract.rs` (every endpoint),
`tests/parity.rs` (our pages and API answers == the real Linux server, run under node).

## Write API (`POST /__api__/write`)

The Linux `my-webserver` write API, same contract and guards, std-only (`src/write.rs`). OFF unless
`HTTPD_WRITE=1`. Confined to `HTTPD_WRITE_ROOTS` (colon separated, absolute or root-relative; none =
nothing writable; a symlink cannot carry a write out of the served root or the write root). Every
request needs `x-httpd-write: 1`, `content-type: application/json`, a loopback `Origin` if one is sent,
and `x-httpd-token` equal to the first line of the 0600 file `HTTPD_WRITE_TOKEN_FILE` (no file = no
writes). Body `{"path": "...", "content": "..."}` or `{"path": "...", "b64": "..."}`, up to 32 MB;
answer `{"ok":true,"path":...,"bytes":N}`. Loopback peers only, as everywhere. Reads never need the
token. `handler_with(&cfg, WriteCfg)` takes the configuration explicitly (tests); `handler`/`start`
read it from the environment. `tests/write.rs` and the unit tests in `src/write.rs` cover each guard.
Not ported: `/__api__/git`, `/__api__/watchdog`.
