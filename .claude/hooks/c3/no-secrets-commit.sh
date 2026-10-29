#!/bin/bash
. "$(dirname "$0")/_lib.sh"
if printf '%s' "$CMD" | grep -Eq '(^|[;&|(]\s*)git(\s+-C\s+\S+)?\s+commit(\s|$)'; then
  PAT='(-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|xox[abprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9_-]{32,}|(password|passwd|secret|api[_-]?key|token)["'"'"']?\s*[:=]\s*["'"'"'][^"'"'"'$ ]{12,}["'"'"'])'
  # a commit message that embeds a secret
  if printf '%s' "$CMD" | grep -Eqi "$PAT"; then block no-secrets-commit 'plaintext secret pattern in the commit command. Remove it (use sops / env vars).'; fi
  # staged content: gitleaks when installed, regex otherwise
  D="$(printf '%s' "$CMD" | grep -Eo 'git\s+-C\s+\S+' | head -1 | awk '{print $3}')"
  [ -n "$D" ] || D=.
  if command -v gitleaks >/dev/null 2>&1; then
    CFG=""; [ -f "$D/.gitleaks.toml" ] && CFG="--config $D/.gitleaks.toml"
    gitleaks protect --staged --no-banner $CFG -s "$D" >/dev/null 2>&1 || {
      [ $? -eq 1 ] && block no-secrets-commit 'gitleaks found a secret in the staged changes. Unstage it and move it to sops.'; }
  else
    # #1291 allowlist: dated task folder names (TASK-20260323-04_..., TASK-net-20260508-01_..., task-2026-02-15-...)
    # contain "sk-" inside the word TASK; only that exact shape is neutralised, real sk-... keys stay blocked
    if git -C "$D" diff --cached -U0 2>/dev/null | grep -E '^\+[^+]' | sed -E 's#([Tt][Aa][Ss][Kk])-(([a-z]+-)?[0-9]{8}-[0-9]{2}_|[0-9]{4}-[0-9]{2}-[0-9]{2}-)#\1_\2#g' | grep -Eqi "$PAT"; then
      block no-secrets-commit 'plaintext secret pattern in the staged changes. Unstage it and move it to sops.'
    fi
  fi
fi
exit 0
