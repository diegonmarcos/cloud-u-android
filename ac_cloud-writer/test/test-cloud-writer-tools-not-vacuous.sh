#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-cloud-writer-tools.sh can actually go red                   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# AN ASSERTION NOBODY HAS WATCHED FAIL IS DECORATION, and this repository has
# shipped the proof more than once: an assertion that compared an expression to
# itself, a pipeline whose exit status was read through `tail`, a
# `case "$OUT" in *"[NEW]"*)` where the brackets made a one-character class,
# markers written relative to the wrong directory, and four testers that passed
# only because ripgrep was absent and their call failed open. Every one of them
# printed green over a defect.
#
# So this runs the sibling tester against a DELIBERATELY BROKEN COPY of the
# application, once per planted defect, and requires the matching assertion to
# name it. If the tester stops seeing, this goes red and says which check has
# gone blind.
#
# THE BREAKS ARE APPLIED TO A COPY IN A TEMPORARY DIRECTORY, never to the
# working tree. A harness that edits the repository and restores afterwards is
# one failed restore away from committing its own sabotage, and on a CI runner
# it would race the publish gate's source hash. The copy carries an empty .git
# marker because the tester finds the repository root by walking up to one.
#
# python3 does the planting: it is already required by build.json::tests.shell,
# and an in-place text edit that must match EXACTLY is one place where a silent
# no-op (the anchor moved, nothing was replaced) has to be an error rather than
# an empty diff. That case is checked explicitly below.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
TESTER="$ROOT/ac_cloud-writer/test/test-cloud-writer-tools.sh"

[ -f "$TESTER" ] || { echo "FAIL   $TESTER is missing — there is nothing to prove"; exit 1; }

python3 - "$ROOT" <<'PYEOF'
import os, shutil, subprocess, sys, tempfile

root = sys.argv[1]

# Exactly what the tester reads. Copied rather than referenced so a planted
# defect cannot escape into the working tree.
NEEDED = [
    "ac_cloud-writer",
    "ab_cloud-libs-shared/libs/text-tools",
    "aa_cloud-superapp/data/constellation-fleet.json",
]

APP = "ac_cloud-writer"
SRC = APP + "/app/src/main/java/com/diegonmarcos/cloudwriter"

# (what a later change would plausibly do, which file, the edit, the assertion
#  that must name it). Every one of these is a real regression shape, not a
#  syntactic scribble: a service element added when somebody decides it is time
#  to serve, a summary routed through the rewrite engine, two tools quietly
#  sharing one model, a refusal deleted because it looked redundant.
BREAKS = [
    ("cloud-writer starts publishing the ITextTools action",
     APP + "/app/src/main/AndroidManifest.xml",
     ("        </activity>\n",
      '        </activity>\n\n'
      '        <service android:name=".WriterTextToolsService" android:exported="true"\n'
      '            android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA">\n'
      '            <intent-filter>\n'
      '                <action android:name="com.diegonmarcos.superapp.texttools.ITextTools" />\n'
      '            </intent-filter>\n'
      '        </service>\n'),
     "W1"),

    ("Summary is sent through the rewrite engine", SRC + "/WriterTools.kt",
     ("            WriterTool.SUMMARY -> client.summariseWith(",
      "            WriterTool.SUMMARY -> client.enhanceWith("),
     "W2 SUMMARY does not call client.summariseWith"),

    ("Grammar Check quietly reuses Text Enhance's model", SRC + "/WriterTools.kt",
     ("WriterPrefs.modelFor(app, WriterTool.GRAMMAR, provider)",
      "WriterPrefs.modelFor(app, WriterTool.ENHANCE, provider)"),
     "W2 the GRAMMAR arm"),

    ("Translate is handed a chat model it cannot use", SRC + "/WriterTools.kt",
     ("WriterTool.TRANSLATE -> client.translate(text, WriterPrefs.translateTarget(app))",
      "WriterTool.TRANSLATE -> client.translate(text, WriterPrefs.modelFor(app, WriterTool.ENHANCE, provider))"),
     "W2 the TRANSLATE arm resolves a model"),

    ("a tap on an empty box returns without a word", SRC + "/WriterTools.kt",
     ("        if (text.isBlank()) {\n"
      "            onDone(WriterOutcome(tool, null, app.getString(R.string.run_nothing_to_send)))\n"
      "            return\n"
      "        }\n",
      "        if (text.isBlank()) {\n"
      "            return\n"
      "        }\n"),
     "W3 run() returns without reporting"),

    ("the owner's credential is read into this application", SRC + "/WriterPrefs.kt",
     ("    fun isSeeded(context: Context)",
      "    fun borrowKey(client: TextToolsClient, providerId: String) =\n"
      "        client.revealAiKey(providerId)\n\n"
      "    fun isSeeded(context: Context)"),
     "W4 something under"),

    ("a token-shaped preference slot appears", SRC + "/WriterPrefs.kt",
     ('    const val KEY_PROVIDER = "ai_provider"',
      '    const val KEY_PROVIDER = "ai_provider"\n\n'
      '    const val KEY_TOKEN_PREFIX = "ai_token_"'),
     "W4 WriterPrefs declares a credential-shaped key"),

    ("Grammar Check falls back to the default style", SRC + "/WriterRegistry.kt",
     ("        val grammar = grammarStyle ?: return null",
      "        val grammar = grammarStyle ?: style(null)"),
     "W5 grammarPrompt() no longer returns null"),

    ("the Grammar refusal is deleted from run()", SRC + "/WriterTools.kt",
     ("        if (tool == WriterTool.GRAMMAR && WriterRegistry.grammarPrompt() == null) {",
      "        if (false) {"),
     "W5 run() no longer checks grammarPrompt()"),

    ("a provider defaults to a model it does not list", APP + "/build.json",
     ('"default_model": "google/gemini-2.5-flash"',
      '"default_model": "google/gemini-3.0-withdrawn"'),
     "W6 openrouter: default_model"),

    ("the grammar style leaves the registry", APP + "/build.json",
     ('"grammar": {', '"grammar_disabled": {'),
     "W6 build.json::writer_ai.styles has no `grammar` entry"),

    ("the application id drifts from the reserved address", APP + "/build.json",
     ('"application_id": "com.diegonmarcos.cloudwriter"',
      '"application_id": "com.diegonmarcos.writer"'),
     "W7 application_id is"),

    ("the Constellation registry is never regenerated",
     "aa_cloud-superapp/data/constellation-fleet.json",
     ('"package": "com.diegonmarcos.cloudwriter"',
      '"package": "com.diegonmarcos.cloudwriter.absent"'),
     "W8 constellation-fleet.json carries no entry"),
]

failures = []
for label, rel, (old, new), expect in BREAKS:
    stage = tempfile.mkdtemp(prefix="cloud-writer-vacuity-")
    try:
        os.mkdir(os.path.join(stage, ".git"))   # the marker the tester walks up to
        for item in NEEDED:
            src = os.path.join(root, item)
            dst = os.path.join(stage, item)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if os.path.isdir(src):
                shutil.copytree(src, dst)
            else:
                shutil.copy(src, dst)

        target = os.path.join(stage, rel)
        text = open(target).read()
        if text.count(old) != 1:
            failures.append("%s: the planted edit matched %d times, not once — the anchor has moved "
                            "and this harness is no longer breaking what it names"
                            % (label, text.count(old)))
            continue
        open(target, "w").write(text.replace(old, new, 1))

        out = subprocess.run(["bash", os.path.join(stage, "ac_cloud-writer/test/test-cloud-writer-tools.sh")],
                             capture_output=True, text=True)
        red = [l for l in out.stdout.split("\n") if l.startswith("FAIL") and expect in l]
        if out.returncode == 0:
            failures.append("%s: the tester still passed — that assertion proves nothing" % label)
        elif not red:
            seen = "; ".join(l for l in out.stdout.split("\n") if l.startswith("FAIL"))
            failures.append("%s: went red but NOT on %r (saw: %s)" % (label, expect, seen or out.stderr[-200:]))
        else:
            print("ok     watched red: %s" % label)
    finally:
        shutil.rmtree(stage, ignore_errors=True)

print("── %d of %d planted defects were caught ──" % (len(BREAKS) - len(failures), len(BREAKS)))
for f in failures:
    print("FAIL   " + f)
sys.exit(1 if failures else 0)
PYEOF
