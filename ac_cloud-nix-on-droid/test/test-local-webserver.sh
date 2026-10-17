#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ Option 37.1: bin/login starts my-webserver on build.json's port, ONCE     ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# This terminal serves its own $HOME through my-webserver on 127.0.0.1:<port>, where <port> is
# build.json::runtime.webserver (8001; the termux terminal has 8000). bake_default_packages.py
# (patch_bin_login_webserver) inlines the shared ab_cloud-terminal-store/local-webserver.sh,
# rendered with that port, into bin/login, plus ONE launch DERIVED from bin/login's own proot
# exec: same proot, same binds, no `exec`, no --kill-on-exit, the binary under the baked profile.
#
#  1. DECLARED: runtime.webserver is 8001 / 127.0.0.1 / $HOME; my-webserver is an extras attr
#     built by rootfs-extras.nix from the termux rootfs.json entry, with both arch hashes pinned
#     for the version that entry declares.
#  2. GENERATED: on a fixture bin/login the patch keeps exactly one exec, puts the block before
#     it, copies every bind, drops --kill-on-exit and login-inner, and refuses an exec it cannot
#     parse (two execs; one that does not end in login-inner).
#  3. EXECUTED with stubs: the patched bin/login runs under sh with its proot replaced by a stub
#     that records argv + environment, and /proc/net/tcp{,6} by fixtures. Port free: exactly one
#     `<profile>/bin/my-webserver 8001 $HOME` launch with the binds and HTTPD_WRITE* scrubbed, and
#     the session exec still happens. 127.0.0.1:8001 LISTENing (or the first login's server): no
#     launch. A listener on 8000 (the termux terminal's) does not count.
#
# Offline, no Android SDK, no nix: python3, sh and coreutils.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
BAKE_PY="$DIR/app/src/main/cpp/bake_default_packages.py"
APP=cld.termux.nix
PROFILE=nix/var/nix/profiles/per-user/nix-on-droid/profile
fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

echo "── local webserver autostart [$(basename "$DIR")] ──"

# ── 1. declared ──────────────────────────────────────────────────────────────
python3 - "$DIR" <<'PY' && ok "runtime.webserver = 8001 on 127.0.0.1 serving \$HOME; my-webserver is an extras attr pinned for rootfs.json's version, both arches" || bad "the runtime.webserver / extras declaration is wrong (see above)"
import json, os, sys
d = sys.argv[1]
b = json.load(open(os.path.join(d, "build.json")))
assert b["runtime"]["webserver"] == {"port": 8001, "bind": "127.0.0.1", "root": "$HOME", "binary": "my-webserver"}, b["runtime"]
dp = b["forks"]["nixdroid"]["bootstrap"]["default_packages"]
assert "my-webserver" in dp["extras"]["attrs"] and dp["provides"]["my-webserver"] == ["my-webserver"]
assert "my-webserver" in dp["binaries"]
pin = dp["extras"]["pins"]["my-webserver"]
r = json.load(open(os.path.join(d, "..", "ac_cloud-termux", "rootfs", "rootfs.json")))["tarballs"]["my-webserver"]
assert pin["version"] == r["version"], (pin["version"], r["version"])
assert set(pin["hash"]) == set(r["arch_names"]) == {"amd64", "arm64"}
assert all(h.startswith("sha256-") for h in pin["hash"].values())
nix = open(os.path.join(d, "app", "src", "main", "cpp", "rootfs-extras.nix")).read()
assert 'my-webserver =' in nix and 'rootfs.tarballs."my-webserver"' in nix and 'dontStrip = true' in nix
PY

# ── 2. generated ────────────────────────────────────────────────────────────
T="$(mktemp -d)" && [ -n "$T" ] && [ -d "$T" ] || { echo "::error::FAIL — mktemp -d failed"; exit 1; }
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/system/bin" "$T/home" "$T/net"
for c in grep date; do ln -s "$(command -v $c)" "$T/system/bin/$c"; done

python3 - "$BAKE_PY" "$T" "$APP" "$PROFILE" <<'PY' || bad "patch_bin_login_webserver did not produce the expected bin/login (see above)"
import importlib.util, sys
spec = importlib.util.spec_from_file_location("bake", sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
t, app, profile = sys.argv[2], sys.argv[3], sys.argv[4]
pre = f"/data/data/{app}/files"
login = (
    "#!/system/bin/sh\nset -e\n"
    f'export HOME="{pre}/home"\n'
    + m.BIN_LOGIN_TRACE +
    f"exec {pre}/usr/bin/proot-static \\\n"
    f"  -b {pre}/usr/nix:/nix \\\n"
    f"  -b {pre}/usr/etc:/etc \\\n"
    "  --kill-on-exit \\\n"
    "  --link2symlink --kill-on-exit --sysvipc \\\n"
    f'  {pre}/usr/bin/sh {pre}/usr/usr/lib/login-inner "$@"\n'
)
rendered = m.render_local_webserver(m.webserver_build_json())
out = m.patch_bin_login_webserver(login, app, profile, rendered)
exec_line = f"exec {pre}/usr/bin/proot-static \\"
assert out.count(exec_line) == 1, "the session exec is not there exactly once"
assert out.index("cloud_webserver_autostart \"$HOME\"") < out.index(exec_line), "the block is not before the exec"
blk = out[out.index("cloud_webserver_autostart \"$HOME\""):out.index(exec_line)]
assert f"-b {pre}/usr/nix:/nix" in blk and f"-b {pre}/usr/etc:/etc" in blk, "a bind was not copied"
assert "--kill-on-exit" not in blk and "login-inner" not in blk and "--link2symlink" in blk and "--sysvipc" in blk
assert f"/{profile}/bin/my-webserver" in blk
assert "CLOUD_WEBSERVER_PORT=8001" in out
for bad_login, why in ((login + login.split("\n", 2)[2], "two execs"),
                       (login.replace('/usr/usr/lib/login-inner "$@"', '/usr/bin/something-else "$@"'), "no login-inner")):
    try:
        m.patch_bin_login_webserver(bad_login, app, profile, rendered)
    except ValueError:
        continue
    raise AssertionError("an exec of another shape was accepted: " + why)
# Runnable copy: the app's paths into the temp dir, /system/bin into the stubs.
run = out.replace(f"{pre}/usr/bin/proot-static", t + "/proot").replace("/system/bin/", t + "/system/bin/")
run = run.replace(f'export HOME="{pre}/home"', f'export HOME="{t}/home"')
run = run.replace("set -e\n", f"set -e\necho $$ > {t}/loginpid\n", 1)
open(t + "/login.sh", "w").write(run)
PY
[ -f "$T/login.sh" ] && ok "bin/login: one block before the one exec, every bind copied, no --kill-on-exit, no login-inner; an unparseable exec is refused"

# ── 3. executed with stubs ─────────────────────────────────────────────────
cat > "$T/proot" <<EOF
#!/bin/sh
case "\$*" in
  *login-inner*) i=0; while [ ! -f "$T/launch" ] && [ \$i -lt 20 ]; do sleep 0.1; i=\$((i + 1)); done
                 echo session-exec > "$T/session"; exit 0 ;;
esac
{ echo "--ppid \$PPID"; printf '%s\n' "\$@"; echo "--env HTTPD_WRITE=\${HTTPD_WRITE-unset} HTTPD_WRITE_ROOTS=\${HTTPD_WRITE_ROOTS-unset}"; } > "$T/launch.\$\$"
mv "$T/launch.\$\$" "$T/launch"
[ -z "\${LISTEN:-}" ] || echo '   1: 0100007F:1F41 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10001 0 1 1 0000000000000000 100 0 0 10 0' >> "$T/net/tcp"
EOF
chmod 0755 "$T/proot"
SH="$(command -v sh)"
HDR='  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode'
net() { printf '%s\n%s' "$HDR" "$1" > "$T/net/tcp"; printf '%s\n' "$HDR" > "$T/net/tcp6"; }
login() {
    rm -f "$T/launch" "$T/session"
    CLOUD_WEBSERVER_PROC_NET="$T/net/tcp $T/net/tcp6" HTTPD_WRITE=1 HTTPD_WRITE_ROOTS=/ \
        "$SH" "$T/login.sh" x >"$T/out" 2>&1 || return 1
    [ -f "$T/session" ] || return 1
    i=0; while [ ! -f "$T/launch" ] && [ $i -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
    return 0
}
launched() { [ -f "$T/launch" ]; }

if [ -f "$T/login.sh" ]; then
    net ""
    if login && launched; then
        args="$(tr '\n' ' ' < "$T/launch")"
        case "$args" in
            *"/usr/nix:/nix "*"/usr/etc:/etc "*"--link2symlink --sysvipc /$PROFILE/bin/my-webserver 8001 $T/home --env HTTPD_WRITE=unset HTTPD_WRITE_ROOTS=unset"*)
                ok "port free: one my-webserver 8001 \$HOME under the profile, the exec's binds, HTTPD_WRITE* scrubbed; the session still execs" ;;
            *) bad "port free: the launch is wrong: $args" ;;
        esac
        case "$args" in
            "--ppid $(cat "$T/loginpid") "*) bad "the server is a direct child of bin/login, which execs the session proot in place: that proot would wait on it forever (double fork lost)" ;;
            "--ppid "*) ok "the server is not a child of bin/login (double fork): the session proot does not wait on it" ;;
            *) bad "the stub recorded no parent pid" ;;
        esac
    else
        bad "port free: no launch, or the session exec did not happen ($(cat "$T/out"))"
    fi
    net '   0: 0100007F:1F41 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10001 0 1 1 0000000000000000 100 0 0 10 0'
    login && ! launched && ok "127.0.0.1:8001 LISTENing: nothing started, the session execs" || bad "started a second server over a listening :8001"
    net '   0: 0100007F:1F40 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10001 0 1 1 0000000000000000 100 0 0 10 0'
    login && launched && ok "the termux terminal's :8000 listening does not stop this terminal's :8001" || bad "a listener on 8000 suppressed :8001"
    net ""
    LISTEN=1; export LISTEN
    login && launched && ok "first login: started" || bad "first login: not started"
    login && ! launched && ok "second login: already LISTENing, nothing started (idempotent)" || bad "second login started another server"
    unset LISTEN
fi

if [ "$fails" -ne 0 ]; then echo "::error::FAIL — $fails local webserver assertion(s)"; exit 1; fi
echo "PASS — local webserver autostart"
