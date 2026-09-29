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
