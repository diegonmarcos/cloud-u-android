// build.json::runtime is THE declaration of the port the server binds and the
// root it serves; it reaches the app as two compile-time env vars, so lib.rs
// carries no number and no path of its own.
fn main() {
    println!("cargo:rerun-if-changed=../build.json");
    let text = std::fs::read_to_string("../build.json").expect("read ../build.json");
    let json: serde_json::Value = serde_json::from_str(&text).expect("parse ../build.json");
    let port = json["runtime"]["port"].as_u64().expect("build.json::runtime.port");
    let root = json["runtime"]["serve_root"].as_str().expect("build.json::runtime.serve_root");
    println!("cargo:rustc-env=C3_WEBSERVER_PORT={}", port);
    println!("cargo:rustc-env=C3_WEBSERVER_SERVE_ROOT={}", root);
    tauri_build::build()
}
