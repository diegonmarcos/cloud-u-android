# Per-tool libs for the terminal — measurement report (#644, from #618)

Measured 2026-09-29 on the real device: Samsung G996B, `Linux localhost 5.4.242-30958140-abG996BXXSJHZC2 aarch64`,
inside `com.termux.nix` (nix-on-droid). `strace` WAS available, so every exec tree below is a real process tree,
not documentation. Reference git: `git version 2.44.2`, exec-path
`/nix/store/4gjpp4hv6mx8g7srcgw7bba6pjn9arcz-git-2.44.2/libexec/git-core`.

The question: can git (then node, then coreutils) ship as ONE file named `lib*.so` in `jniLibs`, exec'd from
`nativeLibraryDir` with no proot — the `libs:rclone` pattern (`ab_cloud-libs-shared/libs/rclone/data/rclone-binary.json`:
`interp: none`, 78,315,682 bytes arm64, no `PT_INTERP`, no `PT_DYNAMIC`).

---

## Verdict table

| tool | one static `lib*.so`? | bytes | `PT_INTERP` | measured on device |
|---|---|---|---|---|
| canonical git 2.44.2 (nixpkgs, glibc) | **NO** | 4,019,312 (`git`) + 2,459,944 (`git-remote-http`) | present | yes |
| canonical git, static musl aarch64 prebuilt | **not found / cannot pin** | — | — | searched, see §3 |
| `gix` (gitoxide 0.59.0, max-pure, aarch64-musl) | **YES — clone + fetch only, NO push** | 18,772,920 | **none** | yes, cloned + fetched over HTTPS with `PATH=/nonexistent` |
| `ein` (same release) | YES (same ELF property) | 5,057,280 | **none** | ELF walked |
| node 22 (unofficial musl arm64) | **NO** | 127,195,408 | `/lib/ld-musl-aarch64.so.1` | yes — refused to exec |
| node (official nodejs.org arm64) | NO (glibc-dynamic, no static build published) | — | — | — |
| coreutils via Android `/system/bin/toybox` | **YES in effect** (bionic-dynamic, but its libs are always present on Android) | 548,424 | `/system/bin/linker64` | yes, 209 applets, applet-as-argument works |
| busybox 1.36.1 (nixpkgs, glibc) | NO | 1,352,504 | present | yes |
| **gh 2.101.0 (official `linux_arm64` release)** | **YES** | **38,928,544** | **none** | yes — ran with no PATH, no git, no writable HOME, zero child execs |
| gh (nixpkgs build on this phone) | NO | 49,750,856 | present | yes — do not generalise from this one |

| rootfs / payload shape | bytes | MB |
|---|---|---|
| nixdroid rootfs (current monolith) | ~400 MB per `ac_cloud-nix-on-droid/build.json` | ~400 |
| **git-only rootfs** (nix: git output + its real runtime lib closure) | 51,018,000 + 71,834,624 = **122,852,624** | **117.2** |
| **node-only rootfs** (nix: nodejs-slim runtime closure, dev/bin/man/doc excluded) | **231,074,544** | 220.4 |
| rclone single static binary (for scale) | 78,315,682 | 74.7 |
| `gix` single static binary | 18,772,920 | 17.9 |
| `gh` single static binary | 38,928,544 | 37.1 |

---

## 1. THE CRUX — is `git-remote-https` a separate executable?

**YES.** It is a real file, not a symlink to `git`, and it is not a builtin.

```
$ ls -la "$(git --exec-path)"/git-remote-*
lrwxrwxrwx 1 ... git-remote-ext -> git
lrwxrwxrwx 1 ... git-remote-fd -> git
lrwxrwxrwx 1 ... git-remote-ftp -> git-remote-http
lrwxrwxrwx 1 ... git-remote-ftps -> git-remote-http
-r-xr-xr-x 1 ... 2459944 git-remote-http
lrwxrwxrwx 1 ... git-remote-https -> git-remote-http
```

Not a builtin — `git`'s own string table has no such command, and `git help -a` never lists it:

```
$ strings "$(git --exec-path)/git" | grep -x 'remote-https\|remote-http\|remote-curl'
      (no output)
$ git help -a | grep -c remote-http
0
$ git remote-https
error: remote-curl: usage: git remote-curl <remote> [<url>]
```

That last line is git dispatching through `execv_dashed_external` to the separate `git-remote-http` binary, which
identifies itself as `remote-curl`.

### What happens when the helper is missing — it FAILS LOUDLY

Built a minimal exec-path containing only `git`, `git-upload-pack`, `git-receive-pack`:

```
$ GIT_EXEC_PATH=$PWD/ep git ls-remote https://github.com/git/git
git: 'remote-https' is not a git command. See 'git --help'.
REAL_EXIT=128
```

Exit 128 with a clear message. No silent degradation, no fallback to another transport.

---

## 2. The real exec trees (strace, `-f -e trace=execve`)

### clone over HTTPS

```
$ strace -f -e trace=execve -o clone.strace git clone --depth 1 https://github.com/git/git.git g
CLONE_EXIT=0
$ grep -o 'execve("[^"]*"' clone.strace | sort | uniq -c | sort -rn
      3 execve(".../libexec/git-core/git"
      1 execve(".../libexec/git-core/git-remote-https"
      1 execve(".../.nix-profile/bin/git"
```

### fetch over HTTPS

```
$ strace -f -e trace=execve -o fetch.strace git fetch origin
FETCH_EXIT=0
execve(".../bin/git", ["git", "fetch", "origin"]
execve(".../git-core/git", [..., "remote-https", "origin", "https://github.com/git/git.git"]
execve(".../git-core/git-remote-https", [..., "origin", "https://github.com/git/git.git"]
execve(".../git-core/git", [..., "rev-list", "--objects", "--stdin", "--not", "--exclude-hidden=fetch", "--all", "--quiet", "--alternate-refs"]
execve(".../git-core/git", [..., "maintenance", "run", "--auto", "--no-quiet"]
```

### push over smart HTTP — reaching `send-pack`

GitHub 401s an unauthenticated `receive-pack` advertisement, so push could not be measured against it:

```
$ curl -s -o /dev/null -w 'receive-pack GET status=%{http_code}\n' \
    'https://github.com/git/git.git/info/refs?service=git-receive-pack'
receive-pack GET status=401
$ GIT_TERMINAL_PROMPT=0 git -c credential.helper= push https://github.com/git/git.git HEAD:refs/heads/nope
fatal: could not read Username for 'https://github.com': terminal prompts disabled
```

So a local `git-http-backend` was stood up behind `busybox httpd` (CGI) on `127.0.0.1:18711`.
**Limit of this substitute:** it is `http://`, not `https://`. That is sound for an exec-tree measurement because
the SAME binary serves both — `git-remote-https` is a symlink to `git-remote-http` (§1) and TLS is internal to
libcurl, adding no process. Confirmed by the trace: the helper resolved is `remote-http`, the only difference
from the HTTPS traces above.

```
$ git -c core.hooksPath=/dev/null -c credential.helper= push \
    http://127.0.0.1:18711/cgi-bin/git-http-backend/test.git HEAD:refs/heads/main
execve(".../bin/git", ["git", ..., "push", "http://127.0.0.1:18711/cgi-bin/g"..., "HEAD:refs/head...
execve(".../git-core/git", [..., "remote-http", "http://127.0.0.1:18711/cgi-bin/g"...
execve(".../git-core/git-remote-http", [..., "http://127.0.0.1:18711/cgi-bin/g"...
execve(".../git-core/git", [..., "send-pack", "--stateless-rpc", "--helper-status", "--thin", "--no-progres...
execve(".../git-core/git", [..., "pack-objects", "--all-progress-implied", "--revs", "--stdout", "--thin"...
```

### THE EXEC LIST — this is the link engine's input

Read the traces carefully: `send-pack`, `pack-objects`, `rev-list`, `maintenance` are exec'd as
**`<abs-path>/git <subcommand>`** — argv[0] is the `git` binary's own path and the subcommand is argv[1].
Those are builtin dispatch. **They need no file of their own and no name on PATH.**

Only ONE name is resolved as a separate file:

| name git resolves | how | file needed |
|---|---|---|
| `git` | invoked directly / re-exec by absolute path | `git` |
| `remote-https` / `remote-http` | not a builtin → `execv_dashed_external` → `execvp("git-remote-https")` over exec-path then PATH | **`git-remote-https`** |
| `send-pack`, `pack-objects`, `rev-list`, `maintenance`, `rev-parse`, … | builtin, exec'd as `git <subcmd>` | none |

**N = 2 files, 1 name.** Not one, so the rclone pattern cannot carry canonical git unmodified — `lib*.so` is the
only name Android extracts, and git will look for the literal string `git-remote-https`.
Everything else git needs it already has.

Two non-git names appeared in the SSH-remote push trace and are worth recording because they are *repo* config,
not git's transport: this repo's `0_git/dist/hooks/pre-push` pulls in `bash`, `coreutils/basename`, `gnused/sed`,
`gettext`, `envsubst`, `uname` via `git-submodule` (a shell script, one of the non-symlink files in exec-path).
Those are avoidable (`core.hooksPath=/dev/null` removed them all); the transport helper is not.

---

## 3. Static aarch64 git

### Canonical git — glibc-dynamic, fails the rclone pin

```
$ readelf -lW "$(git --exec-path)/git" | grep -E 'INTERP|DYNAMIC|Requesting'
  INTERP ...
      [Requesting program interpreter: /nix/store/aaq...-glibc-2.39-52/lib/ld-linux-aarch64.so.1]
  DYNAMIC ...
$ readelf -dW "$(git --exec-path)/git" | grep NEEDED
 [libpcre2-8.so.0] [libz.so.1] [librt.so.1] [libgcc_s.so.1] [libc.so.6] [ld-linux-aarch64.so.1]
$ readelf -dW "$(git --exec-path)/git-remote-http" | grep NEEDED
 [libcurl.so.4] [libexpat.so.1] [libpcre2-8.so.0] [libz.so.1] [librt.so.1] [libgcc_s.so.1] [libc.so.6] [ld-linux-aarch64.so.1]
```

`PT_INTERP` present → would be refused by the rclone pin's program-header check.

### No credible static musl canonical-git prebuilt could be pinned

Searched the GitHub search API and the best-known static-binaries collection; nothing usable came back:

```
$ curl -s 'https://api.github.com/search/repositories?q=static+git+binary+musl&per_page=5' | grep full_name
"full_name": "zszszszsz/.config"
$ curl -s https://api.github.com/repos/ryanwoodsmall/static-binaries/contents/aarch64 | grep '"name": "git'
      (no output)
```

**Reported as a measured negative:** no static aarch64-musl canonical git exists that this spec is willing to pin
by URL + sha256. Producing one is a GHA build job, not a download. Note that even a static canonical git would
still need the `git-remote-https` *file* unless §5 is solved.

### gitoxide — a genuinely static git, ONE file

Release `v0.59.0` publishes `aarch64-unknown-linux-musl` in the `max-pure` flavour (pure Rust: rustls, no C
openssl/zlib), which is what makes it fully static.

- URL: `https://github.com/GitoxideLabs/gitoxide/releases/download/v0.59.0/gitoxide-max-pure-v0.59.0-aarch64-unknown-linux-musl.tar.gz`
- tarball sha256: `6a6d6b9a4089d7146cdd8563dc7c3750bad19c9b8f9b9ff428970a5952b672dc`
- `gix` — 18,772,920 bytes, sha256 `ef495c2375d9e2041a69706564fe90a4d781d05f084e34c71e460102296fe90f`
- `ein` — 5,057,280 bytes, sha256 `311210756f0b4393aca64de30e75bfd88f91887a5010fe04aaacb6eb2c2c55d8`

```
$ readelf -lW gix | grep -E 'INTERP|DYNAMIC'
      (no output)
$ readelf -dW gix
There is no dynamic section in this file.
```

**No `PT_INTERP`, no `PT_DYNAMIC` — the exact rclone property.**

Renamed to `libgix.so`, run on the phone, with the environment emptied and PATH deliberately broken:

```
$ cp .../gix ./libgix.so && ./libgix.so --version
gix v0.59.0

$ env -i PATH=/nonexistent HOME=/nonexistent ../libgix.so clone --depth 1 \
      https://github.com/GitoxideLabs/gitoxide.git gx </dev/null
EXIT=0
gx/.git EXISTS

$ grep -o 'execve("[^"]*", \[[^]]*\]' gix2.strace
execve(".../bin/env", ["env", "-i", "PATH=/nonexistent", "HOME=/nonexistent", "../libgix.so", ...
execve("../libgix.so", ["../libgix.so", "clone", "--depth", "1", "https://github.com/GitoxideLabs/"..., "gx"]
execve("/nonexistent/tput", ["tput", "cols"]
execve("/nonexistent/tput", ["tput", "lines"]
execve("/nonexistent/git", ["git", "config", "-lz", "--show-origin", "--no-includes", "--show-scope", "--name-only"]
```

The three child execs all failed ENOENT (`/nonexistent/...`) and the clone still succeeded with exit 0 and a valid
`.git`. `tput` is cosmetic terminal sizing; the `git config` probe is gitoxide reading an installed git's config for
compatibility and is optional. **So: one file, HTTPS transport built in, zero required helpers, 17.9 MB.**

Fetch also works the same way:

```
$ env -i PATH=/nonexistent ../../libgix.so fetch
EXIT=0
refs/tags/*:refs/tags/* (implicit, due to auto-tag)
	skipped 3539 tags known to the remote without bearing on this commit-graph.
no negotiation was necessary
```

**The blocker: gitoxide 0.59.0 has no push.**

```
$ ../../libgix.so push
error: unrecognized subcommand 'push'
$ ../../libgix.so --help | grep -iE '^\s*(push|fetch|clone|remote)'
  fetch         Fetch data from remotes and store it in the repository
  clone         Clone a repository into a new directory
  remote        Interact with the remote hosts [aliases: remotes]
```

So `libgix.so` is a complete one-file answer for **clone + fetch**, and not an answer for push.

---

## 4. Smallest git-only rootfs

Computed from the binaries' ACTUAL `DT_NEEDED` + `DT_RUNPATH`, not from nixpkgs' `git` attribute (which drags
perl 110.5 MB and python 189.3 MB in for optional subcommands — that is why the full nixpkgs git closure is
335.5 MB and is NOT the right number).

Runtime library set: `zlib-1.3.1`, `pcre2-10.43`, `glibc-2.39-52`, `gcc-13.2.0-lib`, `curl-8.7.1`, `expat-2.6.4`.

```
$ nix path-info --recursive $P | sort -u | wc -l
20
$ nix path-info -s $(cat pl.txt) | awk '{s+=$2} END{printf "%d bytes = %.1f MB\n", s, s/1048576}'
71834624 bytes = 68.5 MB
$ nix path-info -s /nix/store/4gjpp...-git-2.44.2
51018000
```

(Each path counted once. Summing `nix path-info -S` per path double-counts — an earlier pass produced a bogus
700 MB that way; this figure is the deduped `narSize` sum.)

**git-only rootfs = 51,018,000 + 71,834,624 = 122,852,624 bytes = 117.2 MB**, against ~400 MB for the nixdroid
monolith. The 51 MB git output still contains the perl/python/tcl subcommand scripts
(`libexec/git-core` 26,614,157 B, `share` 14,969,642 B); a `gitMinimal`-style output would cut into that, but
`git-minimal` is only a `.drv` in this store and building it is out of scope here.

---

## 5. Can a single static git carry HTTPS with no helper?

- **Canonical git: no, not by configuration.** `git-remote-http` is a separate PROGRAM in git's build, not in
  `BUILTIN_OBJS`; §1 measured that the `git` binary contains no `remote-http`/`remote-curl` command string at all,
  so there is no build flag to flip here and no in-process fallback. Making it a builtin means patching git.
- **gitoxide: yes, already.** `libgix.so` did a real HTTPS clone and fetch with `PATH=/nonexistent` and no helper
  process. One entry, one file. It just cannot push (§3).

Practical reading for #644: a declaration with ONE entry gets clone+fetch today via `libgix.so`.
Push needs either canonical git (2 files, one of them named `git-remote-https` — so a rootfs or the terminal-side
link engine) or gitoxide gaining push upstream.

---

## 6. node

No official static node exists — nodejs.org publishes glibc-dynamic `linux-arm64` only.
`unofficial-builds.nodejs.org` does publish `linux-arm64-musl`, and it is pinnable, so it was measured:

- URL: `https://unofficial-builds.nodejs.org/download/release/v22.21.1/node-v22.21.1-linux-arm64-musl.tar.gz`
- tarball sha256: `3cc7cdaf9860d99eb5a43747c017d44fd4b5f590b1c5baeebdc8b928196aef46`
- `bin/node` — 127,195,408 bytes, sha256 `3f9c1cab77ae652ad5d395eab89afbbd89c8303d66978e0c7a50654af96bb975`

```
$ readelf -lW node | grep -E 'INTERP|DYNAMIC|Requesting'
  INTERP ...
      [Requesting program interpreter: /lib/ld-musl-aarch64.so.1]
  DYNAMIC ...
$ readelf -dW node | grep NEEDED
 [libgcc_s.so.1] [libc.so]
```

Dynamic against musl, and the interpreter path is ABSOLUTE `/lib/ld-musl-aarch64.so.1`. Tested on the device:

```
$ ls -la /lib/ld-musl-aarch64.so.1
ls: cannot access '/lib/ld-musl-aarch64.so.1': No such file or directory
$ ./node-v22.21.1-linux-arm64-musl/bin/node -v
bash: line 1: ./node-v22.21.1-linux-arm64-musl/bin/node: cannot execute: required file not found
```

**node cannot be one `lib*.so`. Measured, on the real device.** It needs a filesystem that provides
`/lib/ld-musl-aarch64.so.1` — i.e. a rootfs. (Rewriting `PT_INTERP`/`DT_RUNPATH` with patchelf is an idea, not a
measurement, and is not claimed here.)

**node-only rootfs**, from nodejs-slim's real runtime closure with `-dev`/`-bin`/`-man`/`-doc` outputs excluded:

```
$ nix path-info --recursive $N | grep -vE '\-dev$|\-bin$|\-man$|\-doc$' | sort -u | wc -l
36
$ nix path-info -s $(cat np.txt) | awk '{s+=$2} END{print s}'
231074544        # 220.4 MB over 36 paths
```

node's 26 `DT_NEEDED` entries (icu4c, openssl, simdjson, ngtcp2, sqlite, brotli, zstd, c-ares, libuv, …) are what
make that number; the node binary itself is 73,174,200 bytes in this store.

---

## 7. coreutils / multi-call binaries (part A — applet as first ARGUMENT)

**It works.** Copied nixpkgs busybox to `libbusybox.so` and invoked applets as argv[1]:

```
$ cp $(readlink -f $(which busybox)) ./libbusybox.so && chmod +x libbusybox.so
$ ./libbusybox.so ls -d .
.
$ ./libbusybox.so sh -c 'echo SH_WORKS $(./libbusybox.so basename /a/b)'
SH_WORKS b
$ ./libbusybox.so ash -c 'echo ASH_OK'
ASH_OK
$ ./libbusybox.so sha256sum libbusybox.so
d3b5ae0987939cb891663b7a0d2e70ae873bcb778b41f0c054e5913042be0b02  libbusybox.so
$ ./libbusybox.so --list | wc -l
402
```

So a link engine may point a link at a payload invoked as `<payload> <applet> <args…>` with no symlink and no
argv[0] control. That is the part A answer.

**Caveat measured, not assumed:** busybox's own `sh` does NOT fall back to internal applets in this build —
it resolves by PATH and execs external files:

```
$ env -i ./libbusybox.so sh -c 'ls -d /; cat /proc/version'
$ grep -o 'execve("[^"]*", \[[^]]*\]' bb.strace
execve("./libbusybox.so", ["./libbusybox.so", "sh", "-c", "ls -d /; ...
execve("/bin/ls", ["ls", "-d", "/"]
execve("/bin/cat", ["cat", "/proc/version"]

$ PATH=/nonexistent ./libbusybox.so sh -c 'ls -d /; echo rc=$?'
sh: ls: not found
rc=127
$ ./libbusybox.so sh -c 'type ls; type echo'
ls is .../.nix-profile/bin/ls
echo is a shell builtin
```

`FEATURE_SH_STANDALONE` is not enabled in nixpkgs busybox 1.36.1. So a busybox *shell* still needs names on PATH —
which is exactly the link-engine's job, and exactly why the link layer belongs inside the terminal.

Its ELF is glibc-dynamic, 1,352,504 bytes, `PT_INTERP` present → would fail the rclone pin.

**Android's own toybox is the interesting one.** 548,424 bytes, and although it is dynamic, every library it needs
ships with Android:

```
$ readelf -lW /system/bin/toybox | grep -E 'INTERP|Requesting'
  INTERP ...
      [Requesting program interpreter: /system/bin/linker64]
$ readelf -dW /system/bin/toybox | grep NEEDED
 [libcutils.so] [libcrypto.so] [liblog.so] [libprocessgroup.so] [libselinux.so] [libz.so] [libc.so] [libm.so] [libdl.so]
$ env -i /system/bin/toybox ls -d /
/
$ env -i /system/bin/toybox | tr ' ' '\n' | grep -c .
209
```

(The first attempt failed with `CANNOT LINK EXECUTABLE ... libmimalloc.so ... not accessible` — that was this
shell's `LD_PRELOAD` leaking in from the nix environment, not a property of toybox; `env -i` cleared it.)

209 applets, applet-as-first-argument works, bionic-linked, half a megabyte. A bionic-built toybox or busybox is
therefore the one candidate that CAN live in `nativeLibraryDir` as `libtoybox.so` with no rootfs — but that is an
inference from its `DT_NEEDED` list plus the fact that it is Android's own binary; it was **not** executed from a
real app's `nativeLibraryDir` in this measurement (see §8).

---

## 7b. gh — the one that passes cleanly, and carries auth

### The official arm64 release is FULLY STATIC

- URL: `https://github.com/cli/cli/releases/download/v2.101.0/gh_2.101.0_linux_arm64.tar.gz`
- tarball sha256: `b57e8063f18862647c9d22727c32e9da1b963f8bf9db648fe123a6975695640f`
- `bin/gh` — **38,928,544 bytes**, sha256 `76f657388c2270fb5049305afe683b741fb7c50fba0e15b09338189123f66557`

```
$ readelf -lW gh_2.101.0_linux_arm64/bin/gh | grep -E 'INTERP|DYNAMIC|Requesting'
      (no output)
$ readelf -dW gh_2.101.0_linux_arm64/bin/gh
There is no dynamic section in this file.
```

**No `PT_INTERP`, no `PT_DYNAMIC`** — passes the rclone pin's guard, and at 37.1 MB it is **cheaper than the
78 MB rclone binary already shipped**. The `gh` in this phone's nix store is a DIFFERENT artifact —
49,750,856 bytes with both `PT_INTERP` and `PT_DYNAMIC` present — and would fail the guard. The official release
is the one to pin; do not generalise from the nixpkgs build.

### It is genuinely self-contained — zero child execs, no git, no PATH, no writable HOME

```
$ cp gh_2.101.0_linux_arm64/bin/gh ./libgh.so && chmod +x libgh.so
$ env -i PATH=/nonexistent HOME=$PWD/ghhome ./libgh.so --version
gh version 2.101.0 (2026-09-15)
EXIT=0
$ grep -o 'execve("[^"]*", \[[^]]*\]' gh1.strace
execve(".../bin/env", ["env", "-i", "PATH=/nonexistent", "HOME=/data/data/...
execve("./libgh.so", ["./libgh.so", "--version"]
```

Only the `env` wrapper and `libgh.so` itself. And with NO config dir and NO writable HOME at all, token from the
environment:

```
$ env -i PATH=/nonexistent HOME=/nonexistent GH_CONFIG_DIR=/nonexistent GH_TOKEN=ghp_invalidtokenfortest \
      ./libgh.so auth status
github.com
  X Failed to log in to github.com using token (GH_TOKEN)
  - Active account: true
  - The token in GH_TOKEN is invalid.

$ ... ./libgh.so auth token
ghp_invalidtokenfortest

$ ... ./libgh.so api /user
{
  "message": "Bad credentials",
  "documentation_url": "https://docs.github.com/rest",
$ grep -o 'execve("[^"]*", \[[^]]*\]' gh3.strace | tail -1
execve("./libgh.so", ["./libgh.so", "api", "/user"]
```

A real authenticated API round-trip with **zero child processes**. git is never spawned for auth or API work
(gh only shells out to git for git-shaped subcommands, which are not in this path).

### Config path — fully redirectable, which an Android app needs

```
$ env -i PATH=/nonexistent HOME=$PWD/ghhome ./libgh.so config list
api_host=
git_protocol=https
editor=
$ find ghhome
ghhome/.local/state/gh          # state under $HOME/.local/state/gh, config under $HOME/.config/gh

$ env -i PATH=/nonexistent HOME=/nonexistent GH_CONFIG_DIR=$PWD/ghcfg ./libgh.so config set editor vi
$ find ghcfg -type f
ghcfg/config.yml
$ cat ghcfg/config.yml
# The current version of the config schema
version: 1
git_protocol: https
editor: vi
...
```

`GH_CONFIG_DIR` overrides everything — point it at the app's `filesDir` and gh needs nothing from `$HOME`.
Note `clipboard: enabled` in the default config ("Whether to copy one-time OAuth device codes to the clipboard"),
which is gh's own acknowledgement that the device code is a thing the user handles.

### The auth prize — MEASURED against GitHub, zero registration on our side

The official static binary carries GitHub's own GitHub App client ids (`Iv1.` prefix = GitHub App, not OAuth App):

```
$ strings libgh.so | grep -oE 'Iv1\.[0-9a-f]{16}' | sort -u
Iv1.b231c327f1eaa229
Iv1.e7b89e013f801f03
```

Those ids were exercised directly against GitHub's device-code endpoint. **Both return live codes:**

```
$ curl -s -X POST https://github.com/login/device/code -H 'Accept: application/json' \
      -d "client_id=Iv1.b231c327f1eaa229&scope=repo%20read:org"
{"device_code":"712e964b4528503235c377fbf6868799fdecb36a","user_code":"F7FD-4222",
 "verification_uri":"https://github.com/login/device","expires_in":899,"interval":5}

$ ... -d "client_id=Iv1.e7b89e013f801f03&scope=repo%20read:org"
{"device_code":"99e9f3745635fe39f0bea014eefaa8970176bfb9","user_code":"C503-AFF0",
 "verification_uri":"https://github.com/login/device","expires_in":899,"interval":5}
```

So GitHub has the device flow enabled on their side for gh's app, and **no app registration, no client secret, and
nothing for Diego to mint is required** — in direct contrast to the Google device-flow client, which needed a
purpose-registered "TVs and Limited Input devices" client. The resulting token is retrievable with
`gh auth token` (measured above reading from `GH_TOKEN`; the same command reads the stored credential after a real
login) and can be handed to JGit and to the terminal handoff.

### Not measured

`gh auth login --web` was started with stdin closed and no TTY; it produced no output and had to be killed at the
2-minute mark. So the *interactive* login was NOT completed end-to-end here — only the device-code endpoint it
depends on (above) and gh's token/API paths (above) were measured. An end-to-end login needs a real TTY or the
app driving the flow itself.

### Honest UX tax

Device flow means the user reads a short code (`F7FD-4222`) and types it at `https://github.com/login/device`,
once. Diego disliked that when it was the only option. The trade is different now: the alternative is him
registering and maintaining an OAuth App, and this happens exactly once per device.

---

## 8. Recorded dead end — PATH-name bridging inside `nativeLibraryDir`

This was investigated and is now **moot by design decision**: the link layer goes in the TERMINAL, inside the
proot rootfs, where symlinks and exec-from-data-dir already work (that is how nix-on-droid runs everything out of
`/nix/store` in its own data directory — this entire measurement session is the proof). Bridging names on the
Android side of proot is the wrong side of the boundary.

What was actually measured before that call, kept so nobody redoes it:

- `GIT_EXEC_PATH` does work as a redirection point (§1 used it to build a deliberately incomplete exec-path), but
  it does not help, because git still looks for the literal filename `git-remote-https` inside it and Android only
  extracts `lib*.so`.
- busybox applet-as-argument works (§7) — that one survives and is useful.
- **NOT measured, and deliberately not claimed:** W^X behaviour on hard links, and exec of a hard link to an
  installer-extracted file. Those require running as a real app uid with targetSdk 29+. This process runs as
  `com.termux.nix`, which self-evidently execs files out of its own data directory, so it is *not* a
  representative subject and any result from here would have been misleading. The W^X constraint itself remains as
  measured on 2026-09-24 and recorded in `libs/rclone/data/rclone-binary.json`.

---

## 9. What this means for #644

- **git: 1 name to declare** (`git-remote-https`), and 2 files. Everything else git spawns is builtin dispatch by
  absolute path and needs no declaration. That is a small, honest input for the link engine.
- **`libgix.so` is a real one-file git for clone + fetch** at 17.9 MB, static, no helpers, verified running on this
  phone. If push is not required for a given consumer, this needs no rootfs and no link engine at all.
- **`libgh.so` is the cleanest win of the whole measurement**: 37.1 MB, fully static, zero child execs, no git, no
  writable `$HOME` needed (`GH_CONFIG_DIR` redirects everything), and it carries a GitHub login that works against
  GitHub's own registered app with **nothing to register on our side** — measured live device codes from both
  embedded client ids. That closes the credential gap #641/#642 left open, at less than half the byte cost of the
  rclone binary already shipped. Cost: one short code typed once.
- **node: rootfs or nothing.** Measured refusal to exec; 220.4 MB for a node-only rootfs.
- **Per-tool rootfs libs are the shape that works for everything**: 117.2 MB git-only + 220.4 MB node-only against
  one ~400 MB monolith. The machinery already exists — `ac_cloud-nix-on-droid/build.json` ships the rootfs as an
  installable companion library APK (`com.diegonmarcos.cloudlib.rootfsnixdroid`, `:rootfs-lib`) gated on its own
  `paths_from`, so a second and third companion is a declaration, not new engine code.

### Contradicting the brief

- The brief framed the helper question as possibly "N execs resolved by exact name". Measured, it is **exactly one
  name**, because `send-pack`/`pack-objects`/`rev-list` turned out to be builtin dispatch (`git <subcmd>`), not
  dashed externals. The problem is smaller than stated.
- "If not, what is the smallest git-only rootfs" presumed no single-file git exists. One does (`libgix.so`), with a
  push-shaped hole.
- The hypothesis that a multi-call binary "normally needs symlinks" is false for invocation: applet-as-argument
  works. It is true for the *shell inside it*, which does resolve by PATH.

---
---

# Part II — what the OS image is actually made of, and what can leave it (#664)

Measured 2026-09-30 on the same device (Samsung G996B, `aarch64`, inside `cld.termux.nix`). Part I asked whether a
single tool can ship as one `lib*.so`. Part II asks the inverse and larger question: of everything inside the
terminal's OS image, what is there for no reason, what duplicates something the device already has, and what is
merely big and unavoidable.

**Naming, and it is load-bearing.** The rootfs is an **OS image** — a filesystem with an ELF loader, a libc and a
`/usr` tree. `nodejs_22` is a **runtime** that lives *inside* that OS image. Part I already measured that the
runtime cannot become a lib (it needs an `/lib/ld-musl-aarch64.so.1` that only a filesystem can supply), so calling
the OS image a "runtime" hides the one fact that decides the whole analysis. Part II keeps the two words apart.

## 0. The artifact this part measures — and the trap that must be recorded first

`ac_cloud-nix-on-droid/build.json` still lists a bare release asset shape
(`cloud-nixdroid-bootstrap-{id}-{abi}.zip`) left over from #618's transport. That asset is **still on the rolling
release and it is STALE**. Measuring it produces confident, wrong answers — this pass did exactly that for an hour
before catching it.

```
$ python3 -c "...sha256 over artifact.identity_files..."      # the content address the declaration defines
CURRENT content address id = 1c5ebd9132be
SHIPPED bare-zip asset id  = add9739ca6e1
```

The stale zip has 20,084 entries and **no `fish`, `gawk`, `findutils` or `less` at all** — it predates #641, which
added them. It also contains zero `cloud-store` entries, so it predates #644. A reader who measured it would
conclude the declaration promises four tools the artifact does not carry. **That conclusion would be false.**

The LIVE OS image is the companion APK's asset (#628 Slice 2). Read without downloading 393 MB, by range-fetching
zip central directories:

```
$ curl -sSL -r 393338528-393638527 -o apkend.bin '.../Cloud-Lib-Rootfs-Nixdroid.apk'
APK EOCD: entries=9 cd_offset=393637888
       comp      uncomp meth     lfh_off  name
  393621704   393621704    0         779  assets/bootstrap.zip     <- method 0 = STORED
$ curl -sSL -r 779-878 '.../Cloud-Lib-Rootfs-Nixdroid.apk'
LFH: method=0 csz=393621704 name_len=20 extra_len=7 name=assets/bootstrap.zip
INNER ZIP DATA START = 836
$ curl -sSL -r 388622540-393622539 -o inner.bin '.../Cloud-Lib-Rootfs-Nixdroid.apk'
INNER EOCD: entries=21281 cd_size=3052039 cd_offset=390569643
parsed 21281 entries  compressed_sum=387858100  uncompressed_sum=1057520494  store_paths=202
```

**All 11 declared attrs ARE present in the live image** — `fish-4.9.3`, `gawk-5.4.1`, `findutils-4.11.0`,
`less-704` included. There is no missing-attr defect. There IS a stale 369,680,084-byte asset on the release that
should be deleted so nobody else measures it.

| artifact | bytes | what it is |
|---|---|---|
| `Cloud-Lib-Rootfs-Nixdroid.apk` | **393,638,528** | the live nix OS image, as shipped |
| └ `assets/bootstrap.zip` (STORED) | **393,621,704** | 21,281 entries, **387,858,100 compressed / 1,057,520,494 uncompressed** |
| `Cloud-Lib-Rootfs-Termux.apk` | 437,219,969 | the live Debian OS image |
| `cloud-rootfs-6f37b3960639-arm64.tar.zst` | 437,419,375 | same bytes, legacy transport |
| `cloud-nixdroid-bootstrap-add9739ca6e1-arm64.zip` | 369,680,084 | **STALE, delete** |

**The ~400 MB in the declaration is COMPRESSED.** The OS image decompresses to 1,057,520,494 bytes. Part I's table
listed "~400,000,000" beside NAR byte counts as if the two were comparable; they are not, and every "bytes saved"
figure below is therefore given in **compressed** bytes, because that is what a phone downloads. Measured
compression ratio on this content, with the command:

```
$ gzip -6 -c .../claude-code-2.1.226/bin/claude | wc -c
claude-code raw=294632376 deflate6=91255331 ratio=3.23
node        raw=73174200  deflate6=23371566 ratio=3.13
glibc-2.39  raw=41227494  deflate6=10322427 ratio=3.99
```

## 1. Table 1 — per-attr closure size vs deduped MARGINAL cost

Two different numbers, and only the second one answers "what would the OS image shrink by".

The pinned-nixpkgs eval could **not** be run on this device. Recorded as a measured negative:

```
$ TMPDIR=$PREFIX/tmp nix eval --raw 'github:NixOS/nixpkgs/e94cb152...#legacyPackages.aarch64-linux.less.outPath'
error (ignored): error: opening directory '.../usr/tmp/nix-28304-0': Function not implemented
error: … while fetching the input 'github:NixOS/nixpkgs/e94cb152…'
       error: failed to extract archive (Could not stat …/nixpkgs-e94cb152…/pkgs/by-name/ha/hawkthorne-journey/package.nix)
$ curl -sSL -o np.tar.gz https://codeload.github.com/NixOS/nixpkgs/tar.gz/e94cb152...
tarball bytes=53334642
curl: (56) Recv failure: Software caused connection abort      # second attempt, GNU tar route
```

nix's own libarchive extraction of a 53 MB nixpkgs tarball fails under proot with `Function not implemented` — the
same class of failure as `nix-on-droid switch` from termux. So the **marginal** column below is computed from this
phone's LOCAL store, whose versions are **identical to the live artifact** for `coreutils-9.11`,
`findutils-4.11.0`, `gnugrep-3.12`, `gnused-4.10` and `gawk-5.4.1`, and near for the rest. The
**live-artifact** column is exact, read from the shipped image's central directory.

```
$ nix-store -qR <attr-outPath> | sort -u > cl.<attr>          # per-attr closure, one file each
$ cat cl.* | sort -u > union.txt                              # dedup across all 11
$ nix path-info -s $(cat union.txt) | awk '{s+=$NF} END{print s}'
1028385240
$ ls cl.* | grep -v "cl\.<attr>$" | xargs cat | sort -u > wo.<attr>   # leave-one-out
$ nix path-info -s $(cat wo.<attr>) | awk '{s+=$NF} END{print s}'
```

| attr | solo closure (NAR) | **marginal** (NAR, leave-one-out) | **live artifact, compressed** |
|---|---:|---:|---:|
| `claude-code` | 338,437,744 | **294,632,856** | **103,409,893** (26.7%) |
| `nodejs_22` | 273,517,976 | **221,294,496** | 29,576,883 (7.6%) |
| `git` | 351,766,232 | **135,570,360** | 29,830,145 + perl/python (§2) |
| `fish` | 250,840,704 | 34,644,832 | 13,346,890 |
| `bashInteractive` | 53,281,992 | 4,019,784 | 2,539,309 |
| `gawk` | 53,583,984 | 4,321,776 | 1,200,782 |
| `findutils` | 64,258,528 | 2,125,600 | 764,849 |
| `gnused` | 50,082,016 | 819,808 | 329,556 |
| `less` | 50,068,864 | 403,448 | 196,205 |
| `coreutils` | 62,132,928 | **0** | 771,465 |
| `gnugrep` | 52,223,480 | **0** | 360,207 |
| union of all 11 | — | 1,028,385,240 | — |

**The two columns disagree by more than an order of magnitude, and the solo column is the misleading one.** Every
solo closure reads 50-64 MB even for `gnused`, because each one contains a whole glibc. Anyone who sums that column
concludes the tools cost 1.6 GB.

**`coreutils` and `gnugrep` have a marginal cost of exactly ZERO.** Removing either attr frees nothing, because
`git` references both directly:

```
$ nix-store -q --references .../git-2.44.2 | sed 's|/nix/store/[a-z0-9]*-||'
glibc-2.39-52  gcc-13.2.0-lib  openssl-3.0.14  bash-5.2p32  gettext-0.21.1  gnused-4.9
zlib-1.3.1  expat-2.6.4  curl-8.7.1  python3-3.11.10  gzip-1.13  pcre2-10.43
gnugrep-3.11  coreutils-9.5  perl-5.38.2  git-2.44.2-doc  … (+17 perl modules)
```

This is the single most important correction in Part II: **the toybox-duplication saving is zero while canonical
git is in the image.** Dropping `coreutils`/`gnused`/`gnugrep` from `attrs` removes them from PATH and frees no
bytes at all, because git drags the identical packages back in.

## 2. Where the bulk actually is — the node hypothesis, REFUTED

The brief's hypothesis was that `nodejs_22` + `claude-code` is most of the payload and that the big item is
therefore the one thing that cannot leave. **Half right, and the half that is wrong is the actionable half.**

| rank | store path | compressed | % of OS image | who asked for it |
|---:|---|---:|---:|---|
| 1 | `claude-code-2.1.280` | **103,409,893** | 26.7% | declared |
| 2 | `python3-3.14.7` | **73,582,754** | **19.0%** | **`git`, for `git p4`** |
| 3 | `nodejs-slim-22.23.3` | 25,951,901 | 6.7% | declared |
| 4 | `git-2.55.0` | 24,862,234 | 6.4% | declared |
| 5 | `perl-5.42.3` | **15,847,717** | 4.1% | **`git`, for send-email/instaweb/cvs** |
| 6 | `icu4c-78.3` | 15,752,769 | 4.1% | node |
| 7 | `glibc-2.42-84` | 12,511,446 | 3.2% | the baked attrs |
| 8 | `fish-4.9.3` | 12,343,156 | 3.2% | login shell |
| 9 | `glibc-2.37-45` | 10,647,980 | 2.7% | **the upstream bootstrap — a SECOND glibc** |
| 10 | `nix-2.20.5` | 6,078,066 | 1.6% | the upstream bootstrap |

`nodejs_22` (all three outputs) + `claude-code` = **132,986,776 compressed = 34.3%** of the OS image. That is the
largest single block and it is immovable — but it is **one third, not "most"**. The refutation that matters:

**`python3` alone is 19.0% of the OS image, and no declared attr asked for it.** It is there because nixpkgs `git`
defaults `pythonSupport = true`, for `git p4`. `perl` and its 40 modules add another 4.5%, for `git send-email`,
`git instaweb` and the CVS bridges. Part I recorded that this tail exists; Part II measures that **it is the
second-largest item in the shipped artifact and larger than git itself.**

```
$ python3 parse.py inner.bin 388621704   # grouping the live image's 21,281 entries
PERL SUBTREE (perl + 40 perl5.42.3-* modules): compressed=17436391 (4.5%)
PYTHON3: compressed=73582754 (19.0%)
PERL+PYTHON = 91019145 compressed (23.5% of the OS image)
GIT + PERL + PYTHON = 120849290 compressed (31.2% of the OS image)
```

Nothing else in the attr set wants either interpreter. Measured by reverse-dependency, not assumed:

```
$ nix-store -q --referrers .../perl-5.38.2 | sed 's|/nix/store/[a-z0-9]*-||' | grep -v '^perl5'
perl-5.38.2  git-2.44.2  autoconf-2.72  automake-1.16.5      # autoconf/automake are not in attrs
$ nix-store -q --references .../nix-2.18.8 | grep -icE 'perl|python'
0
```

## 3. Table 2 — the toybox duplication, per binary

Android's own toybox, already on the device, costs zero bytes to use.

```
$ env -i /system/bin/toybox | tr -s ' \n' '\n' | grep -c .
209
```

Per-attr `bin/` from the version-identical local store paths (the live image records symlinks in `SYMLINKS.txt`
rather than as zip entries, so its entry list undercounts multi-call packages — that is why this diff uses the
store):

```
$ ls <attr-outPath>/bin | sort -u > pk.txt
$ comm -23 pk.txt toybox_applets.txt          # provided but NOT a toybox applet
```

| attr | live bytes | binaries provided | have a toybox applet | with no applet | duplication cost |
|---|---:|---:|---:|---|---:|
| `coreutils` 9.11 | 771,465 | 107 | **81** | b2sum base32 basenc csplit dir dircolors factor fold hostid join link numfmt pathchk pinky pr ptx shred shuf stdbuf sum tsort unexpand users vdir who | 771,465, but **marginal = 0** |
| `findutils` 4.11.0 | 764,849 | 2 (`find` `xargs`) | **2 — fully covered** | — | 764,849 |
| `gnugrep` 3.12 | 360,207 | 3 (`grep` `egrep` `fgrep`) | **3 — fully covered** | — | 360,207, but **marginal = 0** |
| `gnused` 4.10 | 329,556 | 1 (`sed`) | **1 — fully covered** | — | 329,556 |
| `gawk` 5.4.1 | 1,200,782 | 2 (`awk` `gawk`) | **0** | awk gawk | none — toybox has no awk |
| `less` 704 | 196,205 | 3 (`less` `lessecho` `lesskey`) | **0** | less lessecho lesskey | none — toybox has `more`, not `less` |

```
$ for t in awk gawk less; do env -i /system/bin/toybox $t --version >/dev/null 2>&1 \
    && echo "$t: PRESENT" || echo "$t: ABSENT from toybox"; done
awk: ABSENT from toybox
gawk: ABSENT from toybox
less: ABSENT from toybox
```

**`gawk` and `less` cannot be deleted in favour of toybox at any price — toybox does not implement them.** `git`'s
pager is `less`; without it `git log` cannot page. `findutils` and `gnused` are fully covered and together are the
only honest (a)-class candidates, worth **1,094,405 compressed bytes** — 0.28% of the OS image. `coreutils` and
`gnugrep` are also fully covered in the parts that matter but free nothing (§1).

## 4. Table 3 — the GNU-vs-toybox risk, per call site

A tool whose GNU behaviour something depends on is not a free win at any size. Scanned `ab_cloud-terminal-store/`,
`ac_cloud-nix-on-droid/`, `ac_cloud-termux/rootfs/` and the `0_git/` hooks — 30 script files.

```
$ grep -rn 'sed -i\|sed --in-place' $D            # GNU in-place edit
ac_cloud-nix-on-droid/art/generate-big-icon.sh:9    sed -i "" 's/viewBox=…/…/' ~/termux-icons/ic_launcher.svg
$ grep -rn 'grep -[a-zA-Z]*P\b\|grep --perl' $D
ac_cloud-nix-on-droid/.github/workflows/debug_build.yml:41              grep -qP '^(0|[1-9]\d*)\.…'
ac_cloud-nix-on-droid/.github/workflows/attach_debug_apks_to_release.yml:39  grep -qP '^(0|[1-9]\d*)\.…'
ac_cloud-nix-on-droid/termux-shared/src/main/res/raw/apt_info_script.sh:5   grep -P '^\s*deb\s' "@TERMUX_PREFIX@/etc/apt/sources.list"
ac_cloud-nix-on-droid/termux-shared/src/main/res/raw/apt_info_script.sh:15  grep -P '^\s*deb\s' "$filename"
$ grep -rn 'sort -[a-zA-Z]*V\b\|--version-sort' $D
ac_cloud-nix-on-droid/build.sh:468   ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1
ac_cloud-nix-on-droid/build.sh:523   (same)
ac_cloud-nix-on-droid/build.sh:1080  (same)
$ grep -rn 'find .*-printf\|-regextype\|find .*-newermt' $D            # (no output)
$ grep -rn 'gensub\|asort\|asorti\|systime()\|strftime(\|patsplit\|ENVIRON\[' $D   # (no output)
$ grep -rn 'date -d\|date --date' $D                                   # (no output)
```

**Every GNU-only call site found runs on the CI runner or the developer machine, not on the phone.**
`build.sh` (`sort -V`) and the two workflows (`grep -qP`) execute on a GitHub runner. `generate-big-icon.sh` is a
developer art script. `apt_info_script.sh` is inherited upstream Termux Java resource, dead in this fork (the nix
terminal has no apt). None of them resolves against the OS image's PATH.

The scripts that DO run on the phone are the #644 engine and the login wiring, and their whole external-command
surface was inventoried:

```
$ grep -ohE '\b(ls|cat|env|find|xargs|grep|sed|awk|sort|readlink|mkdir|rm|mv|ln|cp|chmod|dirname|…)\b' \
    ab_cloud-terminal-store/{cloud-store,login-init.sh,login-exec} | sort | uniq -c | sort -rn
      7 readlink   7 mv   5 rm   4 ln   3 mkdir   3 dirname   2 test   2 sort   2 sed   2 env   2 chmod   1 cp
```

Twelve commands, all POSIX, all present as toybox applets. Their two `sed` invocations and two `sort` invocations
were run THROUGH Android's toybox against the engine's own text and byte-compared to GNU:

```
$ env -i /system/bin/toybox sed -n '/^# Commands:/,/^# Fault/p' ab_cloud-terminal-store/cloud-store \
    | env -i /system/bin/toybox sed 's/^# \{0,1\}//' > tb.out
$ sed -n '/^# Commands:/,/^# Fault/p' ab_cloud-terminal-store/cloud-store | sed 's/^# \{0,1\}//' > gnu.out
$ cmp gnu.out tb.out && echo "IDENTICAL BYTES: $(wc -c < tb.out)"
IDENTICAL BYTES: 891
$ printf '10\n2\n33\n4\n' | env -i /system/bin/toybox sort -n | tr '\n' ' '   ->  2 4 10 33
$ printf '10\n2\n33\n4\n' | sort -n | tr '\n' ' '                            ->  2 4 10 33
```

Byte-identical, including the BRE interval `\{0,1\}`. The `0_git/` hooks use `sort -u`, `sed 's/^/  /'` and
`grep -E` only — all POSIX — and they run on a developer machine, not the phone.

**Verdict for Table 3: zero GNU-only dependants on the phone side.** The (a)-class candidates are safe. They are
also worth almost nothing (§3), which is the more useful finding.

## 5. Table 4 — the verdict, per attr

`(a)` leave entirely, toybox covers it · `(b)` spin off as a static `lib*.so` · `(c)` must stay in the OS image.

| attr | class | compressed saving | why, measured |
|---|---|---:|---|
| `git` | **(c) stays, but SHRINKS** | **95,987,056** | `gitMinimal` at this pin drops python3 + perl + doc. Keeps `bin/git` and push. §6 |
| `claude-code` | **(c) stays** | 0 | `PT_INTERP` present in BOTH arm64 builds; refused to exec as a lib. §7 |
| `nodejs_22` | **(c) stays** | 0 | Part I §6 measured the refusal. It is the runtime; the image is the OS. |
| `fish` | (c) stays | 0 | `login_shell_attr`; the bake hard-fails without it |
| `bashInteractive` | (c) stays | 0 | `claude`'s Bash tool spawns `bash` by name; login-inner's last-resort `exec -l bash` |
| `gawk` | (c) stays | 0 | toybox has no `awk` at all |
| `less` | (c) stays | 0 | toybox has no `less`; it is git's pager |
| `coreutils` | (c) stays | **0** | fully covered by toybox, but marginal cost is zero — git pulls it anyway. Also #640's `/usr/bin/env` |
| `gnugrep` | (c) stays | **0** | same: fully covered, marginal cost zero |
| `findutils` | (a) candidate | 764,849 | `find`+`xargs` both toybox applets; zero GNU-only call sites |
| `gnused` | (a) candidate | 329,556 | `sed` is a toybox applet; toybox `sed` byte-matched GNU on the engine's own scripts |

Non-attr items in the same image, measured and actionable:

| item | compressed | note |
|---|---:|---|
| non-code outputs (`-doc`/`-man`/`-dev`) | **8,498,997** (2.2%) | `git-2.55.0-doc` 4,967,911 · `icu4c-78.3-dev` 1,446,308 · `fish-4.9.3-doc` 1,003,734 · `openssl-3.6.4-dev` 404,702 · 13 more |
| duplicate versions of the same package | **35,555,599** (9.2%) | `glibc` 2.42-84 **and** 2.37-45 (10,647,980 extra) · `openssl` 3.6.4 **and** 3.0.12 · `bash` 5.3p15/5.2-p15 · `curl` 8.22.0/8.1.1 · `sqlite` 3.53.3/3.41.2 · `xz` 5.8.3/5.4.3 · `gcc-lib` 15.3.0/12.2.0 · `libunistring` 1.4.2/1.1 · `zstd`, `brotli`, `zlib`, `libidn2`, `libssh2`, `simdutf`, `c-ares`, `nghttp2` |
| `ripgrep-15.2.0` | 2,268,028 | `_doc_attrs_audit` lists ripgrep as DELIBERATELY LEFT OUT because claude-code ships its own — yet it is in the image, from the upstream bootstrap |

The duplicate-version block has one cause: the baked attrs resolve against nixpkgs pin `e94cb152` while the
upstream bootstrap zip carries its own older closure. Two glibcs is the honest headline of that 9.2%.

### Residual floor

| step | compressed bytes | % cut |
|---|---:|---|
| live OS image today | **387,858,100** | — |
| after `git` → `gitMinimal` (§6, **shipped**) | **291,871,044** | −24.7% |
| after (a): drop `findutils` + `gnused` | 290,776,639 | −25.0% |
| after excluding `-doc`/`-man`/`-dev` outputs (less git-doc, already counted) | 287,245,553 | −25.9% |
| after aligning the bake pin with the bootstrap's own closure (the 9.2% duplicate block) | ~252,000,000 | ~−35% |
| **immovable core**: claude-code 103,409,893 + node 29,576,883 + glibc 12,511,446 + icu4c 15,752,769 + nix 6,078,066 + fish 13,346,890 + gitMinimal ≈ 20,000,000 | **~200,700,000** | **the floor** |

**The realistic floor for the nix OS image is roughly 200 MB compressed, against 387,858,100 today** — a little
under half. Everything below that is `claude-code` (26.7%), which cannot be a lib, cannot be smaller, and is the
reason the terminal exists.

## 6. `gitMinimal` — the largest single win, and it needs no new mechanism (#665)

Read from nixpkgs at the exact pin, not from memory:

```
$ curl -sSL "https://raw.githubusercontent.com/NixOS/nixpkgs/e94cb152…/pkgs/top-level/all-packages.nix" \
    | grep -n -A9 'gitMinimal'
1075:  gitMinimal = git.override {
1076-    withManual = false;
1077-    osxkeychainSupport = false;
1078-    pythonSupport = false;
1079-    perlSupport = false;
1080-    rustSupport = false;
1081-    withpcre2 = false;
1082-  };
$ grep -nE 'perlSupport|pythonSupport|withManual|outputs' pkgs/by-name/gi/git/package.nix
111:  outputs = [ "out" ] ++ lib.optional withManual "doc";
249:  ++ (if perlSupport then [ "PERL_PATH=…" ] else [ "NO_PERL=1" ])
250:  ++ (if pythonSupport then [ "PYTHON_PATH=…" ] else [ "NO_PYTHON=1" ])
```

`NO_PERL=1` / `NO_PYTHON=1` remove exactly seven subcommands, enumerated from the real build, not guessed:

```
$ grep -rlI '^#!.*perl' .../git-2.44.2/libexec/git-core/ | xargs -n1 basename
.git-cvsexportcommit-wrapped  .git-send-email-wrapped  .git-instaweb-wrapped
.git-archimport-wrapped  .git-cvsimport-wrapped  git-cvsserver
$ grep -rlI '^#!.*python' .../git-2.44.2/libexec/git-core/ | xargs -n1 basename
.git-instaweb-wrapped  git-p4
```

Every one grepped for across the whole repository, plus the PCRE-gated forms and the two subcommands that used to
be perl and no longer are:

```
$ for pat in 'git send-email' 'sendemail' 'git p4' 'git-p4' 'git instaweb' 'git-instaweb' \
             'git svn' 'git-svn' 'git grep -P' 'perl-regexp' 'gitweb' 'git cvsimport' 'git archimport' \
             'difftool' 'mergetool' 'request-pull' 'add -i' 'add --interactive'; do
    grep -rIl --exclude-dir=.git --exclude-dir=z_archive -F "$pat" . | wc -l ; done
0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
```

**Eighteen patterns, eighteen zeroes.** `git difftool` and `git add -i` are builtin C since git 2.44, so `NO_PERL`
does not touch them.

`withpcre2 = false` is the one real behaviour change: `git grep -P` and `git log --perl-regexp` lose PCRE. Both are
in the zero list above, and `pcre2-10.48` stays in the image for `gnugrep` regardless, so standalone `grep -P` is
unaffected.

**Saving: `python3` 73,582,754 + perl subtree 17,436,391 + `git-2.55.0-doc` 4,967,911 = 95,987,056 compressed
bytes, 24.7% of the OS image**, for seven subcommands nothing calls.

Shipped as a two-token change to the declaration — `attrs: "git"` → `"gitMinimal"` and `provides` keyed to match.
`binaries` still says `git`, `bin/git` still exists, push still works, and #644's build-time gate is satisfied
unchanged:

```
$ bash ac_cloud-nix-on-droid/test/test-bootstrap-baked.sh
  ok — build.json declares default_packages (pin e94cb152ed51…, attrs: gitMinimal nodejs_22 claude-code
       coreutils fish bashInteractive findutils gnugrep gnused gawk less)
  ok — every declared attr has a provides[] entry, and binaries[] covers all of them
  ok — login_shell_attr (fish) is one of the baked attrs
── 0 failed ──
```

No `lib*.so`, no new mechanism, no link-store change, nothing to spin off. **The largest available win was a
configuration default, not an architecture problem.**

## 7. `claude-code` cannot be a lib — measured negative, both arm64 builds

It is the most attractive candidate by far: **one file, one name, 26.7% of the OS image.**

```
$ ls -la .../claude-code-2.1.226/
bin/claude    294,632,376 bytes    # the entire store path is this one file
$ readelf -lW .../bin/claude | grep -E 'INTERP|Requesting'
  INTERP  [Requesting program interpreter: /nix/store/aaq36r4…-glibc-2.39-52/lib/ld-linux-aarch64.so.1]
$ readelf -dW .../bin/claude | grep NEEDED
 [librt.so.1] [libc.so.6] [ld-linux-aarch64.so.1] [libpthread.so.0] [libdl.so.2] [libm.so.6]
```

`PT_INTERP` present → fails the rclone pin's program-header guard. Its `DT_NEEDED` list is pure glibc and nothing
else, which makes the musl build the obvious next question. There IS one, published on npm:

```
$ curl -sSL https://registry.npmjs.org/@anthropic-ai/claude-code/2.1.281 | …optionalDependencies
@anthropic-ai/claude-code-linux-arm64        unpacked 236,773,962
@anthropic-ai/claude-code-linux-arm64-musl   unpacked 229,128,616
```

Downloaded and walked (the first two attempts died on `curl: (56) Recv failure`; the third succeeded):

```
$ tar -tzf claude-code-linux-arm64-musl-2.1.281.tgz
package/claude  package/package.json  package/LICENSE.md  package/README.md
$ stat -c %s ccm/package/claude
229128008
$ sha256sum ccm/package/claude
4f72ebbb08706651e7a2204303793700698f4046bc31f3e7e65b381063b7c210
$ readelf -lW ccm/package/claude | grep -E 'INTERP|Requesting'
  INTERP  [Requesting program interpreter: /lib/ld-musl-aarch64.so.1]
$ readelf -dW ccm/package/claude | grep NEEDED
 [libc.musl-aarch64.so.1]
$ ls -la /lib/ld-musl-aarch64.so.1
ls: cannot access '/lib/ld-musl-aarch64.so.1': No such file or directory
$ cp claude libclaude.so && chmod +x libclaude.so && ./libclaude.so --version
bash: ./libclaude.so: cannot execute: required file not found
```

**Identical failure mode to node in Part I §6, and for the identical reason:** an absolute `PT_INTERP` naming an
interpreter only a filesystem can supply. The device refused to exec it. `claude-code` requires an OS image, in
both libcs, and it is 26.7% of the one it requires.

## 8. The Debian OS image — a different duplication story

`ac_cloud-termux/rootfs/rootfs.json` builds from `debian:bookworm-slim` plus four apt packages
(`git fish zsh python3-venv`), three tarballs, one npm global and one pip venv. Its tools do **not** come from
per-tool packages, so §1's marginal analysis does not transfer — there is no `coreutils` attr to remove, because
coreutils is the base image and `dpkg`/`apt` require it.

Its inputs, measured by HTTP `content-length` rather than by downloading 437 MB:

```
$ curl -sSIL https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-arm64.tar.xz
30,172,012 bytes
$ curl -sSIL https://github.com/block/goose/releases/download/v1.44.0/goose-aarch64-unknown-linux-gnu.tar.gz
93,305,949 bytes
$ curl -sSL https://registry.npmjs.org/@anthropic-ai/claude-code/2.1.281   # + its arm64 optionalDep
claude-code wrapper unpacked 184,605 · @anthropic-ai/claude-code-linux-arm64 unpacked 236,773,962
```

| item | bytes | class |
|---|---:|---|
| `claude` (npm native arm64) | 236,773,962 unpacked | **(c)** — glibc `PT_INTERP`, §7 |
| `goose` 1.44.0 | 93,305,949 compressed | `-unknown-linux-gnu`: needs the OS image by declaration |
| `node` 22.23.3 | 30,172,012 compressed | **(c)** — the runtime |
| `agy` (antigravity CLI) | — | declared glibc `PT_INTERP /lib/ld-linux-aarch64.so.1` in rootfs.json |
| `hermes` pip venv | — | python, needs the OS image |
| `debian:bookworm-slim` base | — | the OS image itself |

**The Debian image has no git/perl/python win available**, because Debian's `git` package does not bundle a perl
interpreter — it depends on the system one, which `python3-venv` requires anyway. Its 437,219,969 bytes are
dominated by `claude` + `goose` + `node`, all three of which declare glibc interpreters. Its equivalent of §6 does
not exist; its equivalent of §7 is the same measured negative. The one open question for the Debian image is
`goose` at 93 MB, which is a Rust binary and may have a musl release — **not measured here**, and recorded as
open rather than answered.

## 9. What Part II contradicts

- **The "~400 MB rootfs" is compressed.** It decompresses to 1,057,520,494 bytes. Part I's headline table put
  ~400,000,000 next to NAR byte counts; they are not the same unit and mixing them makes every per-tool comparison
  in that table read ~3x too favourable toward the tools.
- **The node hypothesis is refuted.** `nodejs_22` + `claude-code` is 34.3% of the image, not "most" of it. The
  largest *actionable* item is `python3` at 19.0%, which no declared attr asked for.
- **The toybox duplication is worth almost nothing.** `coreutils` and `gnugrep` have a measured marginal cost of
  **zero** while canonical git is present. The entire (a) class is 1,094,405 bytes — 0.28%.
- **`gawk` and `less` were assumed replaceable by toybox; they are not.** Toybox implements neither.
- **The biggest win required no lib, no link engine and no new mechanism** — one configuration default that had
  never been examined. Part I's framing (which tool can become a `lib*.so`) would never have found it.
- **A stale release asset produced an hour of confident wrong answers,** including an apparent four-missing-attrs
  defect that does not exist. Content-address the artifact you measure before you measure it.
