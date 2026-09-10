#!/usr/bin/env bash
# Tester: run the firestack build's own guard as part of THIS app's suite.
#
# The failure this exists for, 2026-09-09 → 2026-09-10: task #202 stopped the
# firestack aar build resolving dependencies from the network, and shipped
# ab_cloud-libs-shared/libs/firewall/build-firestack.test.sh to hold that
# invariant in place. That tester was correct, and it never ran. The CI engine
# discovers shell testers as <app-dir>/test/*.sh, and the firestack tester lives
# beside the engine it guards, two directories outside any app — so nothing
# invoked it, on any workflow, ever.
#
# The cost of that gap was the whole point of #202 reversed: the offline seeding
# it introduced was incomplete, `gomobile bind` could no longer resolve the
# throwaway module it synthesises per ABI, and every Cloud SuperApp publish from
# 2026-09-09 17:14 onward failed in the native library step. The owner's phone
# sat on an APK from 16:35 for a day and a half while dozens of merged fixes
# went nowhere. A guard nobody runs is not a guard; this file is the two lines
# that make it one.
#
# It delegates rather than duplicating: the invariants belong next to the engine
# they constrain, and lib-apks builds the same aar from the same script. Copying
# the assertions here would give the two callers two different definitions of
# "the build resolves nothing", which is the drift #202 was already fighting.
set -uo pipefail

# Repo root: this file is <root>/aa_cloud-superapp/test/, so up two.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="$ROOT/ab_cloud-libs-shared/libs/firewall/build-firestack.test.sh"

# A missing guard is a FAILURE, not a skip. "The tester moved" and "the tester
# passes" must never produce the same green tick — that is the exact shape of
# the hole this file was written to close.
if [ ! -f "$GUARD" ]; then
    echo "FAIL: firestack build guard not found at ${GUARD#"$ROOT"/}"
    echo "      If it moved, update this delegation; do not let it go unrun again."
    exit 1
fi

bash "$GUARD"
rc=$?

if [ "$rc" -eq 0 ]; then
    echo "ok     firestack build guard passes (delegated to ${GUARD#"$ROOT"/})"
else
    echo "FAIL: firestack build guard failed — see its output above."
    echo "      The aar this app links is built by that engine; a red guard there"
    echo "      is a SuperApp that either will not build or will publish nothing."
fi
exit "$rc"
