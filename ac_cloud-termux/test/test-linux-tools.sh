#!/bin/sh
# Runs ab_cloud-terminal-store/test-linux-tools.sh from this app's tester directory, because the test
# engine runs <app>/test/*.sh and both terminals must pass the same shared check
# (store.json::linux_tools and ::startup are one declaration for the two apps).
exec sh "$(cd "$(dirname "$0")/../.." && pwd)/ab_cloud-terminal-store/test-linux-tools.sh"
