# cloud-account — the visual pass

Status: DESIGN (2026-10-09). Follows `cloud-account-redesign.md` (navigation, data, logic: done and
device-verified). That spec reused cloud-store's dense list look without deciding anything visual; the
result reads as a key/value dump. This spec decides the visual layer. Logic, ops and testers stay.

## 0. Decisions

1. **An account app, not a settings dump.** The first screen is the person and the phone: avatar, name,
   device, state. Raw key paths (`profile › titles_v2`) never appear on a page; a field has a human label
   from the schema and the path lives only in a long-press "copy path" affordance.
2. **One component set, in libs:ui-kit.** Everything the Account pages draw comes from ui-kit: the
   existing `KitCard`, `KitSectionHeader`, `KitSettingsRow`, `KitSwitchRow`, `KitSelectableTile`,
   `KitConfirmDialog`, `KitEmptyState`, plus the new ones in section 2. No page defines a private row
   style; a page that needs a new element adds it to ui-kit so Store and SuperApp can adopt it.
3. **Fleet palette, no new colours.** `KitPalette` (surface, surfaceSelected, textPrimary, textSecondary,
   accent, hairline, tileInk) is the whole palette. State colours are three semantic tokens added to
   KitPalette: `ok`, `warn`, `bad`, defined once in `AccountHost.DEFAULT_PALETTE` and the SuperApp
   palette. Nothing else is coloured.
4. **Density: cards breathe, lists are dense.** Hero and tiles use 16 dp gutters and the title scale;
   list rows keep Store's 12/8 dp and body/caption scale so a 247-row list stays scannable.
5. **State is a shape before it is a word.** Every row that has a state carries a `KitStatePill`
   (dot + short word) in the trailing slot; the detail sentence is secondary text under the label.
6. **Nothing shows a secret value.** Secret-class fields render as a fingerprint chip (`KitFingerprint`),
   same as today, with "copy" disabled.

## 1. The screens

### Account ▸ profile
```
┌─────────────────────────────────────────┐
│  (avatar 56)  Diego Coelho Marcos        │  ← KitHero: avatar from about.profile_picture, else initials
│               me@diegonmarcos.com        │     on accent; name title scale; email + location secondary
│               Berlin/DE · LEAFY          │
├─────────────────────────────────────────┤
│  THIS PHONE                              │  ← KitSectionHeader (eyebrow style, no caption sentence)
│  ┌─────────────────────────────────────┐ │
│  │ ▣ galaxy-s21     Galaxy S21+ · A15  │ │  ← KitDeviceCard: device glyph, id (title), model + Android
│  │ profile loaded · backed up 10:48    │ │     one line of state; tap = picker sheet
│  └─────────────────────────────────────┘ │
│  ┌───────────┐ ┌───────────┐             │
│  │ 247 apps  │ │ 212 keys  │  ← KitStatTile ×4 in a 2×2 grid: apps, settings, drift, permissions
│  │ installed │ │ in sync   │     big number, caption, pill colour by state
│  └───────────┘ └───────────┘
│  [ Backup now ]  [ Restore ]  [ Migrate this phone ]   ← KitActionBar: primary filled, others outlined
├─────────────────────────────────────────┤
│  ABOUT                                   │
│  Company        LEAFY                  › │  ← KitSettingsRow per schema field, label from schema,
│  Website        linktree.diegonmarcos… › │     value one line, chevron = edit sheet; `empty` rows
│  Titles         Product Engineering … › │     show "Add" in accent instead of the word empty
└─────────────────────────────────────────┘
```

### Account ▸ connect
- Top: `KitStatusBanner`: "Connected · GitHub · fetched 12:05" (ok) or "Not connected" (warn) with the
  one action that fixes it.
- Then one `KitCard` per forge (GitHub, Gitea, File), card title + caption ("read and write" / "read
  only" / "offline"), and inside it one `KitSelectableTile` per way, laid out as a wrap row. The
  selected way shows its form (token field, or the gh device-code line) inside the card, not a dialog.
- Origin is a `KitSegmented` (GitHub | Gitea) under the cards; repo and branch as two secondary lines.
- Bottom: `[ Fetch now ]` and the last result sentence.

### Profiles ▸ devices
- One `KitDeviceCard` per device (same component as the profile page), DEFAULT first with a crown glyph,
  this phone marked "this phone". Trailing overflow menu (⋮): Load, Set as DEFAULT, Delete. The card's
  primary button is Load; Backup here appears only on this phone's card.
- Top `KitActionBar`: Fetch, Backup now.

### Profiles ▸ working
- Topic list as `KitSectionHeader` + `KitSettingsRow`s, exactly the profile page's ABOUT block, for
  every topic in schema order. Secret-class rows show `KitFingerprint`. Unsaved edits raise a sticky
  bottom `KitSaveBar` ("3 changes · Save · Discard").

### Profiles ▸ diff
- Summary `KitStatusBanner`: "In sync" (ok) or "12 keys differ in 3 apps" (warn).
- Per app a `KitCard` with the app icon, name, count pill, and the rows: label, file value / phone value
  as two secondary lines, trailing two icon buttons (→ phone, ← file). "Apply all" / "Capture all" in
  the card header.

### Setup ▸ runbook
- The eight steps as a vertical `KitStepper`: a rail with ○ ● ✓ ✗ glyphs joined by a line, step title,
  detail sentence, trailing button (Run / Retry / Open). Done steps collapse to one line.
- Sticky `KitActionBar` at the bottom: `[ Run all ]` with the plan sheet before acting.

### Setup ▸ apps, configs, perms
- `KitListRow`s grouped under `KitSectionHeader`s by the Store's shelf classification (apps) or by app
  (configs, perms), each with icon, label, state pill, one trailing button. Group header carries the
  bulk action ("Install missing", "Grant all"). Same component the Store uses, so the two apps match.

### Secrets ▸ connections / secrets / grants
- `KitListRow`s: label = human name from the manifest, secondary = path, trailing = value (config
  class) or `KitFingerprint` (secret class). Grants page: one `KitCard` per package with chips per key
  and an "×" per chip; "Revoke all" in the header.

### Settings
- `KitSettingsRow` / `KitSwitchRow` groups: Device, Vault (primary forge), Backup (auto-backup
  segmented: Off · Daily · Wi-Fi), Debug API switch, About (version, sha, build date).

## 2. New ui-kit components (libs:ui-kit, commonMain)
`KitHero`, `KitDeviceCard`, `KitStatTile`, `KitActionBar`, `KitStatusBanner`, `KitStatePill`,
`KitFingerprint`, `KitSegmented`, `KitStepper`, `KitListRow`, `KitSaveBar`, `KitEmptyState` (exists).
Each: one composable, palette from `LocalKitPalette`, sizes from a `KitDensity` object (the Store's
8/12/16 and caption/body/title scale moved into ui-kit so the Store can switch to it later), a test tag.

## 3. Rules the tester enforces
- No page file contains `fontFamily = FontFamily.Monospace`, a raw `Text(` of a `section.key` path, or
  the word `empty` as a value.
- No colour literal (`Color(0x…)`) outside ui-kit.
- Every state-bearing row uses `KitStatePill`; grep for `"✓ "`/`"✗ "` string prefixes in page files → RED.
- Every secret-class render goes through `KitFingerprint`.
- The nav guard and the compose ratchet stay green; the debug API is unchanged.

## 4. Not in scope
Logic, ops, testers of the redesign spec; SuperApp pages; the Store's own adoption of the new
components (a later task once they exist).
