// cloud-code bottom nav — the pure half (#562).
//
// No DOM, no imports: everything here is a function of nav.json and of numbers
// measured on the device, so test/test-cloud-nav.sh drives THIS file under node
// and reads the answers back. index.js is the only caller in the app.

/**
 * The tab model, in declared order. Throws on anything that would render a
 * dead tab: a view with no renderer, a duplicate id, a default that is not a tab.
 * @param {object} nav   parsed nav.json
 * @param {string[]} views  names index.js can render
 */
export function buildTabs(nav, views) {
	const tabs = nav.tabs.map((t) => ({
		id: t.id,
		label: t.label,
		icon: t.icon,
		view: t.view,
	}));
	const seen = new Set();
	for (const t of tabs) {
		if (seen.has(t.id)) throw new Error(`duplicate tab id: ${t.id}`);
		seen.add(t.id);
		if (!views.includes(t.view))
			throw new Error(`tab ${t.id}: no renderer for view ${t.view}`);
	}
	if (!seen.has(nav.default_tab))
		throw new Error(`default_tab ${nav.default_tab} is not a tab`);
	return tabs;
}

/**
 * Width each tab gets on a viewport, and whether that clears the touch floor.
 */
export function tabWidth(viewportPx, tabCount) {
	return viewportPx / tabCount;
}

export function clearsTouchFloor(nav) {
	return (
		tabWidth(nav.nav.min_viewport_px, nav.tabs.length) >=
		nav.nav.min_touch_target_px
	);
}

/**
 * 'full' when every label fits inside its own tab, else 'compact' (icons for
 * all, label only on the active tab). Never truncate: a clipped label is the
 * #565 bug. Input is what the device measured, so the decision is the device's.
 * @param {{labelPx:number, tabPx:number}[]} measured
 */
export function fitLabels(measured) {
	return measured.every((m) => m.labelPx <= m.tabPx) ? "full" : "compact";
}

/**
 * What selecting a tab does. The Editor view hides every cloud panel (Acode is
 * shown untouched); a launch view (MyTerminal) fires its action and keeps the
 * current tab, because the target is another app.
 */
export function select(state, tab, launchViews) {
	if (launchViews.includes(tab.view)) {
		return { active: state.active, launch: tab.id, showPanel: state.showPanel };
	}
	return {
		active: tab.id,
		launch: null,
		showPanel: tab.view === "editor" ? null : tab.id,
	};
}
