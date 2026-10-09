#!/usr/bin/env bash
# test-account-ui: the Cloud Account visual pass (a0_docs/eng-specs/cloud-account-ui.md, section 3).
#
# Static (no build, no device, no network). Holds that:
#  1. no Account page file draws in monospace, draws a raw `section.key` path with Text(, or shows
#     the word `empty` as a value (an unfilled field offers "Add");
#  2. no colour literal (Color(0x...)) in the Account lib or the app: colours come from KitPalette,
#     and the three state tokens ok / warn / bad are declared on it and by both palette builders;
#  3. a state is a shape: no page file prefixes a string with "✓ " / "✗ " (results become a state +
#     sentence for KitStatusBanner / KitStatePill);
#  4. every secret-class render goes through KitFingerprint (the masked bullets string is gone);
#  5. the ui-kit holds KitDensity and every component of spec section 2, each one composable on
#     LocalKitPalette with a test tag, and the pages draw with them (hero, device card, 2x2 stat tiles,
#     action bar, banner, segmented origin, stepper, save bar, list rows, chips).
# Then each mutation is planted in a scratch copy and the check must go RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROF="ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile"
KIT="ab_cloud-libs-shared/libs/ui-kit/src/commonMain/kotlin/com/diegonmarcos/superapp/uikit"
PAGES="AccountPages.kt ProfilesPages.kt RunbookPage.kt AppsPage.kt PermsPage.kt AccountVaultTabs.kt FleetSetupTab.kt AccountKit.kt"
FILES="$KIT/CloudKit.kt $KIT/KitComponents.kt $PROF/AccountHost.kt
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ui/LauncherPalette.kt"
for f in $PAGES; do FILES="$FILES $PROF/$f"; done

check() {
python3 - "$1" "$PROF" "$KIT" "$PAGES" <<'PY'
import os, re, sys
R, PROF, KIT, PAGES = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4].split()
fails = 0
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1
def code(s):  # comments out, strings kept (the rules are about string literals)
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?m)^\s*//[^\n]*|\s//\s[^\n]*", "", s)

pages = {p: code(rd(f"{PROF}/{p}")) for p in PAGES}

# 1. monospace / raw paths / the word empty
mono = [p for p, s in pages.items() if "FontFamily.Monospace" in s]
ok(not mono, "no page file draws in monospace", f"monospace in {mono}")
raw = [p for p, s in pages.items() if re.search(r"\bText\(\s*(row\.path|f\.path|path|key|full)\s*[,)]", s)]
ok(not raw, "no page file draws a raw section.key path with Text(", f"raw path Text( in {raw}")
empty = [p for p, s in pages.items() if re.search(r'"[Ee]mpty"', s)]
ok(not empty, "no page file shows the word empty as a value", f'"empty" as a value in {empty}')
ok('"Add"' in pages["AccountPages.kt"] and "InfoMask.Kind.EMPTY ->" in pages["AccountPages.kt"], "an unfilled about field offers Add", "an unfilled field does not offer Add")

# 2. colour literals + the state tokens
lit = []
for base in (PROF, "ac_cloud-account/app/src/main/java"):
    d = os.path.join(R, base)
    for dp, _, fs in os.walk(d):
        for f in fs:
            if f.endswith(".kt") and re.search(r"\bColor\(0x", code(open(os.path.join(dp, f), encoding="utf-8").read())):
                lit.append(f)
kc = code(rd(f"{KIT}/KitComponents.kt"))
if re.search(r"\bColor\(0x", kc): lit.append("KitComponents.kt")
ok(not lit, "no colour literal in the Account lib, the app or the kit's components", f"colour literal in {lit}")
ck = code(rd(f"{KIT}/CloudKit.kt"))
ok(all(re.search(rf"val {t}: Color = DEFAULT_", ck) for t in ("ok", "warn", "bad")), "KitPalette declares ok / warn / bad", "KitPalette lacks a state token")
ah = code(rd(f"{PROF}/AccountHost.kt")); lp = code(rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ui/LauncherPalette.kt"))
ok(all(f"{t} = " in ah[ah.find("val DEFAULT_PALETTE"):] for t in ("ok", "warn", "bad")), "AccountHost.DEFAULT_PALETTE declares its state tokens", "DEFAULT_PALETTE leaves a state token to the default")
ok(all(f"{t} = " in lp[lp.find("KitPalette.fromArgb("):][:400] for t in ("ok", "warn", "bad")), "SuperApp's palette declares its state tokens", "SuperApp's palette leaves the state tokens to the default")
ok(all(f"KitState.{s} -> p.{t}" in kc for s, t in (("OK", "ok"), ("WARN", "warn"), ("BAD", "bad"))), "a state colours by its palette token", "a state does not colour by ok / warn / bad")

# 3. a state is a shape before it is a word
glyph = [p for p, s in pages.items() if re.search(r'"[✓✗] ', s)]
ok(not glyph, 'no page file prefixes a string with "✓ " / "✗ "', f'"✓ "/"✗ " string prefixes in {glyph}')
ok("KitStatePill(" in kc.split("fun KitListRow(")[1].split("fun KitChip(")[0], "KitListRow draws its state through KitStatePill", "KitListRow's state bypasses KitStatePill")

# 4. secrets through KitFingerprint
bullets = [p for p, s in pages.items() if "••••" in s or "R.string.vault_masked" in s]
ok(not bullets, "no page file draws the masked bullets string", f"masked bullets drawn in {bullets}")
vt = pages["AccountVaultTabs.kt"]
need = [
    ("AccountPages.kt", "InfoMask.Kind.MASKED -> KitFingerprint("),
    ("ProfilesPages.kt", "if (masked) KitFingerprint("),
    ("ProfilesPages.kt", "if (secret) KitFingerprint(f.a"),
    ("ProfilesPages.kt", "if (secret) KitFingerprint(f.b"),
    ("AccountVaultTabs.kt", "KitFingerprint(row.fingerprint"),
    ("AccountVaultTabs.kt", "else KitFingerprint(fingerprint("),
]
miss = [n for p, n in need if n not in pages[p]]
ok(not miss, "every secret-class render goes through KitFingerprint", f"secret rendered without KitFingerprint: {miss}")

# 5. the kit and its use
comps = ["KitHero", "KitDeviceCard", "KitStatTile", "KitActionBar", "KitStatusBanner", "KitStatePill", "KitFingerprint",
         "KitSegmented", "KitStepper", "KitListRow", "KitSaveBar", "KitChip"]
bad = []
for c in comps:
    m = re.search(rf"@Composable\s+fun {c}\((.*?)\n}}\n", kc, flags=re.S)
    if not m or "LocalKitPalette.current" not in m.group(1) and c not in ("KitActionBar",) or "testTag(" not in m.group(1) or not ("KitPartTags." in m.group(1) or "modifier.testTag(tag)" in m.group(1)):
        bad.append(c)
ok(not bad, "every new component is one composable on LocalKitPalette with a test tag", f"component missing, off-palette or untagged: {bad}")
ok(all(f"val {n}: Dp = {v}.dp" in kc for n, v in (("small", 8), ("medium", 12), ("large", 16))) and "TEXT_SCALE: Float = 0.78f" in kc,
   "KitDensity carries the Store's 8/12/16 dp and its type scale", "KitDensity lost a step or the type scale")
ap = pages["AccountPages.kt"]
uses = [
    ("AccountPages.kt", "KitHero(", 1), ("AccountPages.kt", "KitDeviceCard(", 1), ("AccountPages.kt", "KitStatTile(", 4),
    ("AccountPages.kt", "KitActionBar(", 1), ("AccountPages.kt", "KitStatusBanner(", 1), ("AccountPages.kt", "KitSegmented(", 2),
    ("AccountPages.kt", "KitListRow(", 1), ("ProfilesPages.kt", "KitDeviceCard(", 2), ("ProfilesPages.kt", "KitSaveBar(", 1),
    ("ProfilesPages.kt", "KitListRow(", 2), ("RunbookPage.kt", "KitStepper(", 1), ("AppsPage.kt", "KitListRow(", 1),
    ("PermsPage.kt", "KitListRow(", 1), ("FleetSetupTab.kt", "KitListRow(", 1), ("AccountVaultTabs.kt", "KitListRow(", 2),
    ("AccountVaultTabs.kt", "KitChip(", 1),
]
short = [f"{p}:{n}" for p, n, k in uses if pages[p].count(n) < k]
ok(not short, "the pages draw with the kit (hero, device card, 2x2 tiles, bars, banners, stepper, rows, chips)", f"a page stopped using: {short}")
private = [p for p, s in pages.items() if re.search(r"private fun (Dense|Mono|Tile|Pill|RowButton)\(", s)]
ok(not private, "no page defines a private row style", f"private row style in {private}")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  for x in $FILES; do mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; done
  mkdir -p "$d/ac_cloud-account/app/src/main/java"; cp -r "$ROOT/ac_cloud-account/app/src/main/java/." "$d/ac_cloud-account/app/src/main/java/"
  python3 - "$d/$f" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
P=$PROF
M=0
mutate "a page draws in monospace" $P/AppsPage.kt 'secondary = "$pkg · $why",' 'secondary = "$pkg · $why", fontFamily = FontFamily.Monospace,' || M=$((M+1))
mutate "a raw path drawn with Text(" $P/AccountPages.kt 'AboutValue(row, full, shown, editable)' 'Text(full); AboutValue(row, full, shown, editable)' || M=$((M+1))
mutate "the word empty as a value" $P/AccountPages.kt 'Text(if (editable) "Add" else "—"' 'Text(if (editable) "empty" else "—"' || M=$((M+1))
mutate "a colour literal in a page" $P/RunbookPage.kt 'tag = "runbook",' 'tag = "runbook", color = Color(0xFFFF0000),' || M=$((M+1))
mutate "a colour literal in a kit component" $KIT/KitComponents.kt 'KitState.IDLE -> p.textSecondary' 'KitState.IDLE -> Color(0xFF888888)' || M=$((M+1))
mutate "a state token dropped from KitPalette" $KIT/CloudKit.kt 'val bad: Color = DEFAULT_BAD,' '' || M=$((M+1))
mutate "DEFAULT_PALETTE leaves ok to the default" $P/AccountHost.kt 'ok = 0xFF7FC98F.toInt(), ' '' || M=$((M+1))
mutate "SuperApp palette drops the state tokens" aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ui/LauncherPalette.kt 'ok = state(ctx, R.color.cloud_state_ok), warn = state(ctx, R.color.cloud_state_warn), bad = state(ctx, R.color.cloud_state_bad)' '' || M=$((M+1))
mutate "a check-mark string prefix" $P/ProfilesPages.kt 'ifBlank { "fetched" }' 'ifBlank { "✓ fetched" }' || M=$((M+1))
mutate "a cross string prefix" $P/AppsPage.kt 'Said(KitState.BAD, "capture failed")' 'said("✗ capture failed")' || M=$((M+1))
mutate "KitListRow state bypasses the pill" $KIT/KitComponents.kt 'if (pill != null) KitStatePill(pill.first, pill.second, Modifier.padding(start = KitDensity.small))' 'if (pill != null) Text(pill.first)' || M=$((M+1))
mutate "secrets page draws the fingerprint as text" $P/AccountVaultTabs.kt 'KitFingerprint(row.fingerprint, tag = row.path)' 'Text(row.fingerprint)' || M=$((M+1))
mutate "working page masks with bullets" $P/ProfilesPages.kt 'if (masked) KitFingerprint(fingerprint(text), tag = full)' 'if (masked) Text("•••• (${text.length})")' || M=$((M+1))
mutate "about page masks with bullets" $P/AccountPages.kt 'InfoMask.Kind.MASKED -> KitFingerprint(' 'InfoMask.Kind.MASKED -> Text("••••"); if (false) KitFingerprint(' || M=$((M+1))
mutate "connections masks with the bullets string" $P/AccountVaultTabs.kt 'else KitFingerprint(fingerprint(' 'else Text(stringResource(R.string.vault_masked, 0)); if (false) KitFingerprint(fingerprint(' || M=$((M+1))
mutate "a component loses its test tag" $KIT/KitComponents.kt 'modifier.fillMaxWidth().testTag(KitPartTags.ACTION_BAR),' 'modifier.fillMaxWidth(),' || M=$((M+1))
mutate "a component leaves the kit" $KIT/KitComponents.kt 'fun KitSaveBar(' 'fun SaveBarGone(' || M=$((M+1))
mutate "KitDensity loses a step" $KIT/KitComponents.kt 'val medium: Dp = 12.dp' 'val medium: Dp = 10.dp' || M=$((M+1))
mutate "the profile loses its stat tiles" $P/AccountPages.kt 'KitStatTile("$perms"' 'Text("$perms"' || M=$((M+1))
mutate "the runbook loses its stepper" $P/RunbookPage.kt 'KitStepper(ids.map' 'listOf(ids.map' || M=$((M+1))
mutate "a page defines a private row style" $P/PermsPage.kt '@Composable
private fun ItemRow(' '@Composable
private fun Mono(t: String) = Text(t)
@Composable
private fun ItemRow(' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
