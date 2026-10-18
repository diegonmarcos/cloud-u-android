// build.json::runtime is THE declaration of the ports the server binds and the
// root it serves; it reaches the app as compile-time env vars, so lib.rs
// carries no number and no path of its own. (build.json::serving, the name ->
// port map, is compiled into the server crate itself and validated there.)
fn main() {
    println!("cargo:rerun-if-changed=../build.json");
    let text = std::fs::read_to_string("../build.json").expect("read ../build.json");
    let json: serde_json::Value = serde_json::from_str(&text).expect("parse ../build.json");
    let port = json["runtime"]["port"].as_u64().expect("build.json::runtime.port");
    let diag_port = json["runtime"]["diag"]["port"].as_u64().expect("build.json::runtime.diag.port");
    let root = json["runtime"]["serve_root"].as_str().expect("build.json::runtime.serve_root");
    let app_id = json["android"]["application_id"].as_str().expect("build.json::android.application_id");
    println!("cargo:rustc-env=C3_WEBSERVER_PORT={}", port);
    println!("cargo:rustc-env=C3_WEBSERVER_DIAG_PORT={}", diag_port);
    println!("cargo:rustc-env=C3_WEBSERVER_SERVE_ROOT={}", root);
    println!("cargo:rustc-env=C3_WEBSERVER_APP_ID={}", app_id);
    tauri_build::build()
}
