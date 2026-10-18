//! The ports this phone serves and the private names that stand for them.
//!
//! ONE declaration: build.json::serving (names, ports, DNS server) beside
//! build.json::runtime (the two listeners this app binds). It is compiled into
//! the binary here; src-tauri/build.rs reads the same file for the ports it
//! hands the server, and ac_cloud-browser/app/build.gradle bakes the same
//! name -> port map into the browser. [parse] refuses a declaration whose
//! ports or names collide, so a drifted copy fails the build, not the phone.
//!
//! .app is a real public TLD and termux.app is a real website. These four exact
//! names are answered by this app's own DNS server (dns.rs) on loopback and are
//! resolved only by our own apps; nothing is changed system-wide.

use crate::routes::jstr;
use serde_json::Value;
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::sync::OnceLock;
use std::time::Duration;

pub const BUILD_JSON: &str = include_str!("../../build.json");

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Role {
    /// Served by another app (the terminals' my-webserver); this app only names and links it.
    External,
    /// The emulated drive, /storage/emulated/0 and nothing else.
    Drive,
    /// The fleet-diagnostics page.
    Diag,
}

impl Role {
    pub fn name(self) -> &'static str {
        match self {
            Role::External => "external",
            Role::Drive => "drive",
            Role::Diag => "diag",
        }
    }
}

#[derive(Clone, Debug)]
pub struct Endpoint {
    pub id: String,
    pub name: String,
    pub port: u16,
    pub role: Role,
    pub owner: String,
    pub title: String,
    pub summary: String,
}

impl Endpoint {
    pub fn url_ip(&self) -> String {
        format!("http://127.0.0.1:{}/", self.port)
    }
    pub fn url_name(&self) -> String {
        format!("http://{}/", self.name)
    }
}

#[derive(Clone, Debug)]
pub struct Dns {
    pub host: String,
    pub port: u16,
    pub answer: String,
    pub ttl_s: u32,
}

#[derive(Clone, Debug)]
pub struct Serving {
    pub dns: Dns,
    pub endpoints: Vec<Endpoint>,
    pub drive_port: u16,
    pub diag_port: u16,
    pub browser_package: String,
    pub bridge_package: String,
    pub bridge_authority: String,
    pub app_name: String,
    pub version_name: String,
    pub version_code: u64,
}

fn s(v: &Value, key: &str) -> Result<String, String> {
    v.get(key).and_then(Value::as_str).map(str::to_string).filter(|x| !x.is_empty()).ok_or_else(|| format!("serving: `{}` missing or empty", key))
}

fn port(v: &Value, what: &str) -> Result<u16, String> {
    match v.as_u64() {
        Some(p) if (1024..=65535).contains(&p) => Ok(p as u16),
        _ => Err(format!("{} must be an integer in 1024..65535, got {}", what, v)),
    }
}

/// Parse and VALIDATE build.json text. Everything that can drift is checked here, so the same
/// function guards the compiled copy, the gradle bake and the tests.
pub fn parse(text: &str) -> Result<Serving, String> {
    let root: Value = serde_json::from_str(text).map_err(|e| format!("build.json does not parse: {}", e))?;
    let sv = root.get("serving").ok_or("build.json has no `serving` block")?;
    let rt = root.get("runtime").ok_or("build.json has no `runtime` block")?;
    let drive_port = port(&rt["port"], "runtime.port")?;
    let diag_port = port(&rt["diag"]["port"], "runtime.diag.port")?;

    let d = &sv["dns"];
    let dns = Dns {
        host: s(d, "host")?,
        port: port(&d["port"], "serving.dns.port")?,
        answer: s(d, "answer")?,
        ttl_s: d["ttl_s"].as_u64().unwrap_or(30).min(3600) as u32,
    };
    for (what, ip) in [("serving.dns.host", &dns.host), ("serving.dns.answer", &dns.answer)] {
        match ip.parse::<IpAddr>() {
            Ok(a) if a.is_loopback() => {}
            _ => return Err(format!("{} = {} must be a loopback address: the private DNS answers and listens on loopback only", what, ip)),
        }
    }

    let mut endpoints = Vec::new();
    for e in sv["endpoints"].as_array().ok_or("serving.endpoints must be an array")? {
        let role = match s(e, "role")?.as_str() {
            "external" => Role::External,
            "drive" => Role::Drive,
            "diag" => Role::Diag,
            other => return Err(format!("serving.endpoints role {:?} is not external|drive|diag", other)),
        };
        endpoints.push(Endpoint {
            id: s(e, "id")?,
            name: s(e, "name")?,
            port: port(&e["port"], "serving.endpoints[].port")?,
            role,
            owner: s(e, "owner")?,
            title: s(e, "title")?,
            summary: s(e, "summary")?,
        });
    }

    for (i, a) in endpoints.iter().enumerate() {
        if a.name != a.name.to_ascii_lowercase() || !a.name.ends_with(".app") || a.name.matches('.').count() != 1 || a.name.len() < 6 {
            return Err(format!("name {:?} must be one lowercase label under .app (exact names only, never a wildcard)", a.name));
        }
        if !a.name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'.') {
            return Err(format!("name {:?} has characters outside a-z 0-9 -", a.name));
        }
        if a.port == dns.port {
            return Err(format!("{} port {} is the DNS server's own port", a.name, a.port));
        }
        for b in &endpoints[i + 1..] {
            if a.port == b.port {
                return Err(format!("port {} is declared twice ({} and {})", a.port, a.name, b.name));
            }
            if a.name == b.name {
                return Err(format!("name {} is declared twice", a.name));
            }
            if a.id == b.id {
                return Err(format!("id {} is declared twice", a.id));
            }
        }
    }
    let one = |role: Role| -> Result<&Endpoint, String> {
        let hits: Vec<&Endpoint> = endpoints.iter().filter(|e| e.role == role).collect();
        match hits.len() {
            1 => Ok(hits[0]),
            n => Err(format!("exactly one `{}` endpoint is allowed, found {}", role.name(), n)),
        }
    };
    if one(Role::Drive)?.port != drive_port {
        return Err(format!("the drive endpoint is port {} but runtime.port is {}: one declaration, two readers", one(Role::Drive)?.port, drive_port));
    }
    if one(Role::Diag)?.port != diag_port {
        return Err(format!("the diag endpoint is port {} but runtime.diag.port is {}", one(Role::Diag)?.port, diag_port));
    }
    if drive_port == diag_port {
        return Err("the drive and diag listeners share a port".to_string());
    }

    let br = &sv["bridge"];
    Ok(Serving {
        dns,
        endpoints,
        drive_port,
        diag_port,
        browser_package: s(&sv["opener"], "package")?,
        bridge_package: s(br, "package")?,
        bridge_authority: s(br, "authority")?,
        app_name: s(&root, "name")?,
        version_name: root["android"]["version_name"].as_str().unwrap_or("").to_string(),
        version_code: root["android"]["version_code"].as_u64().unwrap_or(0),
    })
}

pub fn get() -> &'static Serving {
    static S: OnceLock<Serving> = OnceLock::new();
    S.get_or_init(|| parse(BUILD_JSON).expect("build.json::serving is invalid (the tests catch this at build time)"))
}

pub fn by_id(id: &str) -> Option<&'static Endpoint> {
    get().endpoints.iter().find(|e| e.id == id)
}

/// The exact names this app's DNS answers.
pub fn names() -> Vec<&'static str> {
    get().endpoints.iter().map(|e| e.name.as_str()).collect()
}

/// A loopback connect with a short timeout: is something listening on that port now?
pub fn is_up(port: u16) -> bool {
    let addr = SocketAddr::from(([127, 0, 0, 1], port));
    TcpStream::connect_timeout(&addr, Duration::from_millis(120)).is_ok()
}

/// The renderers shipped inside the binary, with their versions.
fn bundled_json() -> String {
    use crate::render::{ERUDA_VERSION, MARKED_VERSION, YAML_VERSION};
    let rows: Vec<String> = [("marked (Markdown)", MARKED_VERSION), ("js-yaml (YAML)", YAML_VERSION), ("Eruda (DevTools)", ERUDA_VERSION)]
        .iter()
        .map(|(n, v)| format!("{{\"name\":{},\"version\":{}}}", jstr(n), jstr(v)))
        .collect();
    format!("[{}]", rows.join(","))
}

/// `/__api__/serving`: the declaration plus whether each port answers right now.
pub fn json(live: bool) -> String {
    let sv = get();
    let rows: Vec<String> = sv
        .endpoints
        .iter()
        .map(|e| {
            format!(
                concat!(
                    "{{\"id\":{},\"name\":{},\"port\":{},\"role\":{},\"owner\":{},\"title\":{},\"summary\":{},",
                    "\"url_ip\":{},\"url_name\":{},\"up\":{}}}"
                ),
                jstr(&e.id),
                jstr(&e.name),
                e.port,
                jstr(e.role.name()),
                jstr(&e.owner),
                jstr(&e.title),
                jstr(&e.summary),
                jstr(&e.url_ip()),
                jstr(&e.url_name()),
                if live { is_up(e.port).to_string() } else { "null".to_string() }
            )
        })
        .collect();
    format!(
        concat!(
            "{{\"app\":{},\"version_name\":{},\"version_code\":{},\"crate_version\":{},",
            "\"dns\":{{\"host\":{},\"port\":{},\"answer\":{},\"ttl_s\":{},\"scope\":{}}},",
            "\"opener\":{},\"bridge\":{{\"package\":{},\"authority\":{}}},\"bundled\":{},\"endpoints\":[{}]}}"
        ),
        jstr(&sv.app_name),
        jstr(&sv.version_name),
        sv.version_code,
        jstr(env!("CARGO_PKG_VERSION")),
        jstr(&sv.dns.host),
        sv.dns.port,
        jstr(&sv.dns.answer),
        sv.dns.ttl_s,
        jstr("these four exact names only, answered on loopback, resolved only inside our own apps; never system-wide"),
        jstr(&sv.browser_package),
        jstr(&sv.bridge_package),
        jstr(&sv.bridge_authority),
        bundled_json(),
        rows.join(",")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mutated(f: impl Fn(&mut Value)) -> String {
        let mut v: Value = serde_json::from_str(BUILD_JSON).unwrap();
        f(&mut v);
        v.to_string()
    }

    #[test]
    fn the_shipped_declaration_is_valid_and_is_the_four_names() {
        let sv = parse(BUILD_JSON).expect("build.json::serving");
        let got: Vec<(String, u16)> = sv.endpoints.iter().map(|e| (e.name.clone(), e.port)).collect();
        assert_eq!(
            got,
            vec![
                ("termux.app".to_string(), 8000),
                ("nix.app".to_string(), 8001),
                ("emulated.app".to_string(), 8002),
                ("cloud.app".to_string(), 8003)
            ],
            "the owner's map: termux 8000, nix 8001, emulated drive 8002, cloud diagnostics 8003"
        );
        assert_eq!((sv.dns.host.as_str(), sv.dns.port), ("127.0.0.1", 5353));
    }

    #[test]
    fn ports_must_stay_unique() {
        let e = parse(&mutated(|v| v["serving"]["endpoints"][1]["port"] = 8000.into())).unwrap_err();
        assert!(e.contains("declared twice"), "{}", e);
        let e = parse(&mutated(|v| v["serving"]["endpoints"][3]["port"] = 5353.into())).unwrap_err();
        assert!(e.contains("DNS server's own port"), "{}", e);
    }

    #[test]
    fn names_must_stay_unique_exact_and_under_app() {
        assert!(parse(&mutated(|v| v["serving"]["endpoints"][1]["name"] = "termux.app".into())).unwrap_err().contains("twice"));
        assert!(parse(&mutated(|v| v["serving"]["endpoints"][1]["name"] = "*.app".into())).is_err());
        assert!(parse(&mutated(|v| v["serving"]["endpoints"][1]["name"] = "nix.com".into())).is_err());
        assert!(parse(&mutated(|v| v["serving"]["endpoints"][1]["name"] = "a.b.app".into())).is_err());
        assert!(parse(&mutated(|v| v["serving"]["endpoints"][1]["name"] = "Nix.app".into())).is_err());
    }

    #[test]
    fn the_listeners_and_the_map_must_agree() {
        let e = parse(&mutated(|v| v["runtime"]["port"] = 8100.into())).unwrap_err();
        assert!(e.contains("runtime.port"), "{}", e);
        let e = parse(&mutated(|v| v["runtime"]["diag"]["port"] = 8100.into())).unwrap_err();
        assert!(e.contains("runtime.diag.port"), "{}", e);
        assert!(parse(&mutated(|v| v["serving"]["endpoints"][2]["role"] = "external".into())).unwrap_err().contains("exactly one"));
    }

    #[test]
    fn dns_is_loopback_only() {
        assert!(parse(&mutated(|v| v["serving"]["dns"]["host"] = "0.0.0.0".into())).is_err());
        assert!(parse(&mutated(|v| v["serving"]["dns"]["answer"] = "10.0.0.1".into())).is_err());
    }

    #[test]
    fn json_has_a_row_per_endpoint() {
        let j = json(false);
        for e in &get().endpoints {
            assert!(j.contains(&format!("\"name\":{}", jstr(&e.name))));
        }
        assert_eq!(j.matches("\"url_name\"").count(), get().endpoints.len());
    }
}
