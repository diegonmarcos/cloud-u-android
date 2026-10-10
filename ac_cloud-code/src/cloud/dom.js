// cloud-code: the one DOM helper every cloud panel builds with (index.js, chrome.js, chat/, profile.js).
export function el(name, props = {}, ...children) {
	const node = document.createElement(name);
	for (const [k, v] of Object.entries(props)) {
		if (v == null || v === false) continue;
		if (k === "className") node.className = v;
		else if (k === "text") node.textContent = v;
		else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
		else if (v === true) node.setAttribute(k, "");
		else node.setAttribute(k, v);
	}
	for (const c of children.flat()) {
		if (c != null && c !== false) node.append(c instanceof Node ? c : String(c));
	}
	return node;
}

export function icon(name) {
	return el("span", { className: `icon ${name}` });
}
