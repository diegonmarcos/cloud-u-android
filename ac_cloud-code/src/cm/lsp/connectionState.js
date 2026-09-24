import serverRegistry from "./serverRegistry";

function isInternalConfigFile(file) {
	const name = String(file?.filename || file?.name || "")
		.trim()
		.toLowerCase();
	return (
		name === "settings.json" ||
		name === ".key-bindings.json" ||
		name === "keybindings.json" ||
		name === ".keybindings.json"
	);
}

function getCurrentFileLanguage() {
	try {
		const file = window.editorManager?.activeFile;
		if (!file || file.type !== "editor") return null;
		return file.currentMode?.toLowerCase() || null;
	} catch {
		return null;
	}
}

function getServersForCurrentFile() {
	const file = window.editorManager?.activeFile;
	if (isInternalConfigFile(file)) return [];

	const language = getCurrentFileLanguage();
	if (!language) return [];

	try {
		return serverRegistry.getServersForLanguage(language);
	} catch {
		return [];
	}
}

function hasConnectedServers() {
	return getServersForCurrentFile().length > 0;
}

export {
	getCurrentFileLanguage,
	getServersForCurrentFile,
	hasConnectedServers,
};
