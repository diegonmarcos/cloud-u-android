// build.json::serving holds no port number. Each endpoint names its `owner` (an app dir at the repo
// root) and `port_from`, the dotted path inside that owner's build.json where the port is declared
// (the terminals' runtime.webserver.port, this app's runtime.port / runtime.diag.port). This script
// reads those declarations and writes the RESOLVED build.json to OUT_DIR, which serving.rs compiles
// in: the rest of the crate (parse, its tests, routes, dns) sees an ordinary `port` and never a
// second copy of one. A literal `port` in the source declaration, an unreadable owner or a path that
// does not lead to an integer stops the build here, not on the phone.
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::{env, fs};

fn read(p: &Path) -> Value {
    println!("cargo:rerun-if-changed={}", p.display());
    let text = fs::read_to_string(p).unwrap_or_else(|e| panic!("read {}: {}", p.display(), e));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("parse {}: {}", p.display(), e))
}

fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let repo = manifest.join("..").join("..");
    let mut root = read(&manifest.join("..").join("build.json"));
    let endpoints = root["serving"]["endpoints"].as_array_mut().expect("build.json::serving.endpoints must be an array");
    for e in endpoints.iter_mut() {
        let id = e["id"].as_str().unwrap_or("?").to_string();
        if e.get("port").is_some() {
            panic!("build.json::serving.endpoints[{}] spells a literal `port`: name port_from in its owner's build.json instead (one declaration)", id);
        }
        let owner = e["owner"].as_str().unwrap_or_else(|| panic!("build.json::serving.endpoints[{}].owner missing", id)).to_string();
        let from = e["port_from"].as_str().unwrap_or_else(|| panic!("build.json::serving.endpoints[{}].port_from missing", id)).to_string();
        let decl = read(&repo.join(&owner).join("build.json"));
        let mut node = &decl;
        for key in from.split('.') {
            node = node.get(key).unwrap_or_else(|| panic!("{}/build.json has no {} (serving.endpoints[{}].port_from)", owner, from, id));
        }
        let port = node.as_u64().unwrap_or_else(|| panic!("{}/build.json {} is not an integer (serving.endpoints[{}])", owner, from, id));
        e["port"] = port.into();
    }
    let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR")).join("build.json");
    fs::write(&out, root.to_string()).unwrap_or_else(|e| panic!("write {}: {}", out.display(), e));
}
