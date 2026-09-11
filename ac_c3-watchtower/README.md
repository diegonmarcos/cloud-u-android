# ac_c3-watchtower — C3 WatchTower

**Analytics over the cloud's delivery pipeline.**

Watchdog watches what is running. Morpheus decides what runs. WatchTower counts
what ran.

One screen: over the last 30 workflow runs per repo — how many ran, what share
of the *finished* ones went green, how many failed, how long ago the last green
was, and the per-workflow breakdown worst-first.

| | |
|---|---|
| package | `com.diegonmarcos.watchtower` |
| GHCR image | `ghcr.io/diegonmarcos/c3-watchtower` |
| assets | `C3-WatchTower.apk` (arm64) · `C3-WatchTower-x86_64.apk` |
| modelled on | `ac_c3-morpheus` (one module, no shared lib, data-driven) |
| reached from | SuperApp ▸ Cloud ▸ C3 — tab 4, after Morpheus |

## What it reads

`https://api.github.com/repos/<owner>/<repo>/actions/runs`, **anonymously**.

One request per repo, 30 runs per page, cached 15 minutes — the same TTL
`aa_cloud-superapp`'s `GitHubFeed` uses, so a phone that opens this app and the
SuperApp's Analytics card inside the same window spends the quota once.

The repo list, the window and the TTL are **data**, in
`build.json::analytics`. Adding a repo is an edit to that file; nothing is a
Kotlin constant.

## Why it does not read `/pub/analytics/*`

That path is the fleet's one unauthenticated analytics surface and it was the
first candidate. **It cannot serve this.**

`c3-public-api`'s `routes/analytics.ts` mounts `@fastify/http-proxy` per backend
with `rewritePrefix: ""` onto an upstream that already includes a fixed ingest
path:

| backend | upstream path | methods |
|---|---|---|
| matomo | `/matomo.php` | GET, POST |
| umami | `/api/send` | POST |
| openobserve | `/api/default/default/_json` | POST |

Every mount is pinned to **one write endpoint**. There is no query route behind
any of them: the path accepts telemetry and answers no questions.

Measured 2026-09-11, all three additionally answer `500 ECONNREFUSED` from the
public edge (umami `10.0.0.4:3006`, matomo `10.0.0.6:8080`, openobserve
`10.0.0.6:5080`) — the backends are down, so it serves nothing in *either*
direction today.

`c3-infra-api` is not an alternative either: its `build.json` declares
`/c3-infra-api/public/events/*` public, but `events.ts` mounts the route at
`/events`, so the declared public path matches nothing and the live endpoint
302s to Authelia.

The anonymous GitHub Actions API is the one analytics-grade source reachable
with **no credential** that actually answers — and `aa_cloud-superapp` has read
it in production for months, so the reachability is proven rather than hoped
for. An APK that embeds no token is an APK whose publication leaks nothing.

## The card and the app are the same subject

C3 ▸ Observability carries an **Analytics** card (`kind: feed`,
`source: github_run_stats`, `anchor: analytics`) reading the same endpoint
through the same client and cache. The card is the glance — one aggregate line
per repo, three repos. This app is the detail — five repos plus the
per-workflow breakdown the card has no room for. They cannot disagree about a
number without one of them simply being older.

The card's summary row targets `extapp:c3-watchtower`, so tapping it hands off
here through the SuperApp's single dispatch chokepoint.

## The denominator

**Finished runs, not all runs.** A run still in progress carries an empty
`conclusion`; counting it as not-green would make the rate sag every time the
fleet is mid-build, which is precisely when this app gets opened. Queued and
running are neither green nor failed — they are not yet anything. With nothing
finished the screen says so rather than printing `0%`.

`cancelled` is not a failure either: the ship workflows run under
`cancel-in-progress`, so a rapid series of pushes cancels older runs by design.

## Not in v1

- **Traffic analytics** (pageviews, visitors, per-app usage) — that is Matomo
  and Umami, both down, and neither has a read route through the public edge.
  When that changes it is a second `source` in `build.json::analytics` plus a
  client, never a second app.
- **Container/VM resource trends** — behind `c3-infra-api`'s `/metrics`, which
  is Bearer-gated. That needs the Authelia token `libs:ops` stores, a
  dependency this app does not have.

## Build

```sh
./build.sh build        # debug APK → dist/C3-WatchTower.apk
./build.sh release      # signed release APK
./build.sh gh-release   # attach to the rolling `latest` release
```

All toolchain comes from `flake.nix`. CI is
`1_cicd/src/cicd/ship-c3-watchtower.yml` → `.github/workflows/` (generated —
edit the source, then `./build.sh workflow`).

**A rebuild is triggered by** `ac_c3-watchtower/**` and that workflow source,
and nothing else. `build.json::modules` declares one module with no `dir`, so
there is no shared-library tree this APK consumes and none to watch.

## Tests

`test/test-watchtower-identity.sh` — own source only (#254), run by the ship
workflow's test-engine step.

Not covered: nothing *executes* the app. There is no JVM unit test and no
instrumentation, so the arithmetic in `Stats` is asserted as source text and
never run. The cheapest next test is a JVM unit test over that reduction with a
canned `workflow_runs` payload — no device, no network.
