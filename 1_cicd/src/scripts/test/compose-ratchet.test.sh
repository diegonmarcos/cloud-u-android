#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ compose-ratchet.test.sh — does the ratchet catch new View UI,    ║
# ║ and refuse progress that was not written down?                   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# A ratchet that is green on the real tree proves nothing: a script with the
# comparison deleted is green there too. Every case below builds a small tree
# with the REAL counting rules (copied from compose-migration.json, so a rule
# edit is exercised here the same day), breaks or migrates something the way a
# real commit would, and asserts the verdict AND the reason.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-compose-ratchet.py"
PLAN="1_cicd/src/data/compose-migration.json"
FAILURES=0

ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── the pristine fixture: one View app at its baseline, one Compose app, one fork ──
PRISTINE="$WORK/pristine"
SRC="app/src/main/java/x"
mkdir -p "$PRISTINE/1_cicd/src/data" "$PRISTINE/ac_views/$SRC" "$PRISTINE/ac_views/app/src/main/res/layout" \
         "$PRISTINE/ac_compose/$SRC" "$PRISTINE/ac_fork/$SRC/ours" "$PRISTINE/ac_fork/$SRC/upstream" \
         "$PRISTINE/ab_cloud-libs-shared/libs/kit/src/main/java/k"

cat > "$PRISTINE/ac_views/$SRC/MainActivity.kt" <<'KT'
class MainActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContentView(R.layout.activity_main) }
}
KT
cat > "$PRISTINE/ac_views/$SRC/ListPage.kt" <<'KT'
/** Mentions TextView( in a comment and "LinearLayout(" in a string: neither may count. */
class ListPage(private val repo: Repo) : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        LinearLayout(requireContext()).apply { addView(TextView(context)) }
}
KT
cat > "$PRISTINE/ac_views/$SRC/Trampoline.kt" <<'KT'
class Trampoline : Activity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); startActivity(intent); finish() }
}
KT
cat > "$PRISTINE/ac_views/$SRC/Results.kt" <<'KT'
sealed class Result { data class Error(val m: String) : Result() }
KT
cat > "$PRISTINE/ac_views/$SRC/Notes.kt" <<'KT'
// Logic only. A comment that says TextView( and a string that says "LinearLayout(x)"
// describe Views; they do not build one, so this file must not count.
val hint = "LinearLayout(x)"
KT
cat > "$PRISTINE/ac_views/$SRC/Gauge.kt" <<'KT'
class Gauge @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs)
KT
echo '<LinearLayout/>' > "$PRISTINE/ac_views/app/src/main/res/layout/activity_main.xml"

cat > "$PRISTINE/ac_compose/$SRC/MainActivity.kt" <<'KT'
class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { Home() } }
}
@Composable fun Home() { Button(onClick = {}) { Text("ok") }; Switch(true, {}) }
KT
cat > "$PRISTINE/ac_compose/$SRC/Page.kt" <<'KT'
class Page : KitComposeFragment() { @Composable override fun Content() { Text("x") } }
KT
# Another unit declares a sealed `Error` that IS a dialog. Name resolution is per unit,
# so ac_views' Result.Error must not become a screen because of it.
cat > "$PRISTINE/ac_compose/$SRC/Errors.kt" <<'KT'
class Error(ctx: Context) : Dialog(ctx) { init { setContentView(R.layout.x) } }
KT

cat > "$PRISTINE/ac_fork/$SRC/ours/OurLog.kt" <<'KT'
class OurLog : SimpleActivity() { override fun onCreate(b: Bundle?) { setContentView(R.layout.log) } }
KT
cat > "$PRISTINE/ac_fork/$SRC/upstream/Theirs.kt" <<'KT'
class Theirs : Activity() { override fun onCreate(b: Bundle?) { setContentView(R.layout.theirs) } }
KT

python3 - "$ROOT/$PLAN" "$PRISTINE/$PLAN" <<'PY' || { echo "could not write the fixture plan" >&2; exit 1; }
import json, sys
real = json.load(open(sys.argv[1]))
plan = {k: real[k] for k in ("unit_dir_pattern", "shared_libs_dir", "rules")}
plan["units"] = [
    {"id": "ac_views", "scope": "in", "baseline": {"view_screens": 2, "layout_xml": 1, "view_ui_files": 1, "custom_views": 1}},
    {"id": "ac_compose", "scope": "in", "baseline": {"view_screens": 1, "layout_xml": 0, "view_ui_files": 0, "custom_views": 0}},
    {"id": "ac_fork", "scope": "in", "roots": ["app/src/main/java/x/ours"], "screen_bases": ["SimpleActivity"],
     "baseline": {"view_screens": 1, "layout_xml": 0, "view_ui_files": 0, "custom_views": 0}},
]
json.dump(plan, open(sys.argv[2], "w"), indent=2)
PY

# Each case gets a fresh copy, its own mutation, and a verdict.
run_case() {   # name, expected-exit, expected-message-regex, mutation (bash, cwd = sandbox)
    local name="$1" want_rc="$2" want_msg="$3" mutate="$4" dir rc out
    dir="$WORK/case-$RANDOM$RANDOM"
    cp -r "$PRISTINE" "$dir"
    ( cd "$dir" && eval "$mutate" ) || { fail "$name (mutation did not apply)"; return; }
    out="$(python3 "$GUARD" --root "$dir" 2>&1)"; rc=$?
    if [ "$rc" -ne "$want_rc" ]; then
        fail "$name — exit $rc, wanted $want_rc"; printf '%s\n' "$out" | sed 's/^/        /'; return
    fi
    # A here-string, not `printf | grep -q`: under pipefail an early grep match can SIGPIPE
    # the producer and read as a miss (#634).
    if [ -n "$want_msg" ] && ! grep -Eq -- "$want_msg" <<<"$out"; then
        fail "$name — exit right but the reason is missing: /$want_msg/"; printf '%s\n' "$out" | sed 's/^/        /'; return
    fi
    ok "$name"
}

echo "compose ratchet:"

run_case "the fixture at its baseline is green" 0 "" ":"

run_case "a new XML layout fails" 1 "ac_views: layout_xml went UP 1 -> 2" \
    "echo '<FrameLayout/>' > ac_views/app/src/main/res/layout/item_row.xml"

run_case "a new View fragment fails as a screen and as a View file" 1 "ac_views: view_screens went UP 2 -> 3" \
    "printf 'class Settings : Fragment() {\n override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) = i.inflate(R.layout.s, c, false)\n}\n' > ac_views/$SRC/Settings.kt"

run_case "a Compose screen that still hand-builds rows is not migrated" 1 "ac_views: view_screens went UP" \
    "printf 'class Mixed : Fragment() {\n fun v() = ComposeView(ctx).also { TextView(ctx) }\n}\n' > ac_views/$SRC/Mixed.kt"

run_case "a new custom View fails" 1 "ac_views: custom_views went UP 1 -> 2" \
    "printf 'class Dial(c: Context) : Gauge(c)\n' > ac_views/$SRC/Dial.kt"

run_case "migrating a screen without lowering the baseline fails, naming the new value" 1 \
    "ac_views: view_screens went down 2 -> 1 .*baseline.view_screens to 1" \
    "printf 'class ListPage : Fragment() {\n override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) = ComposeView(requireContext()).apply { setContent { } }\n}\n' > ac_views/$SRC/ListPage.kt"

run_case "a new app written in Views fails with an implicit baseline of zero" 1 "ac_new: view_screens went UP 0 -> 1" \
    "mkdir -p ac_new/$SRC && cp ac_views/$SRC/MainActivity.kt ac_new/$SRC/"

run_case "a new shared lib written in Views fails" 1 "libs:kit: view_ui_files went UP 0 -> 1" \
    "printf 'fun row(c: Context) = TextView(c)\n' > ab_cloud-libs-shared/libs/kit/src/main/java/k/Row.kt"

run_case "Compose Button/Switch in a @Composable file do not count" 0 "" \
    "printf '@Composable fun More() { Button(onClick = {}) {}; Switch(false, {}); Chip() }\n' > ac_compose/$SRC/More.kt"

run_case "a KitComposeFragment subclass is a Compose screen" 0 "" \
    "printf 'class Page2 : KitComposeFragment() { @Composable override fun Content() {} }\n' > ac_compose/$SRC/Page2.kt"

run_case "an options-menu inflate is not a View" 0 "" \
    "printf 'class Menus : KitComposeFragment() {\n override fun onCreateOptionsMenu(m: Menu, i: MenuInflater) { i.inflate(R.menu.top, m) }\n @Composable override fun Content() {}\n}\n' > ac_compose/$SRC/Menus.kt"

run_case "a layout inflated through View.inflate counts" 1 "ac_views: view_ui_files went UP 1 -> 2" \
    "printf 'fun row(c: Context) = View.inflate(c, R.layout.row, null)\n' > ac_views/$SRC/Row.kt"

run_case "a View screen in a fork's upstream tree is outside its roots" 0 "" \
    "cp ac_fork/$SRC/upstream/Theirs.kt ac_fork/$SRC/upstream/Theirs2.kt"

run_case "a View screen added to the fork's own roots counts through the upstream base" 1 "ac_fork: view_screens went UP 1 -> 2" \
    "sed 's/OurLog/OurLog2/' ac_fork/$SRC/ours/OurLog.kt > ac_fork/$SRC/ours/OurLog2.kt"

run_case "a declared root that disappeared fails instead of reading as migrated" 1 "ac_fork: root .* does not exist" \
    "rm -r ac_fork/$SRC/ours && mkdir -p ac_fork/$SRC/moved"

run_case "a unit declared but gone fails" 1 "ac_gone: declared .* but no such unit exists" \
    "python3 -c \"import json;p='$PLAN';j=json.load(open(p));j['units'].append({'id':'ac_gone','scope':'in'});json.dump(j,open(p,'w'))\""

run_case "an out-of-scope unit is not counted" 0 "" \
    "python3 -c \"import json;p='$PLAN';j=json.load(open(p));j['units'].append({'id':'ac_vendored','scope':'out','reason':'x'});json.dump(j,open(p,'w'))\" && mkdir -p ac_vendored/$SRC && cp ac_views/$SRC/ListPage.kt ac_vendored/$SRC/"

run_case "tests do not count" 0 "" \
    "mkdir -p ac_views/app/src/test/java/x && cp ac_views/$SRC/ListPage.kt ac_views/app/src/test/java/x/ListPageTest.kt"

echo
if [ "$FAILURES" -ne 0 ]; then
    echo "$FAILURES case(s) failed"
    exit 1
fi
echo "all cases passed"
