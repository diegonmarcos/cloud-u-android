#!/usr/bin/env bash
# Posts Claude Code usage to c3-infra-api (POST /ai/usage/claude) for the Cloud SuperApp AI card (#1151).
# ONE script, two carriers (the SoT is this file):
#   * statusLine path (laptop / Termux): statusline-command.sh calls
#       ai-usage-post.sh statusline <5h_used_pct> <5h_resets_at> <7d_used_pct> <7d_resets_at> [session_id]
#     because only the statusLine JSON carries rate_limits (hook inputs do not). Installed to ~/.claude by the my-ai
#     statusline-assets engine as claude-ai-usage-post.sh.
#   * hook path (cloud sessions, which run no statusLine): wired in settings.project.json on StopFailure, no arguments,
#     hook JSON on stdin. The only usage fact a hook carries is error=rate_limit, posted as {"rate_limited":true}.
# Never blocks and never fails the caller: the network part is detached, bounded by curl --max-time, always exit 0.
# Throttled to one post per AI_USAGE_MIN_INTERVAL seconds (default 60) per mode, across all sessions of the host.
# Auth: mesh leg first (the mesh is trusted, no credential); public leg with a scoped bearer from C3_AI_USAGE_TOKEN
# (or the file C3_AI_USAGE_TOKEN_FILE, or minted from the AUTHELIA_* client credentials the cloud environment injects).
# The credential travels on curl's stdin config, never in argv, and is never printed.
# Env: C3_AI_USAGE_MESH_URL (default https://c3-infra-api.mesh.diegonmarcos.com), C3_AI_USAGE_URL (public, default
#      https://c3-pvt.diegonmarcos.com/c3-infra-api), AI_USAGE_SYNC=1 (foreground, for tests), AI_USAGE_STATE (state dir).
set -u
MODE="${1:-hook}"
STATE="${AI_USAGE_STATE:-${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}}"
MIN="${AI_USAGE_MIN_INTERVAL:-60}"
case "$MIN" in ""|*[!0-9]*) MIN=60;; esac
MESH="${C3_AI_USAGE_MESH_URL:-https://c3-infra-api.mesh.diegonmarcos.com}"
PUB="${C3_AI_USAGE_URL:-https://c3-pvt.diegonmarcos.com/c3-infra-api}"

num()  { case "$1" in ""|*[!0-9.]*|*.*.*|.*) return 1;; esac; return 0; }
int()  { case "$1" in ""|*[!0-9]*) return 1;; esac; return 0; }
safe() { printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-"${2:-40}"; }

# ── build the JSON body (no jq: every interpolated value is validated or sanitised) ──
HOST="$(safe "${AI_USAGE_HOST:-$(hostname 2>/dev/null || echo host)}" 40)"
BODY=""
if [ "$MODE" = "statusline" ]; then
    p5="${2:-}"; r5="${3:-}"; p7="${4:-}"; r7="${5:-}"; sid="$(safe "${6:-}" 40)"
    W=""
    if num "$p5" && int "$r5"; then W="\"five_hour\":{\"used_percentage\":$p5,\"resets_at\":$r5}"; fi
    if num "$p7" && int "$r7"; then W="${W:+$W,}\"seven_day\":{\"used_percentage\":$p7,\"resets_at\":$r7}"; fi
    [ -n "$W" ] || exit 0
    BODY="{\"source\":\"statusline\",\"host\":\"$HOST\"${sid:+,\"session_id\":\"$sid\"},$W}"
else
    MODE=hook
    IN="$(cat 2>/dev/null || true)"
    case "$IN" in
        *'"hook_event_name":"StopFailure"'*|*'"hook_event_name": "StopFailure"'*) ;;
        *) exit 0 ;;
    esac
    case "$IN" in *'"error":"rate_limit"'*|*'"error": "rate_limit"'*) ;; *) exit 0 ;; esac
    sid=""; case "$IN" in *'"session_id":"'*) sid="${IN#*\"session_id\":\"}"; sid="$(safe "${sid%%\"*}" 40)";; esac
    BODY="{\"source\":\"cloud-hook\",\"host\":\"$HOST\"${sid:+,\"session_id\":\"$sid\"},\"rate_limited\":true}"
fi

# ── throttle: stamp first, so concurrent sessions dedupe ──
STAMP="$STATE/claude-ai-usage-post.$MODE.stamp"
now="$(date +%s)"; prev=0
[ -f "$STAMP" ] && read -r prev < "$STAMP" 2>/dev/null
int "$prev" || prev=0
[ $(( now - prev )) -ge "$MIN" ] || exit 0
echo "$now" > "$STAMP" 2>/dev/null || true

send() {
    command -v curl >/dev/null 2>&1 || return 0
    tmp="$(mktemp "$STATE/claude-ai-usage-post.XXXXXX" 2>/dev/null)" || return 0
    chmod 600 "$tmp" 2>/dev/null
    printf '%s' "$BODY" > "$tmp"
    # 1) mesh leg: trusted network, no credential, short timeout
    code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 4 -X POST \
        -H 'Content-Type: application/json' --data-binary "@$tmp" "$MESH/ai/usage/claude" 2>/dev/null)" || code=000
    case "$code" in 2??) rm -f "$tmp"; return 0;; esac
    # 2) public leg with the scoped bearer
    tok="${C3_AI_USAGE_TOKEN:-}"
    [ -z "$tok" ] && [ -n "${C3_AI_USAGE_TOKEN_FILE:-}" ] && [ -r "$C3_AI_USAGE_TOKEN_FILE" ] && tok="$(head -c 4096 "$C3_AI_USAGE_TOKEN_FILE" | tr -d '\r\n ')"
    if [ -z "$tok" ] && [ -n "${AUTHELIA_TOKEN_URL:-}" ] && [ -n "${AUTHELIA_OIDC_CLIENT_ID:-}" ] && [ -n "${AUTHELIA_OIDC_CLIENT_SECRET:-}" ]; then
        cache="$STATE/claude-ai-usage-post.token"
        if [ -f "$cache" ] && [ $(( now - $(date -r "$cache" +%s 2>/dev/null || echo 0) )) -lt 240 ]; then tok="$(cat "$cache" 2>/dev/null)"
        else
            tok="$(printf 'url = "%s"\nuser = "%s:%s"\ndata = "grant_type=client_credentials&scope=authelia.bearer.authz"\n' \
                "$AUTHELIA_TOKEN_URL" "$AUTHELIA_OIDC_CLIENT_ID" "$AUTHELIA_OIDC_CLIENT_SECRET" \
                | curl -s --max-time 8 -K - 2>/dev/null | sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p')"
            [ -n "$tok" ] && { ( umask 077; printf '%s' "$tok" > "$cache" ) 2>/dev/null; }
        fi
    fi
    if [ -n "$tok" ]; then
        printf 'header = "Authorization: Bearer %s"\n' "$tok" \
            | curl -s -o /dev/null --connect-timeout 3 --max-time 6 -X POST -H 'Content-Type: application/json' \
                --data-binary "@$tmp" -K - "$PUB/ai/usage/claude" >/dev/null 2>&1
    fi
    rm -f "$tmp"
}

if [ "${AI_USAGE_SYNC:-0}" = "1" ]; then send; else ( send ) </dev/null >/dev/null 2>&1 & disown 2>/dev/null; fi
exit 0
