#!/bin/sh
# Runs ac_cloud-myterminal/test/test-terminal-session.sh from this app's tester directory: this
# terminal SERVES the zero-setup session MyTerminal opens (CloudSessionService, ICloudSession.aidl,
# SessionGate), so a change here must pass the same check as the client — the contract identical
# in the three modules, the service exported only behind the signature permission, every call
# gated, and the gate refusing non-fleet callers.
exec sh "$(cd "$(dirname "$0")/../.." && pwd)/ac_cloud-myterminal/test/test-terminal-session.sh"
