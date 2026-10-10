// cloud-code Chat — the page (replaced Home). Cloud Search's chat without the search boxes: one
// message list, streamed replies, one composer. Left of the Model button: + (files, photos, voice),
// Agent, Effort, permission Mode, MCP and More. Every decision is model.js's; the network, the
// token and the attachments are the CloudChat plugin's (src/plugins/cloudchat). Nothing here ever
// holds the OpenRouter token: Profile & Config hands it straight to the plugin.
import markdownIt from "markdown-it";
import DOMPurify from "dompurify";
import toast from "components/toast";
import nav from "../nav.json";
import targets from "../targets.gen.json";
import { el, icon } from "../dom";
import { option, sheet, toggleRow, page, closeOverlay } from "../chrome";
import * as M from "./model";

const chat = nav.chat;
const fleet = targets.chat;
const md = markdownIt({ html: false, linkify: true, breaks: true });
const REASONING_TTL = 24 * 3600 * 1000;

const S = {
	sessions: [],
	current: null,
	// undefined = the gateway not asked yet, null = it did not answer (model.js::resolveAgents)
	health: undefined,
	mcpLive: null,
	reasoning: null,
	streaming: null,
	pending: [],
	recording: false,
	panel: null,
	chrome: null,
	catalogue: null,
};

const plugin = () => window.CloudChat;

function persist() {
	M.saveSessions(localStorage, S.sessions);
}

function current() {
	if (!S.current) {
		S.sessions = M.loadSessions(localStorage);
		const settings = M.loadSettings(localStorage, chat);
		S.current = S.sessions.sort((a, b) => b.updated - a.updated)[0] || fresh(settings);
	}
	return S.current;
}

function fresh(settings = M.loadSettings(localStorage, chat)) {
	const s = M.newSession(Date.now(), settings.agent);
	Object.assign(s, { model: settings.model, effort: settings.effort, mode: settings.mode });
	return s;
}

function agents() {
	return M.resolveAgents(chat, fleet, S.health);
}

function agent() {
	const s = current();
	const a = M.pickAgent(agents(), s.agent);
	if (a && a.id !== s.agent) s.agent = a.id;
	return a;
}

/** The chat model kept for this agent (the Model page saves it per agent). */
function modelOf(a) {
	return M.agentModel(localStorage, chat, a?.id);
}

// ── live data: the gateway's /health and MCP status, OpenRouter's reasoning models ──

async function getJson(url, auth) {
	const r = await plugin().get(url, {}, auth);
	if (r.status < 200 || r.status >= 300) throw new Error(`HTTP ${r.status}`);
	return JSON.parse(r.body);
}

async function refreshLive() {
	if (!plugin()) return;
	const g = fleet.gateway;
	const [health, mcp] = await Promise.allSettled([getJson(g + chat.gateway.health_path), getJson(g + chat.gateway.mcp_path)]);
	S.health = health.status === "fulfilled" ? health.value : null;
	S.mcpLive = mcp.status === "fulfilled" && Array.isArray(mcp.value) ? mcp.value : null;
	S.reasoning = await reasoningIds();
	draw();
}

async function reasoningIds() {
	try {
		const cached = JSON.parse(localStorage.getItem(`${M.KEY}reasoning`) || "null");
		if (cached && Date.now() - cached.at < REASONING_TTL) return new Set(cached.ids);
		const r = await plugin().get(chat.direct.models_url, {});
		const ids = M.reasoningIds(r.body);
		if (ids) localStorage.setItem(`${M.KEY}reasoning`, JSON.stringify({ at: Date.now(), ids: [...ids] }));
		return ids;
	} catch {
		return null;
	}
}

// ── the page ────────────────────────────────────────────────────────────────

let $list;
let $input;
let $attach;
let $controls;
let $send;

export async function renderChat(panel, chrome) {
	S.panel = panel;
	S.chrome = chrome;
	current();
	$list = el("div", { className: "cloud-chat-list" });
	$attach = el("div", { className: "cloud-chat-attach" });
	$input = el("textarea", { className: "cloud-chat-input", rows: "1", placeholder: "Message" });
	$input.addEventListener("input", () => {
		$input.style.height = "auto";
		$input.style.height = `${Math.min($input.scrollHeight, 160)}px`;
	});
	$send = el("button", { className: "cloud-chat-send", title: "Send", onclick: () => (S.streaming ? stop() : send()) }, icon("send"));
	$controls = el("div", { className: "cloud-chat-controls" });
	const composer = el("div", { className: "cloud-chat-composer" }, $attach, el("div", { className: "cloud-chat-row" }, $input, $send), $controls);
	panel.classList.add("cloud-chat-panel");
	panel.replaceChildren(chrome.bar("Chat"), $list, composer);
	draw();
	refreshLive().catch(() => {});
}

function draw() {
	if (!S.panel) return;
	const s = current();
	const a = agent();
	S.panel.querySelector(".cloud-topbar")?.replaceWith(S.chrome.bar(a ? a.label : "Chat", M.title(s)));
	drawMessages();
	drawControls();
}

function drawMessages() {
	const s = current();
	if (!s.messages.length) {
		const a = agent();
		$list.replaceChildren(el("div", { className: "cloud-chat-empty" },
			el("div", { className: "cloud-chat-hello" }, "How can I help?"),
			el("div", { className: "cloud-muted" }, a ? `${a.label} · ${M.modelControl(a, modelOf(a), S.health).label}` : "No agent can be reached")));
		return;
	}
	$list.replaceChildren(...s.messages.map(bubble));
	$list.scrollTop = $list.scrollHeight;
}

function bubble(m) {
	const chips = (m.attachments || []).map((f) => el("span", { className: "cloud-chip" }, icon(chipIcon(f.mime)), f.name));
	if (m.role === "user") return el("div", { className: "cloud-msg user" }, el("div", { className: "cloud-msg-body" }, m.content), chips.length ? el("div", { className: "cloud-chips" }, chips) : null);
	const body = el("div", { className: "cloud-msg-body cloud-md" });
	body.innerHTML = DOMPurify.sanitize(md.render(m.content || (m.streaming ? "…" : "")));
	const meta = el("div", { className: "cloud-msg-meta cloud-muted" }, [m.agent, m.model].filter(Boolean).join(" · "), m.error ? ` · ${m.error}` : "");
	return el("div", { className: `cloud-msg assistant${m.error ? " error" : ""}` }, body, meta);
}

function chipIcon(mime = "") {
	if (mime.startsWith("image/")) return "image";
	if (mime.startsWith("audio/")) return "keyboard_voice";
	if (mime.startsWith("video/")) return "videocam";
	return "attach_file";
}

// ── the composer controls (all left of Model) ───────────────────────────────

function chip(label, iconName, onclick, { disabled, title, role } = {}) {
	return el("button", { className: "cloud-ctl", disabled: !!disabled, title: title || label, "data-ctl": role, onclick }, icon(iconName), label ? el("span", {}, label) : null);
}

function drawControls() {
	const s = current();
	const a = agent();
	// model.js::controlOrder is the one order: + · Agent · Model · Effort · Mode · MCP · More, and
	// nothing after More.
	const eff = a ? M.effortControl(a, M.modelControl(a, modelOf(a), S.health).value, S.reasoning) : null;
	const perm = a ? M.permissionControl(a) : null;
	const build = {
		attach: () => chip("", "add", attachSheet, { title: "Attach files, photos or a voice message", role: "attach" }),
		agent: () => chip(a ? a.label : "Agent", "brain", agentSheet, { role: "agent" }),
		model: () => {
			const mc = M.modelControl(a, modelOf(a), S.health);
			return chip(shortModel(mc.label), "wand-sparkles", () => modelPage(), { title: mc.pick ? `Model: ${mc.label}` : `Model ${mc.label} (set by ${a.label})`, role: "model" });
		},
		effort: () => {
			const lvl = chat.effort.levels.find((l) => l.id === s.effort) || chat.effort.levels[0];
			return chip(lvl.label, "tune", effortSheet, { disabled: !eff.enabled, title: eff.enabled ? "Effort" : eff.reason, role: "effort" });
		},
		mode: () => {
			const mode = chat.permission.modes.find((m) => m.id === s.mode) || chat.permission.modes[0];
			return chip(mode.short, mode.id === "plan" ? "notes" : mode.id === "accept" ? "edit" : "zap", modeSheet, { title: mode.label, role: "mode" });
		},
		mcp: () => chip("MCP", "extension", mcpSheet, { role: "mcp" }),
		more: () => chip("", "more_vert", moreSheet, { title: "More", role: "more" }),
	};
	const ctl = M.controlOrder(a, { effort: !!eff?.shown, permission: !!perm?.shown }).map((id) => build[id]());
	$controls.replaceChildren(...ctl);
	$attach.replaceChildren(...S.pending.map((f, i) => el("span", { className: "cloud-chip" }, icon(chipIcon(f.mime)), f.name,
		el("button", { className: "cloud-chip-x", title: "Remove", onclick: () => { S.pending.splice(i, 1); drawControls(); } }, icon("clearclose")))));
	if (S.recording) $attach.append(el("span", { className: "cloud-chip recording" }, icon("keyboard_voice"), "Recording… tap + to stop"));
}

function shortModel(label) {
	const t = String(label || "").split("/").pop();
	return t.length > 22 ? `${t.slice(0, 21)}…` : t;
}

function agentSheet() {
	const s = current();
	const rows = agents().map((a) => option(a.label, {
		checked: a.id === s.agent, disabled: !a.available,
		sub: M.agentSub(a),
		run: () => { s.agent = a.id; persist(); draw(); },
	}));
	sheet("Agent", rows, S.health === null
		? "The gateway did not answer: the fleet's declared agents are shown, marked offline."
		: "From the fleet: the agents the C3 catalogue declares and the gateway's /health serves now.");
}

function effortSheet() {
	const s = current();
	sheet("Effort", chat.effort.levels.map((l) => option(l.label, { checked: l.id === s.effort, sub: `reasoning.effort = ${l.value}`, run: () => { s.effort = l.id; persist(); drawControls(); } })));
}

function modeSheet() {
	const s = current();
	const a = agent();
	const perm = M.permissionControl(a);
	const note = perm.via === "prompt" ? `${a.label} has no per-request approval switch: the mode is sent as an instruction.` : null;
	sheet("Permission mode", chat.permission.modes.map((m) => option(m.label, { checked: m.id === s.mode, run: () => { s.mode = m.id; persist(); drawControls(); } })), note);
}

function mcpSheet() {
	const a = agent();
	const { source, servers } = M.mcpServers(a, S.mcpLive, fleet.mcp_fleet);
	const state = M.mcpState(localStorage, a.id, servers);
	const note = source === "none" ? `${a.label} has no MCP servers.`
		: source === "gateway" ? "Live from the gateway (/v1/mcp/status). Saved per agent; the gateway has no per-request server switch yet, so this list is advisory there."
		: "From the fleet MCP config (the derived .mcp.json). Saved per agent.";
	sheet(`MCP · ${a.label}`, servers.map((sv) => toggleRow(sv.name, state[sv.name], (on) => M.mcpToggle(localStorage, a.id, sv.name, on), sv.tools != null ? `${sv.tools} tools` : null)), note);
}

function moreSheet() {
	const a = agent();
	const saved = M.toggles(localStorage, a.id);
	const rows = M.moreFor(chat, a, S.health).map((f) => {
		if (f.kind === "toggle") return toggleRow(f.label, saved[f.id] ?? !!S.health?.plugins?.[f.plugin], (on) => M.setToggle(localStorage, a.id, f.id, on), f.header);
		if (f.kind === "select") return option(`${f.label}: ${saved[f.id] ?? "server default"}`, { sub: f.header, run: () => selectValue(a, f) });
		return option(f.label, { sub: f.offline ? "gateway offline" : f.kind === "native" ? `${a.label}, live: ${f.path}` : f.route ? chat.gateway[f.route] : null, run: () => invoke(a, f) });
	});
	sheet(`More · ${a.label}`, rows, S.health ? null : "The gateway did not answer: the declared list is shown.");
}

function selectValue(a, f) {
	sheet(f.label, f.values.map((v) => option(v, { run: () => { M.setToggle(localStorage, a.id, f.id, v); } })));
}

async function invoke(a, f) {
	const s = current();
	try {
		if (f.kind === "prompt") {
			$input.value = f.prompt;
			return send();
		}
		if (f.kind === "client") {
			if (f.id === "retry") return retry();
			if (f.id === "undo") {
				s.messages.splice(Math.max(0, s.messages.map((m) => m.role).lastIndexOf("user")));
				persist();
				return drawMessages();
			}
			if (f.id === "catalogue") return modelPage(true);
		}
		if (f.kind === "login") return claudeLogin();
		if (f.kind === "native") return nativeFunction(a, f);
		const url = fleet.gateway + chat.gateway[f.route];
		if (f.kind === "tools") {
			const { default: prompt } = await import("dialogs/prompt");
			const q = await prompt("Search the agent's tools", "", "search");
			if (q == null) return;
			const hits = await getJson(url + encodeURIComponent(q));
			return sheet(`Tools · ${q}`, (Array.isArray(hits) ? hits : []).map((t) => option(t.name, { sub: t.description })), Array.isArray(hits) && hits.length ? null : "No tool matched.");
		}
		if (f.kind === "sessions") return gatewaySessions(url);
		const info = await getJson(url);
		page(f.label, null, el("pre", { className: "cloud-pre" }, JSON.stringify(info, null, 2)));
	} catch (e) {
		toast(`${f.label}: ${e?.message || e}`);
	}
}

/** One of an agent's own functions, served by the gateway (GET /agents/<agent>/<fn>). */
async function nativeFunction(a, f) {
	const j = await getJson(fleet.gateway + f.path);
	const list = Array.isArray(j) ? j : Array.isArray(j?.data) ? j.data : Array.isArray(j?.sessions) ? j.sessions : Array.isArray(j?.jobs) ? j.jobs : null;
	if (list) {
		const rows = list.map((it) => option(String(it.name || it.title || it.id || "item"), { sub: [it.description, it.category, it.schedule, it.updated_at || it.created_at].filter(Boolean).join(" · ") || null }));
		return sheet(`${f.label} · ${a.label}`, rows, rows.length ? null : "Nothing listed.");
	}
	page(`${f.label} · ${a.label}`, null, el("pre", { className: "cloud-pre" }, JSON.stringify(j, null, 2)));
}

async function gatewaySessions(url) {
	const list = await getJson(url);
	const rows = (Array.isArray(list) ? list : []).map((r) => option(r.name || r.id, {
		sub: `${r.device} · ${new Date(r.mtime).toLocaleString()}`,
		run: async () => {
			try {
				const res = await plugin().get(`${url}/${encodeURIComponent(r.device)}/${encodeURIComponent(r.id)}?tail=65536`, {});
				const s = fresh();
				s.title = r.name || r.id;
				s.messages = res.body.split("\n").map((l) => { try { return JSON.parse(l); } catch { return null; } })
					.filter((o) => o && (o.role === "user" || o.role === "assistant") && typeof o.content === "string")
					.map((o) => ({ role: o.role, content: o.content }));
				S.sessions.unshift(s);
				S.current = s;
				persist();
				draw();
			} catch (e) {
				toast(String(e?.message || e));
			}
		},
	}));
	sheet("Gateway sessions", rows, rows.length ? "Tap one to continue it here." : "No session in the store.");
}

async function claudeLogin() {
	const base = fleet.claude_api;
	const r = await plugin().post(`${base}/auth/login/start`, "{}");
	const j = JSON.parse(r.body || "{}");
	if (!j.url) throw new Error(j.error || `HTTP ${r.status}`);
	system.openInBrowser(j.url);
	const { default: prompt } = await import("dialogs/prompt");
	const code = await prompt("Paste the code from the login page", "", "text");
	if (!code) return;
	const done = await plugin().post(`${base}/auth/login/code`, JSON.stringify({ code }));
	toast(JSON.parse(done.body || "{}").message || `HTTP ${done.status}`);
}

// ── + : files, photos / media, a voice message ──────────────────────────────

function attachSheet() {
	if (S.recording) return stopRecording();
	sheet("Attach", [
		option("Photos & videos", { iconName: "image", sub: "Android photo picker", run: () => pick("media") }),
		option("Files", { iconName: "attach_file", sub: "any file, through the system picker", run: () => pick("file") }),
		option("Voice message", { iconName: "keyboard_voice", sub: "record, then send as audio (no speech-to-text in this app)", run: startRecording }),
	]);
}

async function pick(kind) {
	try {
		const files = await plugin().pick(kind, true);
		for (const f of files) {
			if (f.error) toast(`${f.name}: ${f.error}`);
			else S.pending.push(f);
		}
		drawControls();
	} catch (e) {
		toast(String(e?.message || e));
	}
}

async function startRecording() {
	try {
		await plugin().recordStart();
		S.recording = true;
		drawControls();
	} catch (e) {
		toast(String(e?.message || e));
	}
}

async function stopRecording() {
	try {
		const a = await plugin().recordStop();
		S.recording = false;
		S.pending.push({ ...a, name: `voice-${Math.round(a.duration_ms / 1000)}s.wav` });
		drawControls();
	} catch (e) {
		S.recording = false;
		toast(String(e?.message || e));
		drawControls();
	}
}

// ── the Model page: libs:model-catalogue, the four Text tables (A0–A3) ──────

/**
 * A full page, not a sheet: Cloud Search's chat model page drawn in HTML from the same lib data
 * (this WebView app cannot run the Compose screen). One radio per row, exactly one selected;
 * picking saves the model for the CURRENT agent (model.js::setAgentModel) and returns to Chat,
 * Back closes without change. Rows the agent cannot run are greyed with the reason
 * (model.js::rowState). [onPick] (Profile & Config's default model) takes any selectable row.
 */
export async function modelPage(refresh = false, onPick = null) {
	const a = agent();
	const chosen = onPick ? M.loadSettings(localStorage, chat).model : modelOf(a);
	const body = el("div", { className: "cloud-cat" }, el("p", { className: "cloud-muted" }, "Loading the catalogue…"));
	const pg = page(onPick ? "Default model" : `Model · ${a ? a.label : "Chat"}`, null, body);
	pg.node.classList.add("cloud-model-page");
	const choose = (row) => {
		if (onPick) onPick(row.id);
		else {
			M.setAgentModel(localStorage, a.id, row.id);
			draw();
		}
		pg.close();
	};
	const show = (cat) => {
		const mc = a ? M.modelControl(a, chosen, S.health) : { pick: true, label: chosen };
		const head = onPick || mc.pick
			? `Selected: ${chosen}`
			: `${a.label} runs ${mc.value || "its own model"}: the rows are shown for reference.`;
		body.replaceChildren(
			el("p", { className: "cloud-cat-current" }, head),
			el("p", { className: "cloud-muted" }, `Prices as of ${cat.as_of} (${cat.origin})`),
			...cat.groups.flatMap((g) => g.sections.map((sec) => section(sec, M.checkedRow(cat.groups.flatMap((x) => x.sections), chosen)))),
			el("p", { className: "cloud-muted" }, "The fleet's model catalogue (libs:model-catalogue, shared with Cloud Search): A0 Code, A1 Agentic, A2 Base, A3 Reasoning. Anthropic first, then providers by price. Prices are OpenRouter's, read at most daily."),
		);
		body.querySelector("input[type=radio]:checked")?.closest("tr")?.scrollIntoView?.({ block: "center" });
	};
	try {
		show(await plugin().catalogue(chat.catalogue.sections, false));
		const live = await plugin().catalogue(chat.catalogue.sections, true);
		if (pg.node.isConnected) show(live);
		if (refresh) toast(`Prices: ${live.origin}, as of ${live.as_of}`);
	} catch (e) {
		body.replaceChildren(el("p", {}, `The catalogue could not be read: ${e?.message || e}`));
	}

	function section(sec, checked) {
		const cols = ["", "Provider", "Model", "OpenRouter ID", "Params", "License", "Input $/1M", "Output $/1M (Floor)"];
		const rows = sec.rows.map((r) => {
			const st = onPick ? { allowed: !!r.selectable, reason: r.selectable ? "" : "not on OpenRouter" } : M.rowState(a, r, S.health);
			const radio = el("input", { type: "radio", name: "cloud-model", value: r.id, checked: checked === `${sec.id}:${r.id}`, disabled: !st.allowed, "aria-label": `${r.provider} ${r.name}` });
			radio.addEventListener("change", () => choose(r));
			radio.addEventListener("click", (e) => e.stopPropagation()); // the row's own tap would pick twice
			const name = el("div", {}, r.name + (r.listed ? "" : " · not listed"), st.allowed ? null : el("div", { className: "cloud-cat-why" }, st.reason));
			return el("tr", { className: `${st.allowed ? "" : "dim"}${checked === `${sec.id}:${r.id}` ? " current" : ""}`, onclick: st.allowed ? () => choose(r) : null },
				el("td", {}, radio), el("td", {}, r.provider), el("td", {}, name), el("td", {}, r.id), el("td", {}, r.params), el("td", {}, r.license), el("td", {}, r.input), el("td", {}, r.output));
		});
		return el("section", { className: "cloud-cat-section", "data-section": sec.id },
			el("div", { className: "cloud-cat-head" }, el("strong", {}, `${sec.id} ${sec.label}`), sec.note ? el("div", { className: "cloud-muted" }, sec.note) : null),
			el("div", { className: "cloud-cat-scroll" }, el("table", {}, el("thead", {}, el("tr", {}, cols.map((h) => el("th", {}, h)))), el("tbody", {}, rows))));
	}
}

// ── send / stream ───────────────────────────────────────────────────────────

async function send() {
	const text = $input.value.trim();
	if ((!text && !S.pending.length) || S.streaming) return;
	const s = current();
	const a = agent();
	if (!a) return toast("No agent can be reached");
	if (!S.sessions.includes(s)) S.sessions.unshift(s);
	s.messages.push({ role: "user", content: text, attachments: S.pending.splice(0) });
	$input.value = "";
	$input.style.height = "auto";
	await stream(s, a);
}

async function retry() {
	const s = current();
	while (s.messages.length && s.messages[s.messages.length - 1].role === "assistant") s.messages.pop();
	if (!s.messages.length) return;
	await stream(s, agent());
}

function stream(s, a) {
	let req;
	try {
		req = M.buildRequest({ chat, fleet, storage: localStorage, agent: a, model: modelOf(a), effort: s.effort, mode: s.mode, session: s, reasoning: S.reasoning, health: S.health });
	} catch (e) {
		s.messages.push({ role: "assistant", content: "", error: String(e?.message || e) });
		persist();
		return drawMessages();
	}
	const reply = { role: "assistant", content: "", streaming: true, agent: a.label, model: M.modelControl(a, modelOf(a), S.health).label };
	s.messages.push(reply);
	s.updated = Date.now();
	persist();
	drawMessages();
	const node = $list.lastElementChild;
	const id = `${s.id}-${s.messages.length}`;
	S.streaming = id;
	$send.replaceChildren(icon("cancel"));
	const parser = M.sseParser();
	let sse = false;
	let status = 0;
	let whole = "";
	let frame = 0;
	const paint = () => {
		frame = 0;
		const body = node.querySelector(".cloud-msg-body");
		body.innerHTML = DOMPurify.sanitize(md.render(reply.content || "…"));
		$list.scrollTop = $list.scrollHeight;
	};
	return new Promise((resolve) => {
		plugin().stream({ ...req, id }, (ev) => {
			if (ev.type === "head") {
				status = ev.status;
				sse = /event-stream/.test(ev.content_type) && status < 400;
			} else if (ev.type === "data") {
				if (sse) {
					for (const d of parser.push(ev.text)) reply.content += d;
					if (!frame) frame = requestAnimationFrame(paint);
				} else whole += ev.text;
			} else {
				if (ev.type === "error" && S.streaming === id) reply.error = ev.message;
				if (!sse && ev.type === "end") {
					const r = M.parseWhole(status, whole);
					if (r.error) reply.error = r.error;
					else reply.content = r.text;
				}
				if (parser.error) reply.error = parser.error;
				if (S.streaming !== id && !reply.content) reply.error = "stopped";
				delete reply.streaming;
				S.streaming = null;
				$send.replaceChildren(icon("send"));
				s.updated = Date.now();
				persist();
				drawMessages();
				resolve();
			}
		});
	});
}

function stop() {
	const id = S.streaming;
	S.streaming = null;
	if (id) plugin().cancel(id).catch(() => {});
}

// ── the drawer items this page supplies (the hamburger) ─────────────────────

function sessionRow(s, after) {
	return el("div", { className: "cloud-drawer-session" },
		el("button", { className: "cloud-drawer-item", onclick: () => { closeOverlay(); S.current = s; draw(); } }, icon(s.pinned ? "pin" : "chat_bubble"), el("span", {}, M.title(s))),
		el("button", { className: "cloud-icon-btn", title: s.pinned ? "Unpin" : "Pin", onclick: () => { s.pinned = !s.pinned; persist(); after(); } }, icon(s.pinned ? "pin-off" : "pin")));
}

function sessionList(filter, title) {
	const box = el("div", { className: "cloud-drawer-sessions" });
	const fill = () => {
		const now = Date.now();
		const all = S.sessions.filter((s) => s.messages.length);
		if (filter === "pinned") {
			const pinned = all.filter((s) => s.pinned);
			box.replaceChildren(el("div", { className: "cloud-drawer-group" }, "Pinned"), ...(pinned.length ? pinned.map((s) => sessionRow(s, fill)) : [el("p", { className: "cloud-muted" }, "Nothing pinned.")]));
			return;
		}
		const g = M.groupSessions(all, now);
		const groups = [["Pinned", g.pinned], ["Today", g.today], ["Previous 7 days", g.week], ["Older", g.older]].filter(([, l]) => l.length);
		box.replaceChildren(...(title ? [el("div", { className: "cloud-drawer-group" }, title)] : []),
			...(groups.length ? groups.flatMap(([name, l]) => [el("div", { className: "cloud-drawer-group" }, name), ...l.map((s) => sessionRow(s, fill))]) : [el("p", { className: "cloud-muted" }, "No chats yet.")]));
	};
	fill();
	return box;
}

/** The Chat page's drawer: its menu (model.js::chatMenu) bound to actions, then its chat list. */
export function chatDrawer(drawer) {
	const s = current();
	const actions = {
		new: () => { S.current = fresh(); draw(); },
		history: () => drawer("Chats", [], sessionList("all")),
		search: () => searchChats(drawer),
		pinned: () => drawer("Pinned", [], sessionList("pinned")),
		export: () => exportChat(),
		rename: async () => {
			const { default: prompt } = await import("dialogs/prompt");
			const t = await prompt("Rename this chat", M.title(s), "text");
			if (t) { s.title = t; persist(); draw(); }
		},
		clear: () => {
			S.sessions = S.sessions.filter((x) => x !== s);
			S.current = null;
			persist();
			S.current = S.sessions[0] || fresh();
			draw();
		},
	};
	return {
		items: M.chatMenu().map((it) => ({ ...it, run: actions[it.id], disabled: (it.id === "export" || it.id === "rename" || it.id === "clear") && !s.messages.length })),
		extra: [sessionList("all", "Chats")],
	};
}

function searchChats(drawer) {
	const results = el("div", { className: "cloud-drawer-sessions" });
	const input = el("input", { type: "search", placeholder: "Search chats", className: "cloud-drawer-search" });
	const fill = () => results.replaceChildren(...M.searchSessions(S.sessions.filter((x) => x.messages.length), input.value).map((s) => sessionRow(s, fill)));
	input.addEventListener("input", fill);
	drawer("Search chats", [], input, results);
	fill();
	input.focus();
}

async function exportChat() {
	const s = current();
	const text = M.exportMarkdown(s, agent()?.label);
	try {
		// Acode's own new-file tab: the export opens in the editor, to save anywhere Acode can write.
		const { default: EditorFile } = await import("lib/editorFile");
		new EditorFile(`${M.title(s).replace(/[^\w.-]+/g, "-").slice(0, 40) || "chat"}.md`, { text, isUnsaved: true, render: true });
		S.chrome.showTab("editor");
	} catch (e) {
		toast(String(e?.message || e));
	}
}

export function chatSettingsChanged() {
	draw();
}
