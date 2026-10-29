# CI and builds
- Builds run only in GHA/CI or declared runners, never on termux, VMs or locally; no local `gradle`/`gradlew`. Why: wrong arch, disk and time limits. Guard: hook no-local-gradle.
- After source edits run `build.sh build` when present; never start servers. Why: servers outlive the session. Guard: none.
- Drive CI with cloud-infra-mcp devops_workflows_gha_* (trigger, run, run_logs) or the gh CLI; read the workflow definition before dispatching inputs. Why: undeclared inputs are rejected or silently skipped. Guard: none.
- Never weaken or skip a test to get green; an emulator test that SKIPs without an emulator needs a CI job that runs it. Why: a skipped test proves nothing. Guard: CI jobs.
- Never run `nix eval` on VMs. Why: memory spikes kill them. Guard: none.
- Android: one Gradle/Android build at a time; check `df -h /` first. Why: disk allowance is 2-3 GB. Guard: none.
