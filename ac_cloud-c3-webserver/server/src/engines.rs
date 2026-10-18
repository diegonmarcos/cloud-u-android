//! THE ENGINE REGISTRY: every render and feature engine of the server, declared
//! once as a list. The Configs ▸ Engines page draws exactly this list
//! (`/__api__/engines`), handlers ask [`Engines::on`] by id, and the guards in
//! the tests below refuse an id that is used but not declared, declared but
//! never consulted, listed twice, or a toggle on something that cannot be
//! switched off. An engine of the Node original that cannot run on a phone is
//! still a row, with the reason, so "not portable" is visible in the app.

use crate::routes::jstr;
use std::collections::HashMap;
use std::sync::OnceLock;
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
    pub id: String,
    pub name: String,
    pub group: Group,
    /// Can be switched on and off from the Engines page.
    pub toggle: bool,
    pub default_on: bool,
    pub summary: String,
    /// What it handles: extensions or routes.
    pub covers: String,
    /// Why it cannot run here (NotPortable only).
    pub reason: String,
}

/// THE registry: build.json::render_engines.list, compiled in with the rest of build.json. Nothing in this crate spells
/// an engine's name, description or switch; only handlers spell ids, and the tests below hold both directions.
pub fn all() -> &'static [Engine] {
    static ALL: OnceLock<Vec<Engine>> = OnceLock::new();
    ALL.get_or_init(|| parse(crate::serving::BUILD_JSON).expect("build.json::render_engines is invalid (the tests catch this at build time)"))
}

pub fn parse(text: &str) -> Result<Vec<Engine>, String> {
    let root: serde_json::Value = serde_json::from_str(text).map_err(|e| format!("build.json does not parse: {}", e))?;
    let list = root["render_engines"]["list"].as_array().ok_or("build.json::render_engines.list must be an array")?;
    let st = |v: &serde_json::Value, k: &str| v[k].as_str().unwrap_or("").to_string();
    let mut out = Vec::new();
    for v in list {
        let group = match v["group"].as_str() {
            Some("render") => Group::Render,
            Some("feature") => Group::Feature,
            Some("core") => Group::Core,
            Some("not-portable") => Group::NotPortable,
            other => return Err(format!("engine {:?} has group {:?}", v["id"], other)),
        };
        out.push(Engine {
            id: st(v, "id"),
            name: st(v, "name"),
            group,
            toggle: v["toggle"].as_bool().unwrap_or(false),
            default_on: v["default_on"].as_bool().unwrap_or(false),
            summary: st(v, "summary"),
            covers: st(v, "covers"),
            reason: st(v, "reason"),
        });
    }
    Ok(out)
}

pub fn find(id: &str) -> Option<&'static Engine> {
    all().iter().find(|x| x.id == id)
}

pub struct Engines {
    state: Mutex<HashMap<String, bool>>,
    file: Option<PathBuf>,
}

impl Engines {
    /// Defaults, then the saved overrides from `<dir>/engines.json` when a state dir is given.
    pub fn new(dir: Option<PathBuf>) -> Engines {
        let mut state = HashMap::new();
        for x in all().iter().filter(|x| x.toggle) {
            state.insert(x.id.clone(), x.default_on);
        }
        let file = dir.map(|d| d.join("engines.json"));
        if let Some(f) = &file {
            if let Some(saved) = std::fs::read_to_string(f).ok().and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok()) {
                for x in all().iter().filter(|x| x.toggle) {
                    if let Some(b) = saved.get(x.id.as_str()).and_then(|v| v.as_bool()) {
                        state.insert(x.id.clone(), b);
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
            Some(x) => *self.state.lock().unwrap().get(&x.id).unwrap_or(&x.default_on),
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
            st.insert(x.id.clone(), on);
            let rows: Vec<String> = all().iter().filter(|e| e.toggle).map(|e| format!("{}:{}", jstr(&e.id), st.get(&e.id).copied().unwrap_or(e.default_on))).collect();
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
        let rows: Vec<String> = all()
            .iter()
            .map(|x| {
                format!(
                    concat!(
                        "{{\"id\":{},\"name\":{},\"group\":{},\"toggle\":{},\"on\":{},\"default_on\":{},",
                        "\"covers\":{},\"summary\":{},\"reason\":{}}}"
                    ),
                    jstr(&x.id),
                    jstr(&x.name),
                    jstr(x.group.name()),
                    x.toggle,
                    self.on(&x.id),
                    x.default_on,
                    jstr(&x.covers),
                    jstr(&x.summary),
                    if x.reason.is_empty() { "null".to_string() } else { jstr(&x.reason) }
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
        for x in all() {
            assert!(seen.insert(x.id.clone()), "engine {} is declared twice", x.id);
            assert!(x.id.chars().all(|c| c.is_ascii_lowercase() || c == '-'), "{}", x.id);
            assert!(x.summary.len() > 20, "{} needs a description", x.id);
            assert!(!x.name.is_empty() && !x.covers.is_empty(), "{}", x.id);
        }
    }

    #[test]
    fn toggles_exist_exactly_where_they_make_sense() {
        for x in all() {
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
            let x = find(id).unwrap_or_else(|| panic!("a handler asks for engine {:?}, which is not in build.json::render_engines", id));
            assert!(x.toggle, "engines.on({:?}) is asked of something that cannot be switched", id);
        }
        for x in all().iter().filter(|x| x.toggle) {
            assert!(used.contains(&x.id), "engine {} has a switch but no handler consults it", x.id);
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
        let dir = std::env::temp_dir().join(format!("c3ws-eng-{}-{}", std::process::id(), all().len()));
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
