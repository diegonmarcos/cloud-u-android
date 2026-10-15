# Session survival rules

## If the shell dies (every Bash call exits 1 with no output)

Seen 2026-10-15. The Bash **sandbox** broke; the machine itself was fine.

Fix:
1. Run one command with `dangerouslyDisableSandbox: true` (the owner has
   authorised this for exactly this repair). It works even when the sandbox doesn't.
2. Check: `df -h / /dev/shm; free -m`.
3. Stop stuck builds **by PID**: `pgrep -f GradleDaemon`, then `kill <pid>`.
   Never use `pkill -f <pattern>`: the pattern matches the shell's own command
   line, so it kills the shell too (exit 144).
4. Free disk: `rm -rf ~/.gradle/caches ~/.gradle/daemon ~/.m2/repository ~/.cargo/registry/cache ~/.npm/_cacache`
5. Test a normal sandboxed `echo ok`. It recovered after steps 3 and 4.

Do NOT tell the owner the session is unrecoverable before trying step 1.

## Never do

- `rm -rf /tmp/tmp.*`, `rm -rf /tmp/*`, or any glob delete in `/tmp`.
  `/tmp` holds the sandbox's own files; this is what killed the shell.
  Delete only paths you created, by their full name.
- Delete `~/.claude` or `/tmp/claude-*`.
- Run more than ONE Gradle/Android build at a time, in this session or its agents.
- Keep unpushed work in `/dev/shm`. Each sandboxed agent gets its OWN private
  `/dev/shm`, and it vanishes when the agent ends. Three features were lost this way.
  Use worktrees on disk (for example `/home/user/wt/<name>`) and push a WIP branch
  after every meaningful step.

## Always

- Check `df -h /` before a build. The session's disk allowance is about 2–3 GB free.
- Brief every agent with these rules: one build at a time, disk worktrees,
  push WIP early, no glob deletes.
