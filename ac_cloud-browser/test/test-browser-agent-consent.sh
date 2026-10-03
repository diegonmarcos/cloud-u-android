#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I9 the assistant asks before it acts, and holds no secret   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# A mutating tool without confirm acts behind his back; a tool with no
# branch is a dead promise to the model; a tool that reads the profile or
# the vault hands his data to a model; an "allow" over the API makes the
# consent sheet decorative. Matched in COMMENT-STRIPPED Kotlin.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    b = build_json(root)["ui"]["browser"]
    ai = [a for a in b.get("addons", []) if a["id"] == "ai"]
    ok(len(ai) == 1, "the ai add-on is declared once")
    if not ai: return
    ai = ai[0]
    tools = ai.get("tools", [])
    ok(len(tools) >= 10, "the assistant declares its tools")
    ok(isinstance(ai.get("max_tool_calls_per_turn"), int) and 0 < ai["max_tool_calls_per_turn"] <= 10, "a per-turn tool cap is declared")
    src = kotlin(root, LIB, APP)
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    agent = src.get(os.path.join(LIB, "BrowserAgent.kt"), "")
    runner = src.get(os.path.join(APP, "search", "AgentRunner.kt"), "")
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    body = frag[frag.find("private fun runAgentTool("):]
    body = body[:body.find("\n    }\n")]
    for t in tools:
        ok(not t.get("mutating") or t.get("confirm") is True, "mutating tool `%s` is confirm:true" % t["id"])
        ok(re.search(r'"%s"(\s*,\s*"[a-z_]+")*\s*->' % re.escape(t["id"]), body) or
           re.search(r'"[a-z_]+"\s*,\s*"%s"\s*->' % re.escape(t["id"]), body), "tool `%s` has its runAgentTool branch" % t["id"])
    for name, text in (("runAgentTool", body), ("AgentRunner", runner), ("BrowserAgent", agent)):
        ok("BrowserProfileStore" not in text and "revealAiKey" not in text and "BrowserAutofillMatch" not in text,
           "%s reads no profile, autofill or vault value" % name)
    ok("if (tool.confirm) when (decisions[c.id])" in agent and "null -> return Outcome.Pending(" in agent,
       "a confirm tool without his decision stops the turn")
    ok('"denied: ' in agent, "a denied tool is reported to the model as denied")
    ok("AgentPolicy.refuse(c.name, c.args) ?:" in agent, "the policy runs before any confirmation")
    ok(re.search(r"pass\(word\|wd\)\?", agent) and "cc-?(number" in agent, "the policy names password and card fields")
    ok("allow" not in api.split('Op("ai/ask"')[1].split(")")[0].replace("there is no allow", ""), "ai/ask offers no allow parameter")
    ok("Decision.ALLOW" not in api, "the debug API never decides")
    ok("BrowserAgentHost.decide = " in runner, "his decision comes only from the on-screen sheet")
    for op in ("ai/tools", "ai/ask", "ai/sessions"):
        ok('Op("%s"' % op in api and '"%s"' % op in api, "route %s is documented and handled" % op)
    ok('!config.addons.enabled("ai"' in api, "the ai routes refuse while the add-on is off")
    ok('"ai_chat" -> { showAgentChat()' in frag and '"agent_confirm" -> { showAgentConfirm(' in frag, "the chat row and the consent sheet are wired")

BJ = "ac_cloud-browser/build.json"
LIBP = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/"
APPP = "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/"
main("agent consent", check, [
    ("click stops asking", BJ, '"Click the first element matching a CSS selector on the open page.",\n              "params": {\n                "css": {\n                  "type": "string",\n                  "description": "CSS selector"\n                }\n              },\n              "mutating": true,\n              "confirm": true', '"Click the first element matching a CSS selector on the open page.",\n              "params": {\n                "css": {\n                  "type": "string",\n                  "description": "CSS selector"\n                }\n              },\n              "mutating": true,\n              "confirm": false', "`click` is confirm:true"),
    ("a confirm tool runs undecided", LIBP + "BrowserAgent.kt", "null -> return Outcome.Pending(", "null -> run(c).also { Outcome.Pending(", "stops the turn"),
    ("a tool reads the profile", LIBP + "BrowserHostFragment.kt", '"list_tabs" -> done(', '"list_tabs" -> done(JSONObject().put("p", BrowserProfileStore(requireContext()).load().toString())); "x_list" -> done(', "reads no profile"),
    ("the API can allow", APPP + "debugapi/BrowserDebugApi.kt", 'else -> need(q["text"].orEmpty(), "text") ?: r.ask(', 'else -> if (q["allow"] == "1") r.decide(q["call"].orEmpty(), AgentLoop.Decision.ALLOW == AgentLoop.Decision.ALLOW) else need(q["text"].orEmpty(), "text") ?: r.ask(', "never decides"),
    ("the scrape tool loses its branch", LIBP + "BrowserHostFragment.kt", '            "scrape" -> {\n                wv ?: return needPage()\n                val sc = config.addons["scraper"]', '            "scrape_x" -> {\n                wv ?: return needPage()\n                val sc = config.addons["scraper"]', "`scrape` has its runAgentTool"),
])
PYEOF
