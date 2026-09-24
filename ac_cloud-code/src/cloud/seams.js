// cloud-code — THE SEAM (#562). Diego: "FOCUS ON UI AND SMALL ENGINES".
//
// Every data source the Agents / Repos / Home tabs need that is NOT a small
// on-device engine lives behind one of these functions, and nowhere else. The UI
// only ever calls them, so replacing a stub with a real provider (the agent
// runner's API, gitea, cloud-myterminal's workspace broker) is a change to this
// file alone. Each stub is marked `SEAM:` and says what it would call; the
// tester counts the markers against the exports so a new seam cannot land
// unmarked.

import fsOperation from "fileSystem";

// SEAM: agents — replace with the agent runner's task list (my-ai claude-api,
// mesh-only). Stub: an empty list plus the reason, which the tab shows as-is.
export async function listAgents() {
	return {
		stub: true,
		items: [],
		note: "Agent runner not wired yet — this tab is UI only (#562).",
	};
}

// SEAM: repos — a small real engine for now: the children of the declared root
// that hold a .git directory. Replace with gitea / the workspace broker.
export async function listRepos(rootUrl) {
	const entries = await fsOperation(rootUrl).lsDir();
	const repos = [];
	for (const e of entries) {
		if (!e.isDirectory) continue;
		if (await fsOperation(e.url, ".git").exists()) repos.push(e);
	}
	repos.sort((a, b) => a.name.localeCompare(b.name));
	return { stub: false, items: repos };
}

// SEAM: home — the dashboard's status cards. Stub: nothing live yet; the tab
// renders one card per other tab from nav.json, which needs no provider.
export async function homeStatus() {
	return { stub: true, cards: [] };
}
