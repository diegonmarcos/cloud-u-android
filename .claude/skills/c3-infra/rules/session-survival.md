# Session survival
- If every Bash call exits 1 with no output the sandbox broke, not the machine: run one command with `dangerouslyDisableSandbox: true` (authorised for exactly this), then `df -h / /dev/shm; free -m`. Why: seen 2026-10-15. Guard: none.
- Stop stuck builds by PID (`pgrep -f` is banned, so list processes with ps or the ssh_ps tool, then `kill <pid>`). Why: a pattern matches the shell's own command line (exit 144). Guard: hook no-pkill-pgrep-f.
- Free disk with `rm -rf ~/.gradle/caches ~/.gradle/daemon ~/.m2/repository ~/.cargo/registry/cache ~/.npm/_cacache`, then test `echo ok`. Why: it recovered the shell. Guard: none.
- Never `rm -rf /tmp/*`, `/tmp/tmp.*` or any glob in /tmp or /dev/shm; delete only paths you created, by full name. Why: /tmp holds the sandbox's own files. Guard: hook no-glob-rm-tmp.
- Never delete ~/.claude or /tmp/claude-*. Why: session state. Guard: none.
- Keep unpushed work on disk worktrees (for example /home/user/wt/<name>), never /dev/shm; push WIP to main after each step. Why: each agent has a private /dev/shm that vanishes (three features lost). Guard: none.
- Do not tell the owner the session is unrecoverable before trying the sandbox override. Why: it was always fixable. Guard: none.
