#!/usr/bin/env bash
# Tester: the updater owns EXACTLY ONE install identity, declared in ONE place,
# and every path that can emit a badge, a prompt or a notification reads that
# declaration first — so a work-profile, parallel-clone or Secure-Folder copy
# that the fleet never installed stays silent (#453).
#
# THE FAILURE THIS EXISTS FOR (#453).
#
# A "parallel" copy of cloud-sa that Diego never installed was showing an
# update badge and demanding updates. Android can host the same APK more than
# once on one device — the main install, a work/second profile, an app-cloning
# feature ("parallel apps", "dual messenger"), or Samsung Secure Folder — and
# the updater treated every reachable copy as *the* install. There was no model
# of the distinction, so a clone the updater had no business managing produced
# update prompts and badges exactly like the real install.
#
# The fix is a DECLARATION, not a suppression list. InstallIdentity (in
# libs:updater) is the single source of truth: it names the install kinds, says
# which one the updater MAY act on ([MANAGED] = exactly the primary install),
# and maps the device's current Android user to a kind. Every emitting path —
# the self-update scheduler and worker, and the constellation (fleet) scheduler
# and worker — calls InstallIdentity.isManaged() at its head and returns
# silently when the current install is not the managed one. Nothing suppresses;
# nothing is a second copy of the answer.
#
# #515 removed the fifth emitting path, App.detectVersionBump's "Updated to vc:"
# push. It was a SECOND announcement of an install PackageInstallerReceiver had
# already recorded, and it fired from Application.onCreate — the one place with
# no install session behind it, which is exactly why it needed a gate of its own.
# T8 below now asserts the path is gone rather than that it is gated: a launch-
# time push is un-gated by construction the moment someone writes one.
#
# Mutation-proofing shape: this tester asserts BOTH the declaration (T1/T2) and
# the reader (T3+T). Remove the gate from any emitting path and its assertion
# goes RED. Widen [MANAGED] to trust a clone and T2 goes RED.
#
# Nothing here touches a device. Every assertion is over declared data or over
# engine source, because that is all this container can honestly read.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # → cloud-u-android
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq >/dev/null || { echo "ERROR: jq required" >&2; exit 2; }

# The engine and storefront directories, read from build.json::modules — the
# same map settings.gradle resolves. A hardcoded path here would be a second
# statement of where the shared modules live, which is the class of bug this
# whole tester is about.
UPDATER_DIR="$(jq -r '.modules["libs:updater"].dir // empty' "$BJ")"
[ -n "$UPDATER_DIR" ] || { echo "ERROR: build.json::modules[libs:updater].dir is not declared" >&2; exit 2; }
APPSTORE_DIR="$(jq -r '.modules["libs:appstore"].dir // empty' "$BJ")"
[ -n "$APPSTORE_DIR" ] || { echo "ERROR: build.json::modules[libs:appstore].dir is not declared" >&2; exit 2; }
UP="$APP/$UPDATER_DIR"
AS="$APP/$APPSTORE_DIR"
IDENTITY_KT="$UP/src/main/java/com/diegonmarcos/superapp/updater/InstallIdentity.kt"
UPDATER_KT="$UP/src/main/java/com/diegonmarcos/superapp/updater/Updater.kt"
UPDATE_WORKER_KT="$UP/src/main/java/com/diegonmarcos/superapp/updater/UpdateWorker.kt"
CONSTELLATION_WORKER_KT="$AS/src/main/java/com/diegonmarcos/superapp/appstore/ConstellationWorker.kt"
APP_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt"
for f in "$IDENTITY_KT" "$UPDATER_KT" "$UPDATE_WORKER_KT" "$CONSTELLATION_WORKER_KT" "$APP_KT"; do
    [ -f "$f" ] || { echo "ERROR: engine file not found at $f" >&2; exit 2; }
done

echo "== The declaration: one source of truth for which install the updater owns =="

# T1 — the declaration exists and states its answer imperatively as a set.
if grep -E 'val MANAGED: Set<InstallKind> = setOf\(InstallKind\.MAIN\)' "$IDENTITY_KT" >/dev/null; then
    ok "T1 InstallIdentity declares MANAGED = exactly the primary install"
else
    bad "T1 InstallIdentity does not declare MANAGED = setOf(InstallKind.MAIN) — without the declared answer there is no model to silence a clone against"
fi

# T2 — MANAGED must not trust a clone. This is the mutation that reintroduces
# the bug: adding WORK_PROFILE or CLONE (or widening to ALL kinds) makes the
# updater act on copies it never installed. The declaration's other kinds must
# stay outside the managed set, so the line that assigns MANAGED must name MAIN
# and MUST NOT also name a clone kind. Read the ACTUAL assignment line rather
# than re-grepping a fixed string: a widened set would otherwise make the
# fixed-string grep below miss and the test pass by finding nothing.
MANAGED_LINE="$(grep -E 'val MANAGED.*setOf' "$IDENTITY_KT" | head -n 1)"
if [ -z "$MANAGED_LINE" ]; then
    bad "T2 cannot find the MANAGED assignment line to judge it"
elif printf '%s' "$MANAGED_LINE" | grep -qE 'WORK_PROFILE|CLONE'; then
    bad "T2 MANAGED trusts a clone — it must contain MAIN and nothing else (found: $MANAGED_LINE)"
elif printf '%s' "$MANAGED_LINE" | grep -q 'InstallKind.MAIN'; then
    ok "T2 MANAGED contains the primary install and no work-profile/clone kind"
else
    bad "T2 MANAGED does not name the primary install at all (found: $MANAGED_LINE)"
fi

echo "== The reader: every emitting path consults the declaration before acting =="

# T3 — the manual/periodic self-update scheduler must not arm a clone. A clone
# that was armed before this build (or re-armed by a toggle) still reaches the
# worker gate (T4); this one stops the arm from being created in the first place.
if grep -E 'InstallIdentity\.isManaged\(' "$UPDATER_KT" >/dev/null; then
    ok "T3 Updater.start consults InstallIdentity.isManaged before scheduling the self-update"
else
    bad "T3 Updater.start does NOT check InstallIdentity.isManaged — a clone would be armed to self-update and draw the update prompt/badge"
fi

# T4 — deepest backstop: whatever scheduled the self worker, a non-managed
# install must act on nothing.
if grep -E 'InstallIdentity\.isManaged\(applicationContext\)' "$UPDATE_WORKER_KT" >/dev/null; then
    ok "T4 UpdateWorker.doWork consults InstallIdentity.isManaged and stays silent on a clone"
else
    bad "T4 UpdateWorker.doWork does NOT consult InstallIdentity.isManaged — a clone that was armed could still check, download and prompt"
fi

# T5 — the fleet scheduler must not arm a clone (this worker posts the
# "N constellation update(s)" notification and drives the badge).
if grep -E 'InstallIdentity\.isManaged\(' "$CONSTELLATION_WORKER_KT" >/dev/null && \
   awk '/fun start\(context: Context\)/,/return$/' "$CONSTELLATION_WORKER_KT" | grep -qE 'InstallIdentity\.isManaged\('; then
    ok "T5 ConstellationWorker.start consults InstallIdentity.isManaged before scheduling the fleet check"
else
    bad "T5 ConstellationWorker.start does NOT consult InstallIdentity.isManaged in its body — a clone would be armed to post update notifications"
fi

# T6 — the fleet one-shot check must also gate.
if grep -E 'InstallIdentity\.isManaged\(' "$CONSTELLATION_WORKER_KT" >/dev/null && \
   awk '/fun checkNow\(context: Context\)/,/^        }$/' "$CONSTELLATION_WORKER_KT" | grep -qE 'InstallIdentity\.isManaged\('; then
    ok "T6 ConstellationWorker.checkNow consults InstallIdentity.isManaged before enqueueing a fleet check"
else
    bad "T6 ConstellationWorker.checkNow does NOT consult InstallIdentity.isManaged — a clone could still be asked to run the notification path"
fi

# T7 — deepest backstop for the fleet path: the worker that posts notifications
# must gate even if it was scheduled before this build.
if grep -E 'InstallIdentity\.isManaged\(applicationContext\)' "$CONSTELLATION_WORKER_KT" >/dev/null; then
    ok "T7 ConstellationWorker.doWork consults InstallIdentity.isManaged and stays silent on a clone"
else
    bad "T7 ConstellationWorker.doWork does NOT consult InstallIdentity.isManaged — a clone worker could still post update notifications"
fi

# T8 — Application.onCreate announces NOTHING into the in-app feed (#515).
#
# This used to assert that App.detectVersionBump called isManaged() first. The
# function is gone: it announced an install the PackageInstaller callback had
# already recorded, under the same source, so every update left two entries.
# What replaces the assertion is stronger, not weaker. Every OTHER emitting path
# runs behind an install session or a worker that gates at its head (T4-T7); a
# push written into Application.onCreate has neither, so it reaches a clone on
# the clone's first launch no matter what. The invariant is therefore that the
# launch path holds no producer at all, and re-adding one is RED here whether
# or not whoever added it remembered the gate.
if grep -nE 'NotificationStore\.push' "$APP_KT"; then
    bad "T8 App.kt pushes into NotificationStore from the Application lifecycle — there is no install session behind onCreate, so a work-profile or parallel clone announces it on its own first launch (#453), and PackageInstallerReceiver.surface() has already recorded that install anyway (#515)"
else
    ok "T8 App.kt contains no NotificationStore producer — the install feed is written only from the install callback, which cannot run in a copy the updater never installed"
fi

echo
echo
echo "== The reader: the MAIN verdict keys on the ANDROID USER id, never on the raw uid =="

# T9 — current() must classify from the ANDROID USER id, never from the raw
# process uid. Process.myUid() returns uid = (userId * 100000) + appId, so it
# is a 10000+ value for a normal primary-user install and is NEVER 0. A reader
# that compared the raw uid to 0 ('val user = Process.myUid(); if (user == 0)')
# would classify the REAL primary install as CLONE — the updater would then go
# silent even on the one install it is declared to act on. The user id must be
# recovered from the uid first; the division below is exactly Android's
# (hidden) UserHandle.getUserId — public, minSdk-safe arithmetic.
if grep -Fq 'Process.myUid() / PER_USER_RANGE' "$IDENTITY_KT" && \
   grep -Fq 'PER_USER_RANGE = 100000' "$IDENTITY_KT"; then
    ok "T9 reader recovers the ANDROID USER id by dividing Process.myUid() by PER_USER_RANGE (100000) instead of treating the raw uid as the user"
else
    bad "T9 reader does NOT recover the user id from the uid (no 'Process.myUid() / PER_USER_RANGE' with PER_USER_RANGE = 100000) — it must divide the per-user range off the uid, not compare Process.myUid() to 0 (which is never true for an installed app)"
fi

# T10 — the MAIN verdict keys on the primary user id (0) and the mapping is
# one pure classify() function with no Android call in it, so the decision is
# locked by this tester without invoking the platform.
if grep -Fq 'fun classify(userId: Int' "$IDENTITY_KT" && \
   grep -Fq 'PRIMARY_USER_ID' "$IDENTITY_KT" && \
   grep -Fq 'return classify(' "$IDENTITY_KT"; then
    ok "T10 the decision is a pure classify() mapping keyed on PRIMARY_USER_ID (the primary user, id 0), and current() delegates to it"
else
    bad "T10 reader lacks the classify() mapping (PRIMARY_USER_ID => MAIN) or current() does not delegate to it — the MAIN verdict must be keyed on the primary USER id, not a raw-uid comparison"
fi

echo "install-identity-managed: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
