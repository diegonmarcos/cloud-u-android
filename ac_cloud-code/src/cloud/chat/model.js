// cloud-code Chat — the pure half. No DOM, no imports: every decision the Chat tab makes is a
// function of nav.json::chat, the fleet data the build resolved (targets.gen.json::chat), what the
// gateway answers live, and the owner's stored choices. test/chat.mjs loads THIS file under node and
// asserts each one; view.js is the only caller in the app and draws what these return.

export const KEY = "cloud-code.chat.";

// ── agents ──────────────────────────────────────────────────────────────────

/**
 * The agent picker, in order: nav.json's declared agents (the owner's order: Hermes, OpenClaw, …),
 * then every other fleet agent the resolver found, then the direct OpenRouter backend. Each says
 * whether it can be picked and, when not, why — a declared agent the fleet does not run is
 * "not deployed", never a fake entry that fails on send.
 * @param {object} chat     nav.json::chat
 * @param {object} fleet    targets.gen.json::chat
 * @param {object|null} health  the gateway's GET /health, or null when it did not answer
 */
export function resolveAgents(chat, fleet, health) {
	const byId = Object.fromEntries((fleet.agents || []).map((a) => [a.id, a]));
	const out = [];
	for (const decl of chat.agents) {
		const f = byId[decl.id] || { deployed: false };
		let available = !!f.deployed;
		let reason = f.deployed ? "" : "not deployed";
		if (available && health && decl.health_key && !health.agents?.[decl.health_key]) {
			available = false;
			reason = "the gateway does not serve it now";
		}
		out.push({ id: decl.id, label: decl.label, mode: decl.mode, via: "gateway", deployed: !!f.deployed, available, reason, decl });
	}
	for (const f of fleet.agents || []) {
		if (f.declared) continue;
		const label = f.id.charAt(0).toUpperCase() + f.id.slice(1);
		out.push({ id: f.id, label, mode: null, via: "none", deployed: true, available: false, reason: "no app API yet", decl: null });
	}
	out.push({ id: chat.direct.id, label: chat.direct.label, mode: null, via: "direct", deployed: true, available: true, reason: "", decl: null });
	return out;
}

/** The agent a send goes to: the stored one when it can still be picked, else the first that can. */
export function pickAgent(agents, storedId) {
	return agents.find((a) => a.id === storedId && a.available) || agents.find((a) => a.available);
}

// ── model ───────────────────────────────────────────────────────────────────

/**
 * The Model button for an agent. fixed: the gateway answers that mode with its own model (named
 * by /health.agents), so the button shows it and does not pick. catalogue: the shared catalogue's
 * A0 rows, limited to the providers the backend can run. direct: any A0 row.
 */
export function modelControl(agent, picked, health) {
	if (agent.via === "direct") return { pick: true, providers: null, value: picked, label: picked };
	const m = agent.decl?.model || { kind: "fixed" };
	if (m.kind === "catalogue") return { pick: true, providers: m.providers || null, value: picked, label: picked };
	const live = health?.agents?.[agent.decl?.health_key];
	const value = typeof live === "string" ? live : null;
	return { pick: false, providers: null, value, label: value || `set by ${agent.label}` };
}

/** Whether a catalogue row may be picked for this agent (a provider the backend can run). */
export function rowAllowed(agent, row, health) {
	const c = modelControl(agent, null, health);
	if (!c.pick || !row.selectable) return false;
	return !c.providers || c.providers.includes(row.provider);
}

/** The model field a request carries: the CLI id for claude-cli, none for a fixed agent. */
export function requestModel(agent, modelId) {
	if (agent.via === "direct") return modelId;
	const m = agent.decl?.model || { kind: "fixed" };
	if (m.kind !== "catalogue") return undefined;
	if (m.map === "claude-cli") return String(modelId).replace(/^anthropic\//, "").replace(/\./g, "-");
	return modelId;
}

// ── effort ──────────────────────────────────────────────────────────────────

/**
 * The Effort control. Hidden when the backend takes no effort at all; disabled, with the reason,
 * when it does but the model in use does not reason. [reasoning] is the set of OpenRouter ids whose
 * supported_parameters list "reasoning" (null = not known yet: the control stays usable).
 */
export function effortControl(agent, modelId, reasoning) {
	const e = agent.via === "direct" ? { param: "reasoning.effort", needs_reasoning: true } : agent.decl?.effort;
	if (!e) return { shown: false, enabled: false, reason: `${agent.label} takes no effort setting` };
	if (e.needs_reasoning && reasoning && modelId && !reasoning.has(modelId))
		return { shown: true, enabled: false, reason: `${modelId} does not reason` };
	return { shown: true, enabled: true, reason: "" };
}

/** The request field for an effort level: OpenRouter's reasoning.effort (low | medium | high | max). */
export function effortField(chat, agent, levelId, modelId, reasoning) {
	const c = effortControl(agent, modelId, reasoning);
	if (!c.shown || !c.enabled) return null;
	const level = chat.effort.levels.find((l) => l.id === levelId);
	if (!level) return null;
	return { reasoning: { effort: level.value } };
}

// ── permission mode ─────────────────────────────────────────────────────────

/** Auto / Accept / Plan: hidden for the direct backend (no tools); otherwise how the mode is applied. */
export function permissionControl(agent) {
	if (agent.via !== "gateway" || !agent.decl?.permission) return { shown: false, via: null };
	return { shown: true, via: agent.decl.permission.via, param: agent.decl.permission.param || null };
}

/** The system instruction a via=prompt backend receives for a mode ("" for Auto). */
export function permissionInstruction(chat, agent, modeId) {
	const c = permissionControl(agent);
	if (!c.shown || c.via !== "prompt") return "";
	return chat.permission.modes.find((m) => m.id === modeId)?.instruction || "";
}

// ── MCP ─────────────────────────────────────────────────────────────────────

/**
 * The MCP servers an agent has: the gateway's live /v1/mcp/status ([{server, count}]) for an agent
 * whose backend publishes it, else the fleet's derived .mcp.json names; none for the direct backend.
 */
export function mcpServers(agent, live, fleetNames) {
	if (agent.via === "direct" || !agent.decl) return { source: "none", servers: [] };
	if (agent.decl.mcp?.source === "gateway" && Array.isArray(live) && live.length)
		return { source: "gateway", servers: live.map((s) => ({ name: s.server, tools: s.count })) };
	return { source: "fleet", servers: (fleetNames || []).map((n) => ({ name: n, tools: null })) };
}

function readJson(storage, key, fallback) {
	try {
		const v = storage.getItem(KEY + key);
		return v ? JSON.parse(v) : fallback;
	} catch {
		return fallback;
	}
}

function writeJson(storage, key, value) {
	try {
		storage.setItem(KEY + key, JSON.stringify(value));
	} catch {
		// a full or blocked store keeps the in-memory state for this session
	}
}

/** {server: on} for an agent; a server never toggled is on. Persisted per agent. */
export function mcpState(storage, agentId, servers) {
	const saved = readJson(storage, `mcp.${agentId}`, {});
	return Object.fromEntries(servers.map((s) => [s.name, saved[s.name] !== false]));
}

export function mcpToggle(storage, agentId, name, on) {
	const saved = readJson(storage, `mcp.${agentId}`, {});
	saved[name] = !!on;
	writeJson(storage, `mcp.${agentId}`, saved);
	return saved;
}

// ── More ────────────────────────────────────────────────────────────────────

/**
 * The More sheet for an agent: nav.json::chat.functions that apply to it. A toggle or select is
 * listed only when the gateway's live /health.plugins has its plugin; with no live answer the
 * declaration stands and every gateway entry is marked offline.
 */
export function moreFor(chat, agent, health) {
	const plugins = health?.plugins || null;
	return chat.functions
		.filter((f) => {
			if (f.agents === "all") return true;
			if (f.agents === "gateway") return agent.via === "gateway";
			return Array.isArray(f.agents) && f.agents.includes(agent.id);
		})
		.filter((f) => !(f.kind === "toggle" || f.kind === "select") || !plugins || f.plugin in plugins)
		.map((f) => ({ ...f, offline: agent.via === "gateway" && f.agents === "gateway" && !health }));
}

/** Per-request headers from the More toggles an agent has set ({header: value}). */
export function toggleHeaders(chat, storage, agent) {
	if (agent.via !== "gateway") return {};
	const saved = readJson(storage, `toggles.${agent.id}`, {});
	const out = {};
	for (const f of chat.functions) {
		if (!(f.id in saved) || !f.header) continue;
		if (f.kind === "toggle") out[f.header] = saved[f.id] ? "on" : "off";
		else if (f.kind === "select") out[f.header] = String(saved[f.id]);
	}
	return out;
}

export function setToggle(storage, agentId, id, value) {
	const saved = readJson(storage, `toggles.${agentId}`, {});
	saved[id] = value;
	writeJson(storage, `toggles.${agentId}`, saved);
	return saved;
}

export function toggles(storage, agentId) {
	return readJson(storage, `toggles.${agentId}`, {});
}

// ── the request ─────────────────────────────────────────────────────────────

/**
 * One message's content: the text, plus each attachment as the OpenAI-compatible part its kind
 * takes — an image as image_url (a data URL), a WAV as input_audio, a small text file inline, any
 * other file as a file part. Attachments of earlier turns are not resent (named in the text).
 */
export function toContent(text, attachments) {
	const list = (attachments || []).filter((a) => a.base64);
	if (!list.length) return text;
	const parts = [];
	if (text) parts.push({ type: "text", text });
	for (const a of list) {
		const mime = a.mime || "application/octet-stream";
		if (mime.startsWith("image/")) parts.push({ type: "image_url", image_url: { url: `data:${mime};base64,${a.base64}` } });
		else if (mime === "audio/wav" || mime === "audio/x-wav" || mime === "audio/mpeg")
			parts.push({ type: "input_audio", input_audio: { data: a.base64, format: mime === "audio/mpeg" ? "mp3" : "wav" } });
		else if ((mime.startsWith("text/") || /json|xml|javascript|yaml/.test(mime)) && a.size <= 200000)
			parts.push({ type: "text", text: `${a.name}:\n${decodeBase64Utf8(a.base64)}` });
		else parts.push({ type: "file", file: { filename: a.name, file_data: `data:${mime};base64,${a.base64}` } });
	}
	return parts;
}

function decodeBase64Utf8(b64) {
	if (typeof atob === "function") {
		const bin = atob(b64);
		const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
		return new TextDecoder().decode(bytes);
	}
	return Buffer.from(b64, "base64").toString("utf8");
}

/** The conversation as the backend receives it: earlier turns as text, the last with its attachments. */
export function toMessages(session, instruction) {
	const msgs = [];
	if (instruction) msgs.push({ role: "system", content: instruction });
	const turns = session.messages.filter((m) => m.role === "user" || (m.role === "assistant" && !m.error));
	turns.forEach((m, i) => {
		const named = (m.attachments || []).map((a) => `[attached: ${a.name}]`).join(" ");
		const last = i === turns.length - 1;
		const text = last ? m.content : [m.content, named].filter(Boolean).join("\n");
		msgs.push({ role: m.role, content: last && m.role === "user" ? toContent(m.content, m.attachments) : text });
	});
	return msgs;
}

/**
 * {url, headers, body, auth} for the native stream. Gateway agents: the gateway's
 * /v1/chat/completions with X-Agent-Mode and the agent's More headers; the direct backend:
 * OpenRouter with auth "openrouter" (the plugin adds the token, the page never sees it).
 */
export function buildRequest({ chat, fleet, storage, agent, model, effort, mode, session, reasoning, health }) {
	const instruction = permissionInstruction(chat, agent, mode);
	const body = { messages: toMessages(session, instruction), stream: true };
	const m = requestModel(agent, model);
	if (m) body.model = m;
	// The model whose reasoning decides Effort: the picked one, or the one the agent runs (/health).
	const effModel = modelControl(agent, model, health).value;
	Object.assign(body, effortField(chat, agent, effort, effModel, reasoning) || {});
	if (agent.via === "direct") {
		return { url: chat.direct.url, headers: {}, body: JSON.stringify(body), auth: "openrouter" };
	}
	if (agent.via !== "gateway" || !agent.available) throw new Error(`${agent.label}: ${agent.reason || "cannot be reached"}`);
	const headers = { "x-agent-mode": agent.mode, ...toggleHeaders(chat, storage, agent) };
	return { url: fleet.gateway + chat.gateway.chat_path, headers, body: JSON.stringify(body), auth: null };
}

// ── the reply ───────────────────────────────────────────────────────────────

/**
 * An OpenAI-compatible SSE stream, fed chunk by chunk as the plugin hands them over (a chunk may
 * end mid-line). push() returns the text deltas it completed; done is set by "data: [DONE]".
 */
export function sseParser() {
	let buf = "";
	const state = {
		done: false,
		error: null,
		push(chunk) {
			buf += chunk;
			const out = [];
			let nl = buf.indexOf("\n");
			while (nl >= 0) {
				const line = buf.slice(0, nl).replace(/\r$/, "");
				buf = buf.slice(nl + 1);
				nl = buf.indexOf("\n");
				if (!line.startsWith("data:")) continue;
				const data = line.slice(5).trim();
				if (data === "[DONE]") {
					state.done = true;
					continue;
				}
				try {
					const j = JSON.parse(data);
					if (j.error) state.error = j.error.message || String(j.error);
					const d = j.choices?.[0]?.delta?.content ?? j.choices?.[0]?.message?.content;
					if (d) out.push(d);
				} catch {
					// a keep-alive comment or a partial event: ignored
				}
			}
			return out;
		},
	};
	return state;
}

/** A non-streamed answer (the gateway's agent modes answer whole): its text, or its error. */
export function parseWhole(status, body) {
	let j = null;
	try {
		j = JSON.parse(body);
	} catch {
		return status >= 400 ? { error: `HTTP ${status}: ${String(body).slice(0, 300)}` } : { text: String(body) };
	}
	if (status >= 400 || j.error) return { error: j.error?.message || `HTTP ${status}` };
	return { text: j.choices?.[0]?.message?.content ?? j.message?.content ?? "" };
}

// ── sessions (on the phone) ─────────────────────────────────────────────────

export function newSession(now, agentId) {
	return { id: `c${now.toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`, title: "", created: now, updated: now, pinned: false, agent: agentId, messages: [] };
}

export function title(session) {
	if (session.title) return session.title;
	const first = session.messages.find((m) => m.role === "user");
	const t = (first?.content || "New chat").replace(/\s+/g, " ").trim();
	return t.length > 60 ? `${t.slice(0, 57)}…` : t;
}

/** Pinned, Today, Previous 7 days, Older — each newest first (Cloud Search's grouping, plus Pinned). */
export function groupSessions(sessions, now) {
	const day = 86400000;
	const startToday = now - (now % day);
	const g = { pinned: [], today: [], week: [], older: [] };
	for (const s of [...sessions].sort((a, b) => b.updated - a.updated)) {
		if (s.pinned) g.pinned.push(s);
		else if (s.updated >= startToday) g.today.push(s);
		else if (s.updated >= startToday - 7 * day) g.week.push(s);
		else g.older.push(s);
	}
	return g;
}

export function searchSessions(sessions, q) {
	const needle = String(q || "").toLowerCase().trim();
	if (!needle) return [...sessions].sort((a, b) => b.updated - a.updated);
	return sessions
		.filter((s) => title(s).toLowerCase().includes(needle) || s.messages.some((m) => String(m.content).toLowerCase().includes(needle)))
		.sort((a, b) => b.updated - a.updated);
}

export function exportMarkdown(session, agentLabel) {
	const lines = [`# ${title(session)}`, "", `Agent: ${agentLabel || session.agent} · ${new Date(session.created).toISOString()}`, ""];
	for (const m of session.messages) {
		lines.push(`## ${m.role === "user" ? "You" : "Assistant"}`, "", m.content || "", "");
		for (const a of m.attachments || []) lines.push(`- attached: ${a.name} (${a.mime})`);
	}
	return lines.join("\n");
}

export function loadSessions(storage) {
	return readJson(storage, "sessions", []);
}

/** Saved without attachment bytes: a photo would fill the WebView's storage in a few turns. */
export function saveSessions(storage, sessions) {
	const slim = sessions.map((s) => ({ ...s, messages: s.messages.map((m) => ({ ...m, attachments: (m.attachments || []).map(({ base64, ...rest }) => rest) })) }));
	writeJson(storage, "sessions", slim);
}

export function loadSettings(storage, chat) {
	return {
		agent: "hermes",
		model: chat.default_model,
		effort: chat.effort.default,
		mode: chat.permission.default,
		...readJson(storage, "settings", {}),
	};
}

export function saveSettings(storage, settings) {
	writeJson(storage, "settings", settings);
}

// ── the drawer (the hamburger lists the ACTIVE page's items) ────────────────

/** The Chat page's own drawer items; each page supplies its own (index.js::MENUS). */
export function chatMenu() {
	return [
		{ id: "new", label: "New chat", icon: "add" },
		{ id: "history", label: "Chats", icon: "historyrestore" },
		{ id: "search", label: "Search chats", icon: "search" },
		{ id: "pinned", label: "Pinned", icon: "pin" },
		{ id: "export", label: "Export this chat", icon: "file_downloadget_app" },
		{ id: "rename", label: "Rename this chat", icon: "edit" },
		{ id: "clear", label: "Delete this chat", icon: "delete" },
	];
}

/** The id of every OpenRouter model whose supported_parameters list reasoning. */
export function reasoningIds(modelsJson) {
	try {
		const data = JSON.parse(modelsJson).data || [];
		return new Set(data.filter((m) => (m.supported_parameters || []).includes("reasoning")).map((m) => m.id));
	} catch {
		return null;
	}
}
