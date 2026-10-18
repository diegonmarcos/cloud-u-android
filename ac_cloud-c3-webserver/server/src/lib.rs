//! c3-webserver — the phone's local web servers, natively on Android.
//!
//! [`start`] binds two loopback listeners and a DNS socket:
//!   * the emulated drive (build.json runtime.port, 8002): the SHARED web server of the fleet
//!     (ab_cloud-libs-shared/libs/fleet-webserver) rooted at /storage/emulated/0 and nothing else. Its
//!     UI is the Linux my-webserver frontend, bundled byte for byte by that crate; there is no
//!     page, route, script or style of the drive in this crate;
//!   * the fleet-diagnostics page (runtime.diag.port, 8003): the SuperApp's About, Logcat and
//!     Diagnose, read through its bridge (diag.rs);
//!   * the private DNS (serving.dns, udp 127.0.0.1:5353) for the four names.
//!
//! Every listener binds 127.0.0.1 only and refuses every other peer, and answers GET and HEAD. The
//! Tauri app (../src-tauri) calls [`start`] from its own process before it opens the window on the
//! drive's URL, so the app is this crate plus a webview: no second process, no exec, no Kotlin.

pub mod diag;
pub mod dns;
pub mod serving;

use fleet_webserver::http::{self, Req, Resp};
use fleet_webserver::render::jstr;
use std::net::{Ipv4Addr, TcpListener};
use std::sync::Arc;
use std::time::Instant;

/// What the :8003 handlers know about their listener.
pub struct Ctx {
    pub port: u16,
    pub started: Instant,
    pub binary: String,
}

/// The names this server answers to besides loopback: the four private DNS names.
fn private_names() -> Vec<String> {
    serving::get().endpoints.iter().map(|e| e.name.clone()).collect()
}

/// The :8003 listener: the diagnostics page and its three bridge reads, plus liveness and the
/// served-ports map the page's header draws. It serves no files.
pub fn handle_diag(ctx: &Ctx, req: &Req) -> Resp {
    if let Some(h) = req.header("host") {
        let host = http::host_of(h);
        if !(matches!(host.as_str(), "127.0.0.1" | "localhost" | "[::1]") || private_names().iter().any(|n| *n == host)) {
            return Resp::text(403, "Forbidden host: this server answers 127.0.0.1, localhost and its four private names");
        }
    }
    let p = req.path.trim_end_matches('/');
    match if p.is_empty() { "/__diag__" } else { p } {
        "/__diag__" => diag::page(ctx, req),
        "/__api__/health" => Resp::json(
            200,
            format!("{{\"ok\":true,\"pid\":{},\"uptime_s\":{},\"port\":{},\"root\":null}}", std::process::id(), ctx.started.elapsed().as_secs(), ctx.port),
        ),
        "/__api__/serving" => Resp::json(200, serving::json(true)),
        "/__api__/diag/about" => diag::api_about(ctx, req),
        "/__api__/diag/logcat" => diag::api_logcat(ctx, req),
        "/__api__/diag/diagnose" => diag::api_diagnose(ctx, req),
        _ => Resp::text(404, "Not found"),
    }
}

/// What the app hands the server. The ports come from build.json (build.rs), never from code.
pub struct Config {
    pub drive_port: u16,
    pub diag_port: u16,
    pub root: String,
}

/// Bind the drive listener (a failure here is the Err), then the diagnostics listener and the DNS socket (a
/// failure there is logged and the rest keeps serving). Returns once the drive socket is listening, so a caller
/// that opens a webview on its URL next never races the bind.
///
/// The root is NOT required to be readable yet: on Android the storage grant arrives after the app has started,
/// and every request resolves its path afresh, so a root that becomes readable later is served from then on.
pub fn start(cfg: Config) -> Result<(), String> {
    let sv = serving::get();
    if cfg.drive_port != sv.drive_port || cfg.diag_port != sv.diag_port {
        return Err(format!(
            "the app was built with ports {}/{} but build.json::serving declares {}/{}",
            cfg.drive_port, cfg.diag_port, sv.drive_port, sv.diag_port
        ));
    }
    let drive = TcpListener::bind((Ipv4Addr::LOCALHOST, cfg.drive_port)).map_err(|e| format!("cannot bind 127.0.0.1:{} ({})", cfg.drive_port, e))?;
    let web = fleet_webserver::Config { port: cfg.drive_port, root: cfg.root.clone(), extra_hosts: private_names() };
    println!("c3-webserver {} on http://127.0.0.1:{}/ root={} pid={}", env!("CARGO_PKG_VERSION"), cfg.drive_port, cfg.root, std::process::id());
    http::listen(drive, fleet_webserver::handler(&web));

    let binary = std::env::current_exe().map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
    match TcpListener::bind((Ipv4Addr::LOCALHOST, cfg.diag_port)) {
        Ok(l) => {
            println!("c3-webserver diagnostics on http://127.0.0.1:{}/", cfg.diag_port);
            let ctx = Ctx { port: cfg.diag_port, started: Instant::now(), binary };
            http::listen(l, Arc::new(move |req: &Req| handle_diag(&ctx, req)));
        }
        Err(e) => eprintln!("c3-webserver: diagnostics listener not started: cannot bind 127.0.0.1:{} ({})", cfg.diag_port, e),
    }
    if let Err(e) = dns::start() {
        eprintln!("c3-webserver: private DNS not started: {}", e);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};

    fn diag_get(path: &str, host: &str) -> Resp {
        let ctx = Ctx { port: 8003, started: Instant::now(), binary: "test".to_string() };
        let req = http::parse_request(&format!("GET {} HTTP/1.1\r\nhost: {}\r\n\r\n", path, host)).unwrap();
        handle_diag(&ctx, &req)
    }

    #[test]
    fn diagnostics_listener_serves_diagnostics_only() {
        assert!(String::from_utf8_lossy(&diag_get("/", "127.0.0.1:8003").body).contains("Diagnose"));
        assert_eq!(diag_get("/__diag__", "cloud.app:8003").status, 200);
        assert_eq!(diag_get("/__api__/health", "127.0.0.1").status, 200);
        assert_eq!(diag_get("/__api__/serving", "127.0.0.1").status, 200);
        for p in ["/some/file.txt", "/__api__/ls", "/__api__/read", "/__api__/search", "/__browse__", "/__lib__/marked.min.js", "/__api__/engines", "/__tab__/files"] {
            assert_eq!(diag_get(p, "127.0.0.1").status, 404, "{} must not be served on the diagnostics port", p);
        }
        assert_eq!(diag_get("/", "evil.example").status, 403, "DNS rebinding is refused");
    }

    /// The drive is the shared server and nothing else: its answer for `/` is the shared lib's browse page.
    #[test]
    fn the_drive_is_the_shared_server() {
        let root = std::env::temp_dir().join(format!("c3ws-drive-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let port = fleet_webserver::start(fleet_webserver::Config { port: 0, root: root.to_string_lossy().to_string(), extra_hosts: private_names() }).unwrap();
        let mut s = std::net::TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        write!(s, "GET / HTTP/1.1\r\nhost: emulated.app\r\nconnection: close\r\n\r\n").unwrap();
        let mut out = String::new();
        s.read_to_string(&mut out).unwrap();
        assert!(out.starts_with("HTTP/1.1 200"), "{}", &out[..out.len().min(200)]);
        let body = &out[out.find("\r\n\r\n").unwrap() + 4..];
        assert_eq!(body, fleet_webserver::render::browse_page("/"), "/ on the drive is the Linux frontend, byte for byte, and nothing else");
        for custom in ["<nav", "__tab__", "/__api__/routes", "Engines", "Configs"] {
            assert!(!body.contains(custom), "the drive's root page carries custom UI: {}", custom);
        }
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn jstr_is_the_shared_one() {
        assert_eq!(jstr("a\"b"), "\"a\\\"b\"");
    }
}
