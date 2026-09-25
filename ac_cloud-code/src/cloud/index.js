// cloud-code — the 7-tab bottom nav over Acode (#562).
//
// WHY THIS IS NOT THE FLEET'S COMPOSE NAV. #565 made ab_cloud-libs-shared
// libs:bottomnav the one bottom nav for NATIVE apps. This app is a Cordova web
// shell: its whole UI is DOM inside one WebView, so a Compose view cannot sit in
// it without a second Android window stacked over the editor. The declared
// exception is therefore to build the bar in Acode's own UI layer — its theme
// variables, its icon font, its fileSystem / markdown / browser / openFolder —
// so it looks and behaves like the rest of Acode. It keeps #565's behaviour
// rule: a label is never truncated (see tabs.js::fitLabels).
//
// Mounted from main.js::loadApp with ONE call (search "cloud-code seam").
// Editor = Acode untouched: selecting it only hides these panels.

import "./cloud.scss";
import fsOperation from "fileSystem";
import toast from "components/toast";
import openFolder from "lib/openFolder";
import openFile from "lib/openFile";
import browser from "plugins/browser";
import markdownIt from "markdown-it";
import DOMPurify from "dompurify";
import nav from "./nav.json";
import targets from "./targets.gen.json";
import { buildTabs, fitLabels, select } from "./tabs";
import { homeStatus, listAgents, listRepos } from "./seams";

const LAUNCH_VIEWS = ["myterminal"];

function el(name, props = {}, ...children) {
	const node = document.createElement(name);
	for (const [k, v] of Object.entries(props)) {
		if (k === "className") node.className = v;
		else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
		else node.setAttribute(k, v);
	}
	for (const c of children.flat()) {
		if (c != null) node.append(c instanceof Node ? c : String(c));
	}
	return node;
}

function stored(key, fallback) {
	return localStorage.getItem(`cloud-code.${key}`) || fallback;
}

function store(key, value) {
	localStorage.setItem(`cloud-code.${key}`, value);
}

function panelHeader(title, ...actions) {
	return el("div", { className: "cloud-panel-header" }, el("h2", {}, title), ...actions);
}

// ── #575 THE ONE shared repo store ──────────────────────────────────────────
// cloud-drive declares the root (its build.json::storage.shared_root); the build
// copied it into targets.gen.json RELATIVE, and only the device knows where
// shared storage is. Every on-device path this app touches is composed here.
function sharedRoot() {
	return `${cordova.file.externalRootDirectory}${targets.shared_root}/`;
}

function storePath(relative) {
	return sharedRoot() + relative;
}

// ── Backlog: render the ONE existing source, as-is ─────────────────────────
// Backlog and Agents are two entry files of the same backlog dist, read from
// the same folder (one localStorage key), through this one renderer.
async function renderBacklogView(panel, title, entry, ...extra) {
	const md = markdownIt({ html: false, linkify: true });
	let dir = stored("backlog.dir", storePath(nav.backlog.source_dir));
	let current = entry;
	const body = el("div", { className: "cloud-md" });

	async function load(file) {
		body.textContent = `Loading ${file} …`;
		current = file;
		try {
			const text = await fsOperation(dir, file).readFile("utf8");
			body.innerHTML = DOMPurify.sanitize(md.render(text));
		} catch (err) {
			// #575 the store is shared storage: on Android 11+ this app reads it only
			// with all-files access, which the system grants per app, on a toggle.
			const grant = el("button", {
				className: "cloud-row",
				onclick: () => system.manageAllFiles(() => load(file), (e) => toast(String(e))),
			}, el("span", { className: "icon folder_open" }), "Allow access to all files, then retry");
			body.replaceChildren(
				el("p", {}, `Cannot read ${dir}${file}`),
				el("p", { className: "cloud-muted" }, String(err?.message || err)),
				el("p", { className: "cloud-muted" }, "The backlog repository is private: clone it with Cloud Drive (Sync ▸ Git ▸ cloud-data-my-ai-memory ▸ Clone / open) and it appears here through the shared store. Or point this tab at another 1.1.Product-Backlog/dist/ folder."),
				grant,
			);
		}
	}

	// The dist files link to each other ("Views: per project · per status …"):
	// follow those links in place instead of re-deriving a view list here.
	body.addEventListener("click", (e) => {
		const a = e.target.closest("a");
		if (!a) return;
		const href = a.getAttribute("href") || "";
		e.preventDefault();
		if (/^[\w./-]+\.md(#.*)?$/.test(href)) load(href.replace(/#.*$/, ""));
		else if (/^https?:/.test(href)) browser.open(href);
	});

	const change = el("button", {
		className: "icon folder_open",
		title: "Backlog folder",
		onclick: async () => {
			const { default: prompt } = await import("dialogs/prompt");
			const next = await prompt("Backlog dist folder (URL)", dir, "text");
			if (!next) return;
			dir = next.endsWith("/") ? next : `${next}/`;
			store("backlog.dir", dir);
			load(entry);
		},
	});
	// #575 WRITE goes through the same store: the file on screen opens in Acode's
	// editor (saved back in place), and the whole repository opens as a folder in
	// the sidebar. cloud-drive's git manager commits and pushes what is edited.
	const repoName = nav.backlog.source_dir.split("/")[0];
	const edit = el("button", {
		className: "icon edit",
		title: "Edit this file",
		onclick: async () => {
			await openFile(`${dir}${current}`, { render: true });
			showTab("editor");
		},
	});
	const repo = el("button", {
		className: "icon git",
		title: "Open the repository",
		onclick: () => {
			openFolder(storePath(repoName), { name: repoName });
			showTab("editor");
		},
	});
	panel.replaceChildren(panelHeader(title, edit, repo, change), body, ...extra);
	await load(entry);
}

function renderBacklog(panel) {
	return renderBacklogView(panel, "Backlog", nav.backlog.entry);
}

// ── Repos: small engine behind the seam ────────────────────────────────────
async function renderRepos(panel) {
	const root = stored("repos.root", storePath(nav.repos.root));
	const list = el("div", { className: "cloud-list" }, "Scanning …");
	panel.replaceChildren(panelHeader("Repos"), el("p", { className: "cloud-muted" }, root), list);
	try {
		const { items } = await listRepos(root);
		list.replaceChildren(
			...(items.length
				? items.map((r) =>
						el("button", {
							className: "cloud-row",
							onclick: () => {
								openFolder(r.url, { name: r.name });
								showTab("editor");
							},
						}, el("span", { className: "icon git" }), r.name),
					)
				: [el("p", { className: "cloud-muted" }, "No git repositories under this folder.")]),
		);
	} catch (err) {
		list.replaceChildren(el("p", {}, `Cannot list ${root}: ${err?.message || err}`));
	}
}

// ── Agents: the backlog's own agent views, plus the live seam ──────────────
// Assignment, model and batch come from the backlog dist (one source with the
// Backlog tab); running status and token use are the seam's, stubbed for now.
async function renderAgents(panel) {
	const { items, note } = await listAgents();
	const live = items.length
		? el("div", { className: "cloud-list" }, items.map((a) => el("div", { className: "cloud-row" }, a.name)))
		: el("p", { className: "cloud-muted" }, note);
	await renderBacklogView(panel, "Agents", nav.agents.entry, live);
}

// ── Home: one card per other tab, plus whatever the seam reports ───────────
async function renderHome(panel) {
	const { cards } = await homeStatus();
	const tiles = tabs
		.filter((t) => t.view !== "home")
		.map((t) =>
			el("button", { className: "cloud-card", onclick: () => showTab(t.id) },
				el("span", { className: `icon ${t.icon}` }),
				el("span", {}, t.label),
			),
		);
	panel.replaceChildren(
		panelHeader("Home"),
		el("div", { className: "cloud-grid" }, tiles, cards.map((c) => el("div", { className: "cloud-card" }, c.title))),
	);
}

// ── Browser: local pages in Acode's own browser plugin ─────────────────────
async function renderBrowser(panel) {
	const input = el("input", { type: "url", value: stored("browser.last", nav.browser.presets[0].url) });
	const open = (url) => {
		store("browser.last", url);
		browser.open(url);
	};
	panel.replaceChildren(
		panelHeader("Browser"),
		el("div", { className: "cloud-row" }, input, el("button", { className: "icon open_in_browser", onclick: () => open(input.value) })),
		el("div", { className: "cloud-list" },
			nav.browser.presets.map((p) => el("button", { className: "cloud-row", onclick: () => open(p.url) }, el("span", { className: "icon public" }), p.label)),
		),
	);
}

// ── MyTerminal: link out to the fleet app nav.json names ───────────────────
function launchMyTerminal() {
	const t = targets.myterminal;
	system.launchApp(t.package, t.activity, null, () => {}, () =>
		toast(`${t.id} is not installed (${t.package})`),
	);
}

const VIEWS = {
	editor: null,
	backlog: renderBacklog,
	repos: renderRepos,
	home: renderHome,
	agents: renderAgents,
	browser: renderBrowser,
	myterminal: null,
};

const LAUNCH = { myterminal: launchMyTerminal };

let tabs = [];
let state = { active: nav.default_tab, showPanel: null };
const panels = {};
const buttons = {};
let $bar;

function showTab(id) {
	const tab = tabs.find((t) => t.id === id);
	const next = select(state, tab, LAUNCH_VIEWS);
	if (next.launch) {
		LAUNCH[tab.view]();
		return;
	}
	state = next;
	for (const [tid, b] of Object.entries(buttons)) b.classList.toggle("active", tid === state.active);
	for (const [tid, p] of Object.entries(panels)) p.hidden = tid !== state.showPanel;
	if (state.showPanel) {
		const view = VIEWS[tab.view];
		view(panels[tab.id]).catch((err) => toast(String(err?.message || err)));
	}
	fit();
}

function fit() {
	// Measure in full mode (compact hides labels, which would measure 0), then
	// decide; compact is only ever the device's verdict.
	$bar.classList.remove("compact");
	const measured = Object.values(buttons).map((b) => {
		const label = b.querySelector(".cloud-tab-label");
		return { labelPx: label.scrollWidth, tabPx: b.clientWidth };
	});
	$bar.classList.toggle("compact", fitLabels(measured) === "compact");
}

export default function mount() {
	tabs = buildTabs(nav, Object.keys(VIEWS));
	document.body.classList.add("cloud-nav");
	document.documentElement.style.setProperty("--cloud-nav-h", `${nav.nav.height_px}px`);
	document.documentElement.style.setProperty("--cloud-nav-label", `${nav.nav.label_font_px}px`);
	document.documentElement.style.setProperty("--cloud-nav-icon", `${nav.nav.icon_font_px}px`);
	$bar = el("nav", { id: "cloud-nav", role: "tablist" });
	for (const t of tabs) {
		buttons[t.id] = el("button", { className: "cloud-tab", role: "tab", "data-tab": t.id, onclick: () => showTab(t.id) },
			el("span", { className: `icon ${t.icon}` }),
			el("span", { className: "cloud-tab-label" }, t.label),
		);
		$bar.append(buttons[t.id]);
		if (VIEWS[t.view]) {
			panels[t.id] = el("section", { className: "cloud-panel", "data-tab": t.id });
			panels[t.id].hidden = true;
			document.body.append(panels[t.id]);
		}
	}
	document.body.append($bar);
	window.addEventListener("resize", fit);
	showTab(nav.default_tab);
}
