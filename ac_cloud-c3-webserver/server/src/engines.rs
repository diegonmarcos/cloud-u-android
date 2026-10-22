//! THE ENGINE REGISTRY: every render and feature engine of the web server this app runs, declared once in
//! build.json::render_engines and compiled in. Configs > Engines draws exactly this list
//! (`/__app__/api/engines`). The engines are the SHARED server's (ab_cloud-libs-shared/libs/fleet-webserver), which
//! has no per-engine switch, so the page is read-only. An engine of the Node original that is not served here
//! is still a row, with the reason.

use fleet_webserver::render::jstr;
use std::sync::OnceLock;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Group {
    Render,
    Feature,
    Core,
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
    pub summary: String,
    /// What it handles: extensions or routes.
    pub covers: String,
    /// Why it is not served here (NotPortable only).
    pub reason: String,
}

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
        out.push(Engine { id: st(v, "id"), name: st(v, "name"), group, summary: st(v, "summary"), covers: st(v, "covers"), reason: st(v, "reason") });
    }
    Ok(out)
}

pub fn json() -> String {
    let rows: Vec<String> = all()
        .iter()
        .map(|x| {
            format!(
                "{{\"id\":{},\"name\":{},\"group\":{},\"covers\":{},\"summary\":{},\"reason\":{}}}",
                jstr(&x.id),
                jstr(&x.name),
                jstr(x.group.name()),
                jstr(&x.covers),
                jstr(&x.summary),
                if x.reason.is_empty() { "null".to_string() } else { jstr(&x.reason) }
            )
        })
        .collect();
    format!("[{}]", rows.join(","))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn ids_are_unique_and_every_row_is_described() {
        let mut seen = HashSet::new();
        for x in all() {
            assert!(seen.insert(x.id.clone()), "engine {} is declared twice", x.id);
            assert!(x.id.chars().all(|c| c.is_ascii_lowercase() || c == '-'), "{}", x.id);
            assert!(x.summary.len() > 20, "{} needs a description", x.id);
            assert!(!x.name.is_empty() && !x.covers.is_empty(), "{}", x.id);
            if x.group == Group::NotPortable {
                assert!(x.reason.len() > 20, "{} must say why it is not served here", x.id);
            }
        }
    }

    #[test]
    fn the_node_original_is_fully_accounted_for() {
        for id in ["markdown", "json", "yaml", "secrets", "eruda", "html", "spa-fallback", "browse", "search", "dotfiles", "preview", "listing", "static", "confine", "write", "git", "watchdog", "apps"] {
            assert!(all().iter().any(|x| x.id == id), "my-webserver feature {} has no row", id);
        }
    }

    #[test]
    fn json_lists_every_engine() {
        let j: serde_json::Value = serde_json::from_str(&json()).unwrap();
        assert_eq!(j.as_array().unwrap().len(), all().len());
    }
}
