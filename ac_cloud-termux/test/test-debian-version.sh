#!/bin/sh
# The app's versionName ("0.118.4 (sha-dac09da6)") must reach dpkg as a valid Debian version.
#
# 2026-10-07 a first start failed with "dpkg: error: version '0.118.4 (sha-dac09da6)' has bad
# syntax: version string has embedded spaces". DebianVersion.of is the one place the name is made
# dpkg-safe (TERMUX_VERSION is built from it). This compiles it and asks dpkg itself, when the
# host has one, plus a regex of the Debian grammar when it does not. A mutant that returns the name
# unchanged must go red.
set -u
unset JAVA_TOOL_OPTIONS
DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$DIR/termux-shared/src/main/java/com/termux/shared/shell/DebianVersion.java"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0

cat > "$T/Main.java" <<'JAVA'
public class Main { public static void main(String[] a) { for (String s : a) System.out.println(com.termux.shared.shell.DebianVersion.of(s)); } }
JAVA
build() {  # $1 = DebianVersion.java; leaves classes in $T/out
    rm -rf "$T/out"; mkdir -p "$T/out"
    javac -d "$T/out" "$1" "$T/Main.java" 2>"$T/javac.log" || { cat "$T/javac.log"; return 1; }
}
RE='^[0-9][A-Za-z0-9.+~:-]*$'
check() {  # prints violations
    build "$1" || { echo "C0 does not compile"; return; }
    for name in "0.118.4 (sha-dac09da6)" "0.118.4" "1.2.3-rc.1 (sha-0123abcd)" "  2.0.0  " "v3 (sha-abc)"; do
        got="$(java -cp "$T/out" Main "$name")"
        case "$got" in *" "*) echo "V1 '$name' -> '$got' still has a space" ;; esac
        printf '%s\n' "$got" | grep -Eq "$RE" || echo "V2 '$name' -> '$got' is not a Debian version"
        if command -v dpkg >/dev/null 2>&1; then
            dpkg --compare-versions "$got" gt 0 2>/dev/null || echo "V3 dpkg rejects '$got' (from '$name')"
        fi
    done
    [ "$(java -cp "$T/out" Main "0.118.4 (sha-dac09da6)")" = "0.118.4+sha.dac09da6" ] || echo "V4 the documented example is not 0.118.4+sha.dac09da6"
}

v="$(check "$SRC")"
if [ -n "$v" ]; then echo "FAIL"; echo "$v"; fail=1; else echo "ok   versionName becomes a dpkg-valid version (0.118.4+sha.dac09da6)"; fi

sed 's|return v;|return versionName;|' "$SRC" > "$T/DebianVersion.java"
if cmp -s "$SRC" "$T/DebianVersion.java"; then echo "FAIL MUTATION DID NOT APPLY"; fail=1
elif [ -z "$(check "$T/DebianVersion.java")" ]; then echo "FAIL MUTATION SURVIVED: an identity sanitiser"; fail=1
else echo "ok   mutation proved: an identity sanitiser goes red"; fi

[ "$fail" -eq 0 ] && echo "PASS debian version" || { echo "FAILED debian version"; exit 1; }
