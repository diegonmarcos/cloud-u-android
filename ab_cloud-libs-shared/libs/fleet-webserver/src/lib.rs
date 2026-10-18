//! fleet-webserver: THE web server of the fleet. The Linux `my-webserver` HTTP API over one root
//! directory, serving the Linux frontend (folder tree left, viewer right, toolbar on top) that
//! is bundled into the binary at build time from vendor/ (byte-identical to cloud-u-linux,
//! see sync.sh and tests/vendor.rs). c3-webserver (:8002), termux (:8000) and nix-on-droid
//! (:8001) all consume this crate; none of them carries a copy of any of it.
//!
//! Contract (what the frontend calls, nothing more and nothing less):
//!   GET /                      the file browser SPA, or the root's index.html
//!   GET /<dir>/                the SPA opened there, or that directory's index.html
//!   GET /<file>                the file; .md/.json/.yaml/.secrets rendered, .html + Eruda
//!   GET /<file>?raw            the file as text/plain
//!   GET /__api__/ls?path=&dot=1
//!   GET /__api__/read?path=
//!   GET /__api__/search?q=&mode=filename|folder|content&path=&dot=1   (header x-search-partial)
//!   GET /__lib__/{marked.min.js,github-markdown-dark.css,js-yaml.min.js,eruda.min.js,browse.html}

pub mod api;
pub mod files;
pub mod http;
pub mod render;
pub mod sha256;

use http::{Req, Resp};
use std::net::{Ipv4Addr, TcpListener};
use std::path::PathBuf;
use std::sync::Arc;

pub struct Ctx {
    /// Canonical served root.
    pub root: PathBuf,
    pub search_ms: u64,
    /// Host header values accepted besides loopback (the phone's private names). Anything else is a
    /// DNS-rebinding attempt or a stranger.
    pub extra_hosts: Vec<String>,
}

pub struct Config {
    pub port: u16,
    pub root: String,
    pub extra_hosts: Vec<String>,
}

fn host_allowed(ctx: &Ctx, host: &str) -> bool {
    matches!(host, "127.0.0.1" | "localhost" | "[::1]") || ctx.extra_hosts.iter().any(|h| h == host)
}

pub fn handle(ctx: &Ctx, req: &Req) -> Resp {
    if let Some(h) = req.header("host") {
        if !host_allowed(ctx, &http::host_of(h)) {
            return Resp::text(403, "Forbidden host");
        }
    }
    match req.path.as_str() {
        "/__browse__" | "/__browse__/" => return Resp::html(render::browse_page("/")),
        "/__api__/ls" => return api::ls(ctx, req),
        "/__api__/read" => return api::read(ctx, req),
        "/__api__/search" => return api::search(ctx, req),
        p => {
            if let Some(name) = p.strip_prefix("/__lib__/") {
                return api::lib(name).unwrap_or_else(|| Resp::text(404, "Not found"));
            }
        }
    }
    files::page_files(ctx, req)
}

/// A handler for [`http::listen`] serving `cfg.root`. The root is NOT required to be readable yet:
/// on Android the storage grant arrives after the app has started and every request resolves its
/// path afresh, so a root that becomes readable later is served from then on.
pub fn handler(cfg: &Config) -> Arc<http::Handler> {
    let root = std::fs::canonicalize(&cfg.root).unwrap_or_else(|_| PathBuf::from(&cfg.root));
    let search_ms = std::env::var("HTTPD_SEARCH_MS").ok().and_then(|v| v.parse().ok()).unwrap_or(api::SEARCH_DEADLINE_MS);
    let ctx = Ctx { root, search_ms, extra_hosts: cfg.extra_hosts.clone() };
    Arc::new(move |req: &Req| handle(&ctx, req))
}

/// Bind 127.0.0.1:`cfg.port` and serve it on background threads. Returns the bound port (so port 0
/// works for tests). A bind failure is the Err.
pub fn start(cfg: Config) -> Result<u16, String> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, cfg.port)).map_err(|e| format!("cannot bind 127.0.0.1:{} ({})", cfg.port, e))?;
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(cfg.port);
    println!("fleet-webserver {} on http://127.0.0.1:{}/ root={} pid={}", env!("CARGO_PKG_VERSION"), port, cfg.root, std::process::id());
    http::listen(listener, handler(&cfg));
    Ok(port)
}
