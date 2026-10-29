#!/bin/bash
. "$(dirname "$0")/_lib.sh"
if printf '%s' "$CMD" | grep -Eq '(^|[;&|(]\s*|(^|\s)(sudo|exec|nohup|time)\s+)(\S*/)?gradlew?(\s|$)'; then
  block no-local-gradle 'no local Gradle runs: Android builds happen in CI only (trigger the workflow with devops_workflows_gha_trigger or gh workflow run).'
fi
exit 0
