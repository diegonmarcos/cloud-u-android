# patches/ — the owned edits, applied live at materialize time

Cloud Office is built from source. The source is **not** in this repository and
must never be: LibreOffice core with `external/` present is in the multi-gigabyte
range, this repo has a hard rule that no file may approach 100 MB, it has already
had to repair a 90 MB repository, and its transcripts are sharded specifically to
stay under GitHub's blob limit. Committing the upstream tree outright would wreck
it.

So the upstream is **pinned and fetched**, and every edit we own lives here as a
numbered patch applied against that pin.

## Why this differs from the fleet's other forks — read before "fixing" it

The brief for this work said to follow `ac_cloud-keyboard`. That is not what
`ac_cloud-keyboard` is: it holds first-party source and has no `patches/` at all.
The real precedent is `ac_cloud-dialer`, `ac_cloud-matrix`, `ac_cloud-vault` and
`ac_cloud-media-center`.

Those four **used** to work exactly the way this directory does — a pinned
upstream cloned at `materialize-fork` time with the series applied by `git am`.
On 2026-08-19 they stopped: the patched source is now vendored directly in
`<app>/upstream/`, and their `patches/` are historical documentation that nothing
re-applies. Their own READMEs say so in as many words.

**We cannot follow them there, and the reason is size, not taste.** Fossify Phone
vendors comfortably. `collaboraonline/online` plus `libreoffice/core` plus
`external/` does not, by three orders of magnitude. So this directory is the
*earlier* fleet model, kept alive deliberately for the one app that cannot use the
later one. It is the same shape the brief described — numbered patches against an
exactly pinned revision — which is why the two agree even though the exemplar it
named does not do this.

Anyone consolidating fork tooling across the fleet: this app is the exception, and
`build.json::upstream.*.patch_mode` says `apply` rather than `vendored` so the
difference is data an engine can read rather than a fact only this file knows.

## What is pinned, and what is patched

Two upstreams, and they are not the same kind of thing:

| | repo | ref | pinned by | patched |
|---|---|---|---|---|
| `online` | `gerrit.collaboraoffice.com/online` | `distro/collabora/co-26.04-mobile` | commit sha | **yes** — everything in this directory |
| `core` | `gerrit.collaboraoffice.com/core` | `distro/collabora/co-26.04` | commit sha | **no** |

Gerrit, not the GitHub mirror: the mirror carries the Android tree only on the
25.04 line, and pinning it would have moved the owner back a whole release from
the 26.04.3.1 that shipped while this was a mirror. That is not hypothetical —
the series was first written against 25.04-mobile and **failed on three files**
against 26.04, which is exactly the drift the pin exists to make loud.

`core` is the engine; we build it and never touch it. Every owned edit is in
`online`, in `browser/` (the web layer) and `android/` (the shell). That is what
keeps this series small enough to review by hand — nine files, not a fork of an
office suite.

`upstream.online.revision` is a **commit sha, not a branch.** A floating branch
means the build is not reproducible and these patches silently stop applying. This
fleet has already been bitten by a rolling-tag hash race and by a CI step that
network-fetches and dies on a hiccup, so the fetch must fail loudly rather than
carry on with whatever it got.

## What makes a good patch here

The documented trap in this fleet is a naively generated patch that broke the
sync. Concretely:

- Generate with `git format-patch -1 --zero-commit --no-signature`. The signature
  line carries the generating machine's git version, so without `--no-signature`
  a regenerated but otherwise identical patch reads as a change. `--zero-commit`
  keeps the scratch clone's commit sha out of the file for the same reason.
- Apply with `git am --keep-non-patch`. Without it `git am` eats a `[PATCH]`-like
  prefix out of the subject.
- **The commit message is the documentation.** Nothing else records why an edit
  exists; the diff only records what it does. Explain the failure, the cost, the
  user impact.
- No binary hunks. A binary blob in a patch is a blob in git, which is the thing
  this whole structure exists to avoid. Icons and other assets go in as text-
  encoded source or as a configure flag.
- One concern per patch, numbered in apply order. `git am` stops at the first
  failure, so a series that mixes concerns fails as a unit and tells you less.

## When one fails to apply

It must stop the build. A patch that no longer applies means upstream moved under
an edit we own, and the only honest outcomes are "rebase the patch" or "do not
ship" — never "skip it and publish an APK missing a feature nobody will notice is
missing". `tests/patches-apply.sh` is the assertion; the exact line to look for is

```
error: browser/src/map/Clipboard.js: patch does not apply
```

`git am --skip` and `git am -3` are both wrong here for the same reason: they turn
a loud failure into a quiet one.

## The series

| # | what | why |
|---|---|---|
| 0001 | Text Enhance over the canvas selection (Writer); Settings into the Edit menu | Enhance was a menu item that silently did nothing, because Android text input is a hidden `contenteditable` that never holds the document text |

0001 touches Writer's Edit menu only. Calc's is deliberately left alone: a
spreadsheet selection can span cells, and one plain-text rewrite pasted back over
it would scatter across them. A feature that mangles a document is worse than one
that is absent.

Rebranding is deliberately **not** a patch. `android/appSettings.gradle` is
generated from `@APP_NAME@` / `@ANDROID_PACKAGE_NAME@` / `@VENDOR@`, so the rebrand
is three `configure` flags carried as data in `build.json::upstream.online.configure`.
A patch would have hardcoded into the tree what upstream already exposes as a knob.
