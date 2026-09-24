// Driver for test/test-cloud-nav.sh (#562). Loads the REAL src/cloud/tabs.js
// (no copy) and the REAL nav.json, renders the bar model through them, and
// prints one `CHECK <ok|bad> <text>` line per assertion; the shell decides.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);

const tabs = await import(
	`data:text/javascript,${encodeURIComponent(read("src/cloud/tabs.js"))}`
);
const nav = JSON.parse(read("src/cloud/nav.json"));
const index = read("src/cloud/index.js");

// The renderers index.js actually has — read from its VIEWS table, not restated.
const viewsBlock = /const VIEWS = \{([\s\S]*?)\n\};/.exec(index);
const views = viewsBlock
	? [...viewsBlock[1].matchAll(/^\s*(\w+)\s*:/gm)].map((m) => m[1])
	: [];
out(views.length > 0, `index.js VIEWS table read: ${views.join(",")}`);

let model = [];
try {
	model = tabs.buildTabs(nav, views);
	out(true, `bar renders ${model.length} tabs`);
} catch (e) {
	out(false, `buildTabs refused the declaration: ${e.message}`);
}

// #562's order is the requirement; the RENDERED labels are what is compared.
const required = "Backlog|Editor|Repos|Home|Agents|Browser|MyTerminal";
const rendered = model.map((t) => t.label).join("|");
out(rendered === required, `rendered order ${rendered} (required ${required})`);

// Every icon is a class that exists in Acode's own icon font.
const css = read("src/res/icons/style.css");
for (const t of model) {
	const has = new RegExp(`\\.icon\\.${t.icon.replace(/[-]/g, "\\-")}\\s*:{1,2}before`).test(css);
	out(has, `tab ${t.id} icon '${t.icon}' exists in src/res/icons/style.css`);
}

// Measured fit: the declared narrowest viewport still gives every tab the
// touch floor, and the device-side verdict goes compact on overflow, never clips.
const w = tabs.tabWidth(nav.nav.min_viewport_px, model.length);
out(tabs.clearsTouchFloor(nav), `each tab is ${w.toFixed(1)} px at ${nav.nav.min_viewport_px} px, floor ${nav.nav.min_touch_target_px} px`);
out(tabs.fitLabels([{ labelPx: 40, tabPx: 51 }, { labelPx: 50, tabPx: 51 }]) === "full", "labels that fit keep full mode");
out(tabs.fitLabels([{ labelPx: 40, tabPx: 51 }, { labelPx: 56, tabPx: 51 }]) === "compact", "one overflowing label switches the bar to compact");

// Selection semantics, through the real select().
const byId = Object.fromEntries(model.map((t) => [t.id, t]));
const s0 = { active: nav.default_tab, showPanel: null };
const toBacklog = tabs.select(s0, byId.backlog, ["myterminal"]);
out(toBacklog.active === "backlog" && toBacklog.showPanel === "backlog", "Backlog shows its panel");
const toEditor = tabs.select(toBacklog, byId.editor, ["myterminal"]);
out(toEditor.active === "editor" && toEditor.showPanel === null, "Editor hides every cloud panel (Acode untouched)");
const toTerm = tabs.select(toBacklog, byId.myterminal, ["myterminal"]);
out(toTerm.launch === "myterminal" && toTerm.active === "backlog", "MyTerminal launches out and keeps the current tab");
out(new RegExp(`LAUNCH_VIEWS = \\[\\s*"myterminal"\\s*\\]`).test(index), "index.js treats myterminal as the launch view");
