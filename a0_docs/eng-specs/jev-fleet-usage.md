# Jev across the fleet — where a calibrated decision belongs, and the one gate that serves them all (#880)

Status: design, 2026-10-06. Owner decision: adopt. Measured against real code on that date; every
file:line below is the heuristic Jev would score, not a sketch.

## 0. What Jev is, and the one rule

Jev is TypeSafe's structured **decision** model on OpenRouter's Decisions API
(`https://openrouter.ai/api/alpha/decisions`, pinned `typesafe/jev-1.13`). Given a redacted state and
typed questions it returns calibrated probabilities:

- **noul** — yes/no with P(yes)
- **choice** — one of the caller's options with a probability per option
- **score** — a level on the caller's legend with a probability per level

It never generates text and never computes. It scores options the caller already has.

**The rule every site below obeys:** a Jev answer only *reorders, pre-selects, re-ranks or
suggests*; the caller's existing path remains the fallback and is what runs on *any* failure (no
key, offline, timeout, HTTP error, malformed answer, P below threshold, over budget). The user sees
exactly today's behaviour when Jev is absent. Gating sites (anything that can lose data, money or
access) may lower friction (confirm instead of refuse) but never auto-act, and a static deny list
is evaluated *before* Jev (the `never_auto_approve` pattern of the agent gate).

## 1. Where it already runs

| Surface | Use | Code |
|---|---|---|
| Cloud Calc | Ask routes a request to one of the app's tools; "Ask about this result" scores a result; Sound ▸ What is it? | `ac_cloud-calc/build.json::jev`, `jev/JevConfig.kt`, `JevRouter.kt:91` (`pick.p >= threshold`, else positional fallback ~:127) |
| Cloud Writer | route per function (model vs local) | `ac_cloud-writer/.../WriterRoutes.kt:79-84` |
| Image engine | OpenRouter route when Configs ▸ Image says so | `libs/ml-l-image-mlkit/.../Recognizer.kt` |
| Agent CLIs (claude / goose / hermes) | permission pre-flight (never denies), tool pre-selection, code-context routing to cloud-cgc | `cloud-u-containers/_shared/jev-gate/{jev_gate.py,jev-gate.json}` (#764/#765) |

The Android client is `libs/decisions` (`Decisions.kt`, 165 lines, never throws, pluggable `Http`,
`pick(allowed)` rejects an out-of-set choice). It holds no endpoint, model or config — each caller
does, and each caller compiles it. The token is reached three times over by copy-paste
(`ac_cloud-calc/.../JevStore.kt:139-160`, `ac_cloud-search/.../Account.kt:30`,
`ac_cloud-browser/.../SearchAddon.kt:105`) through `TextToolsClient.revealAiKey(provider)`; mail is
barred from holding the key (`ac_cloud-mail/build.json:45`, K1).

## 2. Plumbing first — two implementations, not thirty

### 2.1 Android: `libs:decisions` becomes a runtime engine

Per #870/#871 (engines-only lib APKs), `libs:decisions` splits like analytics did:

- **Engine** `libs/decisions-engine` → `Cloud-Lib-Decisions-Engine.apk`: a `CONSTELLATION_DATA`-guarded
  service (`${applicationId}.ENGINE`, CONTRACT 1) that holds the OpenRouter token *inside its own
  process* (read from the Account vault #874 through text-tools, never crossing Binder), the endpoint,
  the pinned model, the per-use declarations, a result cache keyed by sha256(state+questions) with
  TTL, the budget, the consent flags and the decisions journal.
- **Client** `libs:decisions` stays the thin contract apps compile: `decide(use, state, questions)
  → answers | null`. No credential in the interface (a K1-style contract test enforces it).
- **Declaration**, in `fleet-config.json` (fleetconfig-model, #873) rather than per-app build.json:
  `uses.<use>.{enabled, threshold, allowed, class, ttl_s, max_calls_per_hour, consent}` — the same
  shape as the server gate's `uses{}` so thresholds tune from one place.
- **Controls** the engine owns: fleet daily USD cap and per-app quota; suppression on metered /
  offline / battery-saver (battery lib, ConnectivityManager); circuit breaker after N failures;
  state redaction (no bodies, 8 000-char cap, secret-looking env masked); a per-app, per-use consent
  defaulting **off** for mail and git content; `/api/decisions` debug route returning names and
  ✓/✗ only.
- **Classes** every use is tagged with and the engine enforces: `user_facing` (may pre-select or
  re-rank), `background` (must cache, never re-ask), `gating` (may only produce a suggestion or
  turn a refusal into a confirm).

### 2.2 Hosts and flakes: one `jev-gate` package + an optional sidecar

The gate is stdlib Python (617 lines). Today each container copies the directory into its image
(`my-ai-api/src/flake.nix:70-72`, `Dockerfile:131-132`, `start.sh:79-95`; hermes
`flake.nix:24-34,66` + `compose.nix:97-100`; claude `settings.json:23,62`).

- **Package**: `pkgs.writers.writePython3Bin "jev-gate"` (or `buildPythonApplication`) from
  `_shared/jev-gate`, config at `$out/share/jev-gate/jev-gate.json` (`load_config` already honours
  `JEV_GATE_CONFIG`). Containers, the Home-Manager desktop flake and the termux flake consume the same
  derivation; the hermes plugin keeps its directory layout.
- **Generic subcommand**: `jev-gate decide --use <name>` reading `{state, questions}` on stdin,
  printing the verdict or "no opinion" — `decide()`, `log()`, `redact()`, `_prob()` all exist;
  `main()` (:603) only dispatches the three CLI modes today.
- **Sidecar** (for shell-heavy sites: Dagu DAGs, `journal-ntfy.sh`, maddy post-hoc): a small unit on
  oci-apps exposing `POST /decide/<use>` on the WireGuard mesh — one secret (Dagu's `secrets.yaml`
  carries no OpenRouter key today), one cache, one spend cap, one journal shipped to OpenObserve.
  Desktop/termux keep the in-process CLI with their own key (already in `build-flakes_desktop.json`
  / `build-flakes_termux.json`).
- **Budget is missing today**: cost is logged, never enforced. Add `daily_cost_cap` and per-use
  `max_calls_per_hour`; over budget behaves like `no_key`.
- **Tests**: extend `_shared/test-jev-gate.py` (mock Decisions + mock cgc MCP already there) with the
  generic subcommand and a src/dist parity check.

## 3. Android inventory (most valuable first)

Format — site: existing decision · question · state · fallback · class.

1. **Updater downgrade gate** `libs/updater/.../Fleet.kt:634` `DowngradePolicy.allowDowngrade` refuses
   unconditionally · noul "safe rollback?" · app, both version codes, advisory items, channel · refuse
   · **gating**: may turn the refusal into a confirm dialog, never auto-allow.
2. **Updater advisory notify** `Advisory.kt:459` fixed `RENOTIFY_MS` · score urgency {low, normal,
   interrupt} · item, text, last shown, severity · the rate limit · background + user-facing (may
   suppress further or escalate inside the limit).
3. **Notification centre** `aa_cloud-superapp/.../AlertStore.kt:109` mute by app/severity;
   `NotifyGroups.kt:88` `rank()` · noul "worth interrupting now?"; choice of group for an unknown
   app · app, severity, title, mutes, time · mutes + fixed rank · user-facing.
4. **Mail triage** `ac_cloud-mail/core/data/.../MailRepository.kt:4105` `rankedMailboxPick` fixed role
   ranks; spam is user-driven (`:3452`/`:3458`) · noul "spam/phishing?"; choice of folder ·
   headers, sender history, mailbox roles · role rank, no auto-spam · user-facing, **suggest-only**;
   must go through the engine (mail may not hold the key).
5. **Git-sync conflict** `libs/git-sync/.../GitEngine.kt:370` `resolve(path, side)` user-chosen ·
   choice {ours, theirs, manual} shown as a recommendation · path, both blobs truncated, messages,
   timestamps · no preselection · **gating** (data loss): never auto-apply.
6. **Store source & shelf** `libs/appstore/.../SourceResolver.kt:180`, `StoreCloudFragment.kt:145`
   `shelfOf`, `StoreAuto.kt:420` `rank(kind)` · choice of shelf/source; score "recommended" ·
   today's resolver and rank · user-facing.
7. **Phone app folders** `libs/appstore/.../PhoneAppClassifier.kt:79` keyword → metadata → sink;
   `PhoneSmartFolders.kt:81,211` · choice of folder *only when the result would be the sink* ·
   package, label, categories, permissions · sink folder · background, cached.
8. **Keyboard AI route** `libs/keyboard/.../AiRouter.kt:240,386` provider/model/style from prefs;
   pricing at `:186` · choice of style/model per request (cost vs quality); noul "send to AI or
   keep local" · pref route · user-facing. Autocorrect (`Suggest.kt:161`) stays local: hot path.
9. **Writer routing** `WriterRoutes.kt:79-84` · choice of route per function · `default_route` ·
   user-facing; already the closest site.
10. **Search scope** `libs/search/.../SearchSheet.kt:115` multi-scope · choice over {web, mail, drive,
    apps, AI}; score relevance · listed order · user-facing.
11. **Firewall rule for a new package** `libs/firewall/.../FirewallFlowBridge.kt:54` unknown uid →
    direct · choice {block, direct, vpn} as a *suggested rule*, computed offline, never per flow ·
    default rule · **gating**, suggest-only.
12. **DNS fallback order** `DnsFragment.kt:130,73` · choice over resolvers from failure history ·
    configured order · background; circular when DNS is down, so the fallback always wins offline.
13. **Privileged grants re-request** `aa_cloud-superapp/.../PrivilegedGrants.kt:174` `staleGrants` ·
    noul "re-request now?" · background.
14. Low value / keep heuristic: sysdns quiet mode (`:186`), Me files rank (`FilesFragment.kt:130`),
    datamanager `rankByLastUse` (`:154`), MarketsBadge stale threshold (`:123`).

**Not candidates, by design**: APK cache keep/prune (`ApkCache.kt:271` is proof-based), analytics
consent (`Analytics.kt:94`, a human decision — and the model for Jev's own consent), camera location
fix (deterministic), per-flow firewall verdicts (latency, offline).

Not yet swept (second pass before building): drive, nav, wallet, Account fleet-setup grants, store
app, news ranking, watchdog internals, health, feed, cal, rclone, mounts, net-wg profile choice,
fleet alerts (`ConstellationWorker.kt:140`).

## 4. Server inventory (most valuable first)

| # | Decision today | Question | State | Fallback | Class |
|---|---|---|---|---|---|
| S1 | maddy apply-rules: unmatched mail → "91 📬 Others (fallback)" (`mail-sieve-subset-post-hoc.sh:422,541,649-661`) | choice of category folder for fallback rows only; noul "urgent / needs reply" | cached headers (From, Subject, List-Id), the folder list | stays in fallback | advisory (copy-only, idempotent `$distributed`); budget: new fallback rows only (2-min loop :160) |
| S2 | mail ingest stall pages at P5 on `AGE >= STALE_HOURS` (`dags/health_mail-ingest.yaml:44-60`) | noul "abnormal for weekday/hour pattern" | age + 21-day map | fixed page | observe → advisory (lower priority, never suppress) |
| S3 | alert priority hard-coded high (`cloud-infra-mcp/.../poller.ts:166-195`, `notify.ts:63-94`) | score ntfy priority | rule, value, history, last fleet snapshot | hard-coded | advisory (re-rank only) |
| S4 | journal → ntfy static `route()` + 300 s limit (`vm-pilot/.../journal-ntfy.sh:57-91`) | noul "worth a push"; choice of topic | redacted line, unit, severity | static route | observe-only, sampled, local daily cap |
| S5 | container unhealthy → P4 notify (`dags/health_docker-containers.yaml:20-32`) | choice {none, container.restart, service.restart, vm.reset, page} | containers JSON, restart history, log tail | notify | advisory in the ntfy body; auto-execute only `container.restart` behind a static allowlist; never vm.reset |
| S6 | remediation verbs (`operations.ts:214-218,720,771-809`) | noul "reversible / proportionate" pre-flight | tool, args, snapshot | execute | gating in the defer-on-doubt style; vm.reset in a static deny list ahead of Jev |
| S7 | load-shedder PSI shed (`load-shedder.nix:11-12,189-197`) | noul "transient (build/backup)?" | PSI series, top containers | shed | observe-only — never waits on the network |
| S8 | deploy pull-up RC≠0 → P4 (`dags/ops_deploy-pull-up.yaml`) | choice {retry-once, rollback-tag, page}; noul transient vs real | rc, stderr tail, tag | alert | advisory |
| S9 | my-ai router: model tier, Ponytail profile (`server.mjs:252-268,84-85,213-240`; bots `route.mjs:64,108-113`) | choice of tier when no explicit model; choice of compression profile | last message (redacted), history length, tokens | DEFAULT_MODEL / PONYTAIL_DEFAULT | advisory; explicit headers win; needs the cost cap |
| S10 | daily security report always P2 (`dags/security_auth-events.yaml:20-30`) | noul "anomalous vs baseline" → bump | counts | P2 | advisory |
| S11 | backup-check / capacity / docker-prune / octocode-db-pull | noul "needs attention"; for octocode noul "changed enough to re-index" | report text, push timestamps | fixed | observe → advisory; prune and backups stay deterministic |
| S12 | GHA failures (`workflows.ts` errors / rerun_job) | noul "flake vs real" → one audited rerun | failed-step tail | report | advisory |
| S13 | goose `on_unsafe=block`, hermes `serves_task` | flip from log to block once `decisions.jsonl` shows precision | journal | log | gating, data-driven |

Not candidates: mail-puller reconciliation (idempotent), agents-tmp-reaper / disk-janitor
(deletion stays rule-based — a past incident is documented in its entrypoint), load-shedder
execution. Caddy edge fallback and finops anomalies: no branching code today (finops: observe-only
noul on `obs_finops_*_costs_history`).

## 5. Desktop and termux flakes

| # | Hook | Use | Risk |
|---|---|---|---|
| D1 | Desktop Claude Code `cloud-infra-desktop/0_apps/src/claude/settings.json` has no `hooks` block | add the container's PermissionRequest + UserPromptSubmit registrations pointing at the nix `jev-gate` binary (settings.base.json in the HM flake) | gating-by-allow only, never deny |
| D2 | git hooks `cloud-infra/0_git/src/hooks/{pre-commit,pre-push}` + `0_apps/src/githooks/{leak-scan,history-gate}` (same set in cloud-infra-desktop) | new commit-msg guard: noul "message describes the staged diff"; noul "diff looks like a secret the regexes missed" — after `redact`, never for vault paths | advisory (stderr warning); the static leak-scan stays the boundary |
| D3 | post-merge / post-checkout | skip | — |
| D4 | goose / hermes desktop configs, zsh hooks, termux boot and keep-alive, session-sync / rollover | the gate's `client_configs` already reads `~/.config/goose/config.yaml` and `~/.hermes/config.yaml`; install the plugin into `~/.agents/plugins` and hermes' plugins dir from the package | unverified: `ba_flakes_desktop` (the HM flake) is not in this clone |

## 6. Rollout — waves and tickets

| Wave | Scope | Ticket |
|---|---|---|
| 0 | Plumbing: `decisions-engine` (Android) with budget, consent, cache, classes, K1 contract test; `jev-gate` nix package + `decide` subcommand + budget; sidecar on oci-apps; journals to OpenObserve | #881 |
| 1 | Advisory, high volume, easy undo: S1 mail fallback folder, S3/S5 alert priority + remediation suggestion, S2, S9 model/compression with cap; Android 2 (advisory urgency), 7 (folders, cached), 9 (writer), 6 (store shelf) | #882 |
| 2 | User-facing suggestions: Android 3 (notify), 4 (mail triage, suggest-only), 10 (search scope), 8 (keyboard route); servers S8, S10, S11, S12; desktop D1, D2 | #883 |
| 3 | Gating, only after wave 1–2 journals show precision: Android 1 (downgrade confirm), 5 (conflict recommendation), 11 (firewall rule suggestion); servers S6 pre-flight, S13 flips; S5 auto `container.restart` | #884 |

Each wave ships behind the per-use `enabled` flag, observe-only first (answers journaled, nothing
changes), then advisory, then — for the gating class only — the confirm-not-refuse behaviour. A
guard (Android: engine-contract + a `decisions-use` guard that every `decide(use…)` call names a
declared use with a class and a fallback test; hosts: `test-jev-gate.py`) fails the build when a new
use appears without a declaration.
