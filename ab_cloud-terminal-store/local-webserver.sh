# local-webserver.sh -- shared by BOTH Cloud Terminals: at session login, start my-webserver
# (cloud-u-linux da_my-webserver, the per-arch Node SEA) on the terminal's declared loopback
# port, serving that terminal's $HOME -- once: a second session finds the port listening and
# starts nothing.
#
# Not used as-is. render-local-webserver.py prepends the values from the app's
# build.json::runtime.webserver (the ONE declaration of the port), and:
#   termux  build-rootfs.sh stages the result as cloud-store/local-webserver.sh beside enter.sh,
#           which sources it before it execs the session proot;
#   nix     bake_default_packages.py inlines it into bin/login before its proot exec.
# Each caller hands cloud_webserver_autostart its OWN launcher (its proot + binds + the guest
# path of the binary), so the binary always runs inside the glibc root it was built for.
#
# Security: my-webserver binds 127.0.0.1 only and refuses non-loopback peers. Writes stay
# token-gated and OFF: HTTPD_WRITE / HTTPD_WRITE_ROOTS / HTTPD_WRITE_TOKEN_FILE are unset for
# the child, so a login environment can never turn them on by accident.
#
# Runs in mksh (Android /system/bin/sh) before any proot, so tools are called by their
# /system/bin path, as bin/login's storage block already does; the tests rewrite that prefix.

# 0 when something LISTENs on TCP port $1 (IPv4 or IPv6, any local address). /proc/net/tcp is
# the only probe both hosts have without a network client; when it is unreadable the server is
# started anyway and a taken port ends it at once with EADDRINUSE in the log -- never a second
# listener.
cloud_webserver_listening() {
    _cw_hex="$(printf '%04X' "$1")"
    for _cw_f in ${CLOUD_WEBSERVER_PROC_NET:-/proc/net/tcp /proc/net/tcp6}; do
        [ -r "$_cw_f" ] || continue
        /system/bin/grep -qiE "^ *[0-9]+: [0-9A-F]+:$_cw_hex [0-9A-F]+:[0-9A-F]+ 0A " "$_cw_f" && return 0
    done
    return 1
}

# cloud_webserver_autostart <root as the guest sees it> <launcher ... binary>
# Never fails the login: every outcome returns 0.
cloud_webserver_autostart() {
    _cw_root="$1"; shift
    [ -z "${CLOUD_WEBSERVER_DISABLE:-}" ] || return 0
    if cloud_webserver_listening "$CLOUD_WEBSERVER_PORT"; then
        return 0
    fi
    (
        unset HTTPD_WRITE HTTPD_WRITE_ROOTS HTTPD_WRITE_TOKEN_FILE LD_PRELOAD
        HTTPD_VERBOSE=0
        export HTTPD_VERBOSE
        exec </dev/null >"$CLOUD_WEBSERVER_LOG" 2>&1
        # Its own session, so closing the terminal session that started it does not hang it up.
        if [ -x /system/bin/setsid ]; then
            exec /system/bin/setsid "$@" "$CLOUD_WEBSERVER_PORT" "$_cw_root"
        fi
        trap '' HUP
        exec "$@" "$CLOUD_WEBSERVER_PORT" "$_cw_root"
    ) &
    return 0
}
