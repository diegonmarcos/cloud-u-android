//! THE ENGINE REGISTRY: every render and feature engine of the server, declared
//! once as a list. The Configs ▸ Engines page draws exactly this list
//! (`/__api__/engines`), handlers ask [`Engines::on`] by id, and the guards in
//! the tests below refuse an id that is used but not declared, declared but
//! never consulted, listed twice, or a toggle on something that cannot be
//! switched off. An engine of the Node original that cannot run on a phone is
//! still a row, with the reason, so "not portable" is visible in the app.

use crate::routes::jstr;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Group {
    /// Turns a file into a page.
    Render,
    /// A capability of the server.
    Feature,
    /// What every request relies on; not switchable.
    Core,
    /// Exists in my-webserver, cannot be ported; carries the reason.
    NotPortable,
}

impl Group {
    pub fn name(self) -> &'static str {
        match self {
            Group::Render => "render",
            Group::Feature => "feature",
            Group::Core => "core",
            Group::NotPortable => "not-portable",
        }
    }
}

pub struct Engine {
    pub id: &'static str,
    pub name: &'static str,
    pub group: Group,
    /// Can be switched on and off from the Engines page.
    pub toggle: bool,
    pub default_on: bool,
    pub summary: &'static str,
    /// What it handles: extensions or routes.
    pub covers: &'static str,
    /// Why it cannot run here (NotPortable only).
    pub reason: &'static str,
}

const fn e(id: &'static str, name: &'static str, group: Group, toggle: bool, covers: &'static str, summary: &'static str) -> Engine {
    Engine { id, name, group, toggle, default_on: true, summary, covers, reason: "" }
}

const fn np(id: &'static str, name: &'static str, covers: &'static str, summary: &'static str, reason: &'static str) -> Engine {
    Engine { id, name, group: Group::NotPortable, toggle: false, default_on: false, summary, covers, reason }
}

pub const ENGINES: &[Engine] = &[
    // ── render engines ──────────────────────────────────────────────────
    e("markdown", "Markdown", Group::Render, true, ".md", "marked.js 15 draws the file as a GitHub-dark page with a breadcrumb and a view-raw link; ?raw sends the source."),
    e("json", "JSON viewer", Group::Render, true, ".json", "Objects become key/value tables, arrays of objects sortable tables, nested values cards; row filter, collapsible sections, copy buttons."),
    e("yaml", "YAML viewer", Group::Render, true, ".yaml .yml", "js-yaml 4.1 parses the file and the same tables draw it; a parse error falls back to the raw text."),
    e("secrets", "Secrets table", Group::Render, true, ".secrets .secrets.json", "Key/value rows with a copy button per row and a red badge. Nothing is decrypted here: the file is shown as stored."),
    e("eruda", "Eruda DevTools", Group::Render, true, "every HTML page", "Injects the bundled Eruda 3.4.3 console, elements and network panels into HTML, index pages and rendered pages. No CDN."),
    e("html", "HTML pages", Group::Render, true, ".html, dir/index.html, /route", "Serves .html natively, opens a directory's index.html with a <base>, and /route falls back to route.html."),
    e("spa-fallback", "SPA fallback", Group::Render, true, "unknown paths under a project", "An unknown path inside a prerendered SvelteKit project serves its nearest ancestor 200.html with root-absolute assets rewritten."),
    // ── feature engines ─────────────────────────────────────────────────
    e("browse", "File browser", Group::Feature, true, "/__browse__, directory pages", "Sidebar tree, breadcrumb path bar, a viewer pane with a raw/rendered switch and a copy-raw button. Off: a plain index."),
    e("search", "Search", Group::Feature, true, "/__api__/search", "Folder, filename and content search. Breadth-first, bounded by 3 s, 50 000 entries and 200 results, and says when it stopped early."),
    e("dotfiles", "Hidden files", Group::Feature, true, "dot=1", "Lists and searches dot entries when asked (the Dot files switch). Off: hidden entries stay hidden whatever the request says."),
    e("preview", "Preview", Group::Feature, true, "/__api__/read", "Returns a text file (at most 5 MB) as JSON for the Files tab's preview pane."),
    e("diag", "Fleet diagnostics bridge", Group::Feature, true, "cloud.app :8003", "About, Logcat and Diagnose of the SuperApp, read through its signature-permission provider. Off: :8003 answers 503."),
    // ── core ────────────────────────────────────────────────────────────
    e("listing", "Directory listing", Group::Core, false, "/__api__/ls", "Folders first, then files by name, dangling symlinks shown as broken links instead of vanishing."),
    e("static", "Static files", Group::Core, false, "everything else", "MIME table with text sniffing for unknown types, ?raw plain-text passthrough, HEAD."),
    e("confine", "Root confinement", Group::Core, false, "every path", "Each path is canonicalised and must stay inside the served root; .. and symlinks that leave it are 404."),
    e("loopback", "Loopback only", Group::Core, false, "all listeners", "Binds 127.0.0.1 and refuses any other peer. GET and HEAD only; the single POST is this page's own switch."),
    e("dns", "Private DNS", Group::Core, false, "udp 127.0.0.1:5353", "Answers termux.app, nix.app, emulated.app and cloud.app with 127.0.0.1 and refuses every other name. Only our own apps ask it."),
    e("opener", "Open in cloud-browser", Group::Core, false, "/__api__/open", "The Pages tab's links hand the URL to cloud-browser, which resolves the names through the private DNS."),
    // ── in my-webserver, not portable ───────────────────────────────────
    np("write", "Write API", "the write endpoint", "Writes a file under an allow-listed directory with a shared-secret token.", "Needs a token file provisioned by home-manager and is a write path into shared storage; this app is read-only by design."),
    np("git", "Git push API", "the git endpoint", "add, commit and push a scoped set of paths.", "Needs a git binary and credentials the app does not have, and an exec surface a Tauri app does not carry."),
    np("watchdog", "Watchdog snapshot", "the watchdog snapshot endpoint", "Hands over my-watchdog's two-second metrics file.", "The snapshot lives in /run or XDG_RUNTIME_DIR of a Linux session; Android has neither, and the phone's metrics are the Home tab."),
    np("apps", "App panels", "the app panel prefix", "Serves another project's dist/ directory as a panel.", "Resolves a checkout under $HOME/git; there is no such tree on the phone."),
];

pub fn find(id: &str) -> Option<&'static Engine> {
    ENGINES.iter().find(|x| x.id == id)
}

pub struct Engines {
    state: Mutex<HashMap<&'static str, bool>>,
    file: Option<PathBuf>,
}

impl Engines {
    /// Defaults, then the saved overrides from `<dir>/engines.json` when a state dir is given.
    pub fn new(dir: Option<PathBuf>) -> Engines {
        let mut state = HashMap::new();
        for x in ENGINES.iter().filter(|x| x.toggle) {
            state.insert(x.id, x.default_on);
        }
        let file = dir.map(|d| d.join("engines.json"));
        if let Some(f) = &file {
            if let Some(saved) = std::fs::read_to_string(f).ok().and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok()) {
                for x in ENGINES.iter().filter(|x| x.toggle) {
                    if let Some(b) = saved.get(x.id).and_then(|v| v.as_bool()) {
                        state.insert(x.id, b);
                    }
                }
            }
        }
        Engines { state: Mutex::new(state), file }
    }

    /// Is the engine on? A core engine is always on; a not-portable one never is; an undeclared id is a bug
    /// (the registry guard fails on it) and reads as off.
    pub fn on(&self, id: &str) -> bool {
        match find(id) {
            Some(x) if x.group == Group::NotPortable => false,
            Some(x) if !x.toggle => true,
            Some(x) => *self.state.lock().unwrap().get(x.id).unwrap_or(&x.default_on),
            None => false,
        }
    }

    pub fn set(&self, id: &str, on: bool) -> Result<(), String> {
        let x = find(id).ok_or_else(|| format!("no engine {:?}", id))?;
        if !x.toggle {
            return Err(format!("{} cannot be switched", x.id));
        }
        let snapshot = {
            let mut st = self.state.lock().unwrap();
            st.insert(x.id, on);
            let rows: Vec<String> = ENGINES.iter().filter(|e| e.toggle).map(|e| format!("{}:{}", jstr(e.id), st.get(e.id).copied().unwrap_or(e.default_on))).collect();
            format!("{{{}}}\n", rows.join(","))
        };
        if let Some(f) = &self.file {
            if let Some(dir) = f.parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            std::fs::write(f, snapshot).map_err(|e| format!("cannot save {}: {}", f.display(), e))?;
        }
        Ok(())
    }

    pub fn json(&self) -> String {
        let rows: Vec<String> = ENGINES
            .iter()
            .map(|x| {
                format!(
                    concat!(
                        "{{\"id\":{},\"name\":{},\"group\":{},\"toggle\":{},\"on\":{},\"default_on\":{},",
                        "\"covers\":{},\"summary\":{},\"reason\":{}}}"
                    ),
                    jstr(x.id),
                    jstr(x.name),
                    jstr(x.group.name()),
                    x.toggle,
                    self.on(x.id),
                    x.default_on,
                    jstr(x.covers),
                    jstr(x.summary),
                    if x.reason.is_empty() { "null".to_string() } else { jstr(x.reason) }
                )
            })
            .collect();
        format!("[{}]", rows.join(","))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    /// Every `engines.on("<id>")` call in the server's own sources, minus this file's tests.
    fn used_ids() -> HashSet<String> {
        let mut ids = HashSet::new();
        for src in [include_str!("routes.rs"), include_str!("files.rs"), include_str!("lib.rs"), include_str!("diag.rs")] {
            let body = src.split("#[cfg(test)]").next().unwrap();
            let mut rest = body;
            while let Some(i) = rest.find("engines.on(\"") {
                rest = &rest[i + 12..];
                let id = &rest[..rest.find('"').unwrap()];
                ids.insert(id.to_string());
            }
        }
        ids
    }

    #[test]
    fn ids_are_unique_and_every_row_is_described() {
        let mut seen = HashSet::new();
        for x in ENGINES {
            assert!(seen.insert(x.id), "engine {} is declared twice", x.id);
            assert!(x.id.chars().all(|c| c.is_ascii_lowercase() || c == '-'), "{}", x.id);
            assert!(x.summary.len() > 20, "{} needs a description", x.id);
            assert!(!x.name.is_empty() && !x.covers.is_empty(), "{}", x.id);
        }
    }

    #[test]
    fn toggles_exist_exactly_where_they_make_sense() {
        for x in ENGINES {
            match x.group {
                Group::Render | Group::Feature => assert!(x.toggle, "{} is a render/feature engine and must be switchable", x.id),
                Group::Core => assert!(!x.toggle && x.default_on, "{} is core: always on, no switch", x.id),
                Group::NotPortable => assert!(!x.toggle && !x.default_on && x.reason.len() > 20, "{} must say why it cannot run here", x.id),
            }
        }
    }

    #[test]
    fn every_consulted_id_is_declared_and_every_switch_is_consulted() {
        let used = used_ids();
        for id in &used {
            let x = find(id).unwrap_or_else(|| panic!("a handler asks for engine {:?}, which is not in ENGINES", id));
            assert!(x.toggle, "engines.on({:?}) is asked of something that cannot be switched", id);
        }
        for x in ENGINES.iter().filter(|x| x.toggle) {
            assert!(used.contains(x.id), "engine {} has a switch but no handler consults it", x.id);
        }
    }

    #[test]
    fn the_node_original_is_fully_accounted_for() {
        // my-webserver's features, each either an engine here or a not-portable row with a reason.
        for id in ["markdown", "json", "yaml", "secrets", "eruda", "html", "spa-fallback", "browse", "search", "dotfiles", "preview", "listing", "static", "confine", "write", "git", "watchdog", "apps"] {
            assert!(find(id).is_some(), "my-webserver feature {} has no row", id);
        }
    }

    #[test]
    fn switching_works_persists_and_refuses_core() {
        let dir = std::env::temp_dir().join(format!("c3ws-eng-{}-{}", std::process::id(), ENGINES.len()));
        let _ = std::fs::remove_dir_all(&dir);
        let en = Engines::new(Some(dir.clone()));
        assert!(en.on("markdown") && en.on("loopback"));
        en.set("markdown", false).unwrap();
        assert!(!en.on("markdown"));
        assert!(en.set("loopback", false).is_err(), "core engines have no switch");
        assert!(en.set("write", true).is_err() && !en.on("write"), "a not-portable engine never turns on");
        assert!(en.set("nope", true).is_err());
        let again = Engines::new(Some(dir.clone()));
        assert!(!again.on("markdown"), "the switch survives a restart");
        assert!(again.on("json"));
        assert!(en.json().contains("\"id\":\"markdown\"") && en.json().contains("\"on\":false"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
