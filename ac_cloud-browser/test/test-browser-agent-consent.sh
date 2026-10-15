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
from browser_tester import LIB, APP, kotlin, build_json, main, strip

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
    # #823 the side panel replaces the dialog: the consent card is the one place a waiting action is decided.
    # The panel and its consent card are libs:ai's (AiPanel.kt, AiPage.kt); the app installs them (AgentPanelScreen.kt).
    aipanel = strip(open(os.path.join(root, AIP), encoding="utf-8").read())
    aipage = strip(open(os.path.join(root, AIPAGE), encoding="utf-8").read())
    screen = src.get(os.path.join(APP, "search", "AgentPanelScreen.kt"), "")
    ok("agentState.pending = AgentPending(callId, sentence)" in frag, "a waiting action puts the consent card in the side panel")
    ok("BrowserAgentPanelHost.panel ?: return" in frag and "AiAssistantPanel(" in screen and "BrowserAgentPanelHost.panel =" in screen,
       "the side panel is libs:ai's, shown by the host through its seam")
    ok(re.findall(r"onDecide\((true|false)\)", aipanel) == ["true", "false"]
       and "OutlinedButton(onDeny, Modifier.testTag(denyTag)" in aipage and "Button(onAllow, Modifier.testTag(allowTag)" in aipage
       and screen.count("actions.onDecide(") == 1 and 'deny = "browser:agent:deny"' in screen and 'allow = "browser:agent:allow"' in screen,
       "the card's Allow and Deny are the only decisions")
    ok(frag.count("BrowserAgentHost.decide") == 1 and "decide(pend.callId, allow)" in frag, "the host decides only from the card")
    ok("enabled = !busy && pending == null) { Text(s.send) }" in aipanel, "nothing new is sent while an action waits")
    sect = frag[frag.find("private fun showAgentChat("):frag.find("private fun runAgentTool(")]
    ok("AlertDialog" not in sect, "the assistant draws no dialog: it is the Compose side panel")

AIP = "ab_cloud-libs-shared/libs/ai/src/main/kotlin/com/diegonmarcos/superapp/ai/AiPanel.kt"
AIPAGE = "ab_cloud-libs-shared/libs/ai/src/main/kotlin/com/diegonmarcos/superapp/ai/AiPage.kt"
BJ = "ac_cloud-browser/build.json"
LIBP = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/"
APPP = "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/"
main("agent consent", check, [
    ("click stops asking", BJ, '"Click the first element matching a CSS selector on the open page.",\n              "params": {\n                "css": {\n                  "type": "string",\n                  "description": "CSS selector"\n                }\n              },\n              "mutating": true,\n              "confirm": true', '"Click the first element matching a CSS selector on the open page.",\n              "params": {\n                "css": {\n                  "type": "string",\n                  "description": "CSS selector"\n                }\n              },\n              "mutating": true,\n              "confirm": false', "`click` is confirm:true"),
    ("a confirm tool runs undecided", LIBP + "BrowserAgent.kt", "null -> return Outcome.Pending(", "null -> run(c).also { Outcome.Pending(", "stops the turn"),
    ("a tool reads the profile", LIBP + "BrowserHostFragment.kt", '"list_tabs" -> done(', '"list_tabs" -> done(JSONObject().put("p", BrowserProfileStore(requireContext()).load().toString())); "x_list" -> done(', "reads no profile"),
    ("the API can allow", APPP + "debugapi/BrowserDebugApi.kt", 'else -> need(q["text"].orEmpty(), "text") ?: r.ask(', 'else -> if (q["allow"] == "1") r.decide(q["call"].orEmpty(), AgentLoop.Decision.ALLOW == AgentLoop.Decision.ALLOW) else need(q["text"].orEmpty(), "text") ?: r.ask(', "never decides"),
    ("the panel allows on Deny", AIP, "onDeny = { onDecide(false) }", "onDeny = { onDecide(true) }", "the only decisions"),
    ("the card's Deny button runs Allow", AIPAGE, "OutlinedButton(onDeny, Modifier.testTag(denyTag)", "OutlinedButton(onAllow, Modifier.testTag(denyTag)", "the only decisions"),
    ("the side panel is drawn by the browser again", APPP + "search/AgentPanelScreen.kt", "AiAssistantPanel(", "OwnPanel(", "the side panel is libs:ai's"),
    ("the host allows without the card", LIBP + "BrowserHostFragment.kt", "if (decide != null) agentRun {", "BrowserAgentHost.decide?.invoke(\"x\", true); if (decide != null) agentRun {", "decides only from the card"),
    ("send while an action waits", AIP, "enabled = !busy && pending == null) { Text(s.send) }", "enabled = !busy) { Text(s.send) }", "nothing new is sent"),
    ("the scrape tool loses its branch", LIBP + "BrowserHostFragment.kt", '            "scrape" -> {\n                wv ?: return needPage()\n                val sc = config.addons["scraper"]', '            "scrape_x" -> {\n                wv ?: return needPage()\n                val sc = config.addons["scraper"]', "`scrape` has its runAgentTool"),
])
PYEOF
