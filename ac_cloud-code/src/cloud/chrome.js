// cloud-code app chrome over every cloud panel (Chat replaced Home): a top bar with the hamburger
// at the left (a drawer of the ACTIVE page's own items) and Profile & Config at the right, plus
// the bottom sheet the Chat composer's pickers open. Same theme variables as the bottom nav, so
// every Acode theme restyles it; data-dense, one line per row.
import { el, icon } from "./dom";

/** The bar every cloud panel starts with. [actions] are the page's own icon buttons. */
export function topBar(title, { onMenu, onProfile, subtitle } = {}, ...actions) {
	return el("div", { className: "cloud-topbar" },
		el("button", { className: "cloud-icon-btn", title: "Menu", "aria-label": "Menu", "data-role": "menu", onclick: onMenu }, icon("menu")),
		el("div", { className: "cloud-topbar-title" }, el("h2", {}, title), subtitle ? el("span", { className: "cloud-muted" }, subtitle) : null),
		...actions,
		el("button", { className: "cloud-icon-btn", title: "Profile & Config", "aria-label": "Profile & Config", "data-role": "profile", onclick: onProfile }, icon("account_circle")),
	);
}

let open = null;

function close() {
	if (open) open.remove();
	open = null;
}

/**
 * The hamburger drawer: [title], then [items] ({label, icon, run, sub, disabled}) and any extra
 * nodes the page adds below them (the Chat page puts its chat list there). A tap runs the item and
 * closes the drawer; the scrim closes it.
 */
export function drawer(title, items, ...extra) {
	close();
	const list = el("div", { className: "cloud-drawer-list" },
		items.map((it) => el("button", {
			className: "cloud-drawer-item", disabled: !!it.disabled, "data-item": it.id,
			onclick: () => { close(); it.run?.(); },
		}, icon(it.icon || "keyboard_arrow_right"), el("span", {}, it.label), it.sub ? el("span", { className: "cloud-muted" }, it.sub) : null)),
	);
	const panel = el("nav", { className: "cloud-drawer", role: "menu" }, el("div", { className: "cloud-drawer-head" }, el("strong", {}, title)), list, ...extra);
	open = el("div", { className: "cloud-overlay", onclick: (e) => { if (e.target === open) close(); } }, panel);
	document.body.append(open);
	return { close, panel };
}

/** A bottom sheet of rows (the composer's Agent / Effort / Mode / MCP / More / + pickers). */
export function sheet(title, rows, note) {
	close();
	const body = el("div", { className: "cloud-sheet-body" }, rows);
	const panel = el("div", { className: "cloud-sheet" },
		el("div", { className: "cloud-sheet-head" }, el("strong", {}, title),
			el("button", { className: "cloud-icon-btn", title: "Close", onclick: close }, icon("clearclose"))),
		note ? el("p", { className: "cloud-muted cloud-sheet-note" }, note) : null,
		body);
	open = el("div", { className: "cloud-overlay cloud-overlay-bottom", onclick: (e) => { if (e.target === open) close(); } }, panel);
	document.body.append(open);
	return { close, body, panel };
}

/** One sheet row: an option to pick (checked = current), with its reason when it cannot be picked. */
export function option(label, { checked, disabled, sub, run, iconName } = {}) {
	return el("button", {
		className: `cloud-sheet-row${checked ? " checked" : ""}`, disabled: !!disabled,
		onclick: () => { close(); run?.(); },
	}, iconName ? icon(iconName) : icon(checked ? "check" : "keyboard_arrow_right"),
	el("span", { className: "cloud-sheet-label" }, label), sub ? el("span", { className: "cloud-muted" }, sub) : null);
}

/** A sheet row with a switch that does NOT close the sheet (MCP servers, More toggles). */
export function toggleRow(label, on, onChange, sub) {
	const input = el("input", { type: "checkbox" });
	input.checked = !!on;
	input.addEventListener("change", () => onChange(input.checked));
	return el("label", { className: "cloud-sheet-row cloud-toggle" }, el("span", { className: "cloud-sheet-label" }, label),
		sub ? el("span", { className: "cloud-muted" }, sub) : null, input);
}

export function closeOverlay() {
	close();
}

/** A full-screen page above the panels (Profile & Config, the model catalogue). */
export function page(title, onBack, ...children) {
	close();
	const body = el("div", { className: "cloud-page-body" }, children);
	const node = el("section", { className: "cloud-page" },
		el("div", { className: "cloud-topbar" },
			el("button", { className: "cloud-icon-btn", title: "Back", onclick: () => { node.remove(); onBack?.(); } }, icon("arrow_back")),
			el("div", { className: "cloud-topbar-title" }, el("h2", {}, title))),
		body);
	document.body.append(node);
	return { node, body, close: () => node.remove() };
}
