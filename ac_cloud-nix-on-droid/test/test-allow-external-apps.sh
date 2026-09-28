#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #620 — allow-external-apps=true is written, not merely read             ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# RunCommandService refuses every RUN_COMMAND intent unless
# ~/.termux/termux.properties sets allow-external-apps=true, and the boot runner
# IS such an intent — so every reboot logged
#   "requires allow-external-apps=true in ~/.termux/termux.properties"
# and ran nothing.
#
# The cause was not a wrong value: NOTHING in this tree ever wrote that
# property. The app only read it, the file is created once at first setup, and
# an app update never rewrites it — so on an already-installed phone the
# property was absent forever. That is the #605 shape, and it is why #198/#436
# were inert: a fix hung off first-run setup never reaches an installed phone.
#
# This tester therefore asserts the WRITER exists, that it runs from the
# application start path (which runs on every launch and before any service in
# the process), and that the rule it applies actually ends with the property
# set. Delete the writer or its call site and every one of these goes red.
#
# Static, offline, no Android SDK: it reads source only.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
WRITER="$DIR/app/src/main/java/com/termux/cloud/CloudTermuxProperties.java"
APPLICATION="$DIR/app/src/main/java/com/termux/app/TermuxApplication.java"
SERVICE="$DIR/app/src/main/java/com/termux/app/RunCommandService.java"

fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

echo "── #620 allow-external-apps writer assertions [$(basename "$DIR")] ──"

# A1 — the gate this exists for is still the gate. If RunCommandService stops
# consulting the property, everything below is answering a dead question.
if grep -q 'checkIfAllowExternalAppsPolicyIsViolated' "$SERVICE"; then
    ok "RunCommandService still gates on the allow-external-apps policy"
else
    bad "RunCommandService no longer checks the allow-external-apps policy — re-read #620 before deleting the writer"
fi

# A2 — the writer exists at all. This is the whole defect: it did not.
if [ -f "$WRITER" ]; then
    ok "the writer exists: ${WRITER#"$DIR"/}"
else
    bad "no writer for allow-external-apps — the property is read by this app and set by nobody (#620)"
    echo "── #620 assertions: $fails failed ──"
    exit 1
fi

# A3 — it writes the one value that opens the gate, into the one file
# RunCommandService's properties are loaded from.
if grep -q 'PROP_ALLOW_EXTERNAL_APPS' "$WRITER" && grep -q 'TERMUX_PROPERTIES_PRIMARY_FILE' "$WRITER"; then
    ok "writes TermuxConstants.PROP_ALLOW_EXTERNAL_APPS into TERMUX_PROPERTIES_PRIMARY_FILE"
else
    bad "the writer does not name the property constant and the properties file — it must write ~/.termux/termux.properties"
fi
if grep -qE 'VALUE *= *"true"' "$WRITER"; then
    ok 'the value written is "true"'
else
    bad 'the writer does not write the value "true" — no other value opens the gate'
fi

# A4 — it can run on a phone that has never been set up: the .termux directory
# and the file may not exist yet.
if grep -q 'mkdirs()' "$WRITER" && grep -q 'FileOutputStream' "$WRITER"; then
    ok "creates the directory and the file when missing"
else
    bad "the writer neither creates its directory nor opens the file for writing"
fi

# A5 — idempotent, and non-destructive. Three behaviours, one per branch of the
# merge: already-true writes nothing, a present-but-other value is rewritten,
# an absent property is appended to whatever was already in the file.
if grep -q 'return null' "$WRITER"; then
    ok "already-true is detected and the file is left untouched (no duplicate line)"
else
    bad "the writer has no already-set branch — it would append the property on every launch"
fi
if grep -q 'replaced = true' "$WRITER"; then
    ok "a present-but-wrong value is rewritten in place"
else
    bad "the writer cannot repair allow-external-apps=false — it only appends"
fi
if grep -q 'append(LINE)' "$WRITER"; then
    ok "an absent property is appended, preserving the existing contents"
else
    bad "the writer does not append to the existing contents — other properties would be clobbered"
fi
if grep -q "startsWith(\"#\")" "$WRITER"; then
    ok "commented-out declarations are not mistaken for the live one"
else
    bad "the merge does not skip comment lines — '#allow-external-apps=true' would read as set"
fi

# A6 — SELF-HEALING is the point. The call must be in the application start
# path, which runs on every launch AND on every boot before any service in this
# process handles an intent. Hanging it off first-run setup is exactly the
# mistake #605 documented.
if grep -q 'CloudTermuxProperties.ensureAllowExternalApps()' "$APPLICATION"; then
    ok "TermuxApplication calls it, so it runs on every launch and every boot"
else
    bad "nothing calls ensureAllowExternalApps() from TermuxApplication — an installed phone would never get the property"
fi

# A7 — and before the file is cached. TermuxAppSharedProperties reads
# termux.properties once; writing after that init would take effect one launch
# late, which on a reboot means the boot runner is still refused.
if grep -q 'TermuxAppSharedProperties.init' "$APPLICATION"; then
    order="$(grep -nE 'CloudTermuxProperties.ensureAllowExternalApps\(\)|TermuxAppSharedProperties.init' "$APPLICATION" | head -1)"
    case "$order" in
        *ensureAllowExternalApps*) ok "the write happens before TermuxAppSharedProperties caches the file" ;;
        *) bad "TermuxAppSharedProperties.init runs BEFORE the write — the property takes effect one launch late" ;;
    esac
else
    ok "this app does not cache termux.properties in TermuxApplication — no ordering constraint"
fi

echo "── #620 assertions: $fails failed ──"
[ "$fails" -eq 0 ]
