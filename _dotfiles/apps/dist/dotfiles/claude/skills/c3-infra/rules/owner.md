# Owner workflow, quality, publication
Each rule: statement, Why, Guard.
- Work on main only: commit and push direct to main, no PRs; merge or delete stale branches you find. Why: owner order; branches rot unmerged. Guard: hooks no-branch-create and main-only-push.
- Carry a task to the deployed, verified end; ask only when truly blocked and never what notes or tickets already answer. Why: hand-backs waste the owner's time. Guard: none.
- An ordered tool or flow brings its required steps (build, guards, push); do them unasked, but deploy only what is in scope. Why: owner standing order 2026-10-21. Guard: none.
- Do only what was asked; questions starting explain, how, what-is are read-only. Why: unrequested changes erode trust. Guard: none.
- Number every question so the owner can answer by number. Why: fast replies from a phone. Guard: none.
- Reports are short: result, files touched, verification, blockers; no recaps. Why: token saving. Guard: none.
- No cost-affecting change (machine types, paid instances) without explicit approval. Why: the fleet runs on free tiers. Guard: none.
- Fix root causes at once; never bypass the architecture, never weaken or skip tests to get green. Why: shortcuts compound. Guard: CI test suites.
- Research and read source before changing; one informed change, not trial and error. Why: loops burn budget. Guard: none.
- Verify before saying done: check rendered output; for Android verify on the owner's phone, not on green CI. Why: CI cannot see the device. Guard: backlog phone-check line (rules/backlog.md).
- Android UI is dense Compose UI. Why: owner standing order 2026-10-21. Guard: none.
- Nothing is downloaded at runtime; assets and data ship in the build. Why: offline-first apps. Guard: none.
- Never `git add -f`; never npm or npx locally; try commands without sudo first. Why: ignored files are ignored on purpose; local toolchains drift. Guard: none.
- Use cloud-infra-mcp (or gh) for GitHub, VM and Cloudflare operations; a missing capability becomes a tool there. Why: one audited control plane. Guard: none.
- Never quote the owner verbatim in commits, comments, specs or tickets; neutral third-person engineering prose. Why: publication rule, several repos are public. Guard: none.
