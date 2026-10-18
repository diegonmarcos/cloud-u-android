//! c3-webserver — the phone's local web server, natively on Android.
//!
//! [`start`] binds two loopback listeners and a DNS socket:
//!   * the emulated drive (build.json runtime.port, 8002): serves the shell and
//!     <root> (/storage/emulated/0) and nothing else;
//!   * the fleet-diagnostics page (runtime.diag.port, 8003): the SuperApp's
//!     About, Logcat and Diagnose, read through its bridge;
//!   * the private DNS (serving.dns, udp 127.0.0.1:5353) for the four names.
//!
//! Every listener binds 127.0.0.1 only and refuses every other peer, answers
//! GET and HEAD (plus one guarded POST for the Engines switch and the browser
//! opener), one thread per connection, and refuses to start on a route table
//! that is not well-formed (see routes.rs). The Tauri app (../src-tauri) calls
//! it from its own process before it opens the window on the drive's URL, so the
//! app is this crate plus a webview: no second process, no exec, no Kotlin.

pub mod about;
pub mod diag;
pub mod dns;
pub mod engines;
pub mod files;
pub mod render;
pub mod routes;
pub mod serving;

use routes::Side;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

pub struct Ctx {
    pub root: PathBuf,
    pub port: u16,
    pub started: Instant,
    pub binary: String,
    /// Which listener this connection came in on.
    pub side: Side,
    pub engines: Arc<engines::Engines>,
}

pub struct Req {
    pub method: String,
    pub path: String,
    pub query: Vec<(String, String)>,
    /// Lower-cased names.
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Req {
    pub fn q(&self, key: &str) -> Option<&str> {
        self.query.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    }
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str())
    }
}

pub struct Resp {
    pub status: u16,
    pub ctype: &'static str,
    pub body: Vec<u8>,
    pub headers: Vec<(String, String)>,
    /// A file sent from disk instead of from `body` (length declared by the caller).
    pub stream: Option<(std::fs::File, u64)>,
}

impl Resp {
    pub fn bytes(status: u16, ctype: &'static str, body: Vec<u8>) -> Resp {
        Resp { status, ctype, body, headers: Vec::new(), stream: None }
    }
    pub fn stream(status: u16, ctype: &'static str, file: std::fs::File, len: u64) -> Resp {
        Resp { status, ctype, body: Vec::new(), headers: Vec::new(), stream: Some((file, len)) }
    }
    pub fn json(status: u16, body: String) -> Resp {
        Resp::bytes(status, "application/json; charset=utf-8", body.into_bytes())
    }
    pub fn html(body: String) -> Resp {
        Resp::bytes(200, "text/html; charset=utf-8", body.into_bytes())
    }
    pub fn text(status: u16, body: &str) -> Resp {
        Resp::bytes(status, "text/plain; charset=utf-8", body.as_bytes().to_vec())
    }
    fn len(&self) -> u64 {
        self.stream.as_ref().map(|s| s.1).unwrap_or(self.body.len() as u64)
    }
}

fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        400 => "Bad Request",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        413 => "Payload Too Large",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        _ => "",
    }
}

/// Percent-decode; '+' becomes a space only when `form` is set (query values).
fn pct_decode(s: &str, form: bool) -> String {
    let b = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                let hex = std::str::from_utf8(&b[i + 1..i + 3]).ok().and_then(|h| u8::from_str_radix(h, 16).ok());
                match hex {
                    Some(v) => {
                        out.push(v);
                        i += 3;
                        continue;
                    }
                    None => out.push(b'%'),
                }
            }
            b'+' if form => out.push(b' '),
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

fn parse_request(head: &str) -> Option<Req> {
    let line = head.lines().next()?;
    let mut parts = line.split(' ');
    let method = parts.next()?.to_string();
    let target = parts.next()?;
    let version = parts.next()?;
    if !version.starts_with("HTTP/1.") {
        return None;
    }
    let (raw_path, raw_query) = match target.find('?') {
        Some(i) => (&target[..i], &target[i + 1..]),
        None => (target, ""),
    };
    let path = pct_decode(raw_path, false);
    if !path.starts_with('/') {
        return None;
    }
    let mut query = Vec::new();
    for pair in raw_query.split('&') {
        if pair.is_empty() {
            continue;
        }
        let (k, v) = match pair.find('=') {
            Some(i) => (&pair[..i], &pair[i + 1..]),
            None => (pair, ""),
        };
        query.push((pct_decode(k, true), pct_decode(v, true)));
    }
    let mut headers = Vec::new();
    for l in head.lines().skip(1) {
        if let Some(i) = l.find(':') {
            headers.push((l[..i].trim().to_ascii_lowercase(), l[i + 1..].trim().to_string()));
        }
    }
    Some(Req { method, path, query, headers, body: Vec::new() })
}

/// The host part of a Host / Origin value: `127.0.0.1:8002` -> `127.0.0.1`, `http://emulated.app` -> `emulated.app`.
fn host_of(v: &str) -> String {
    let v = v.trim();
    let v = v.split("://").last().unwrap_or(v);
    let v = v.split('/').next().unwrap_or(v);
    if let Some(rest) = v.strip_prefix('[') {
        return format!("[{}]", rest.split(']').next().unwrap_or(""));
    }
    v.split(':').next().unwrap_or(v).to_ascii_lowercase()
}

/// A request may name this server only as loopback or as one of the four private names. Anything else is a
/// DNS-rebinding attempt (a page on evil.example whose name resolves to 127.0.0.1) or a stranger.
fn host_allowed(host: &str) -> bool {
    matches!(host, "127.0.0.1" | "localhost" | "[::1]") || serving::get().endpoints.iter().any(|e| e.name == host)
}

pub fn handle(ctx: &Ctx, req: &Req) -> Resp {
    if let Some(h) = req.header("host") {
        if !host_allowed(&host_of(h)) {
            return Resp::text(403, "Forbidden host: this server answers 127.0.0.1, localhost and its four private names");
        }
    }
    let route = match routes::find(ctx.side, &req.path) {
        Some(r) => r,
        None => return Resp::text(404, "Not found"),
    };
    let is_read = req.method == "GET" || req.method == "HEAD";
    if !is_read && !(req.method == "POST" && route.post) {
        let mut r = Resp::text(405, if route.post { "method not allowed: GET, HEAD, POST" } else { "method not allowed: this server answers GET and HEAD" });
        r.headers.push(("allow".to_string(), if route.post { "GET, HEAD, POST" } else { "GET, HEAD" }.to_string()));
        return r;
    }
    if req.method == "POST" {
        // A browser page on another origin can fire a form POST at 127.0.0.1. A custom header forces a CORS
        // preflight this server never grants, and a JSON content type cannot come from a plain <form>.
        if req.header("x-c3-write") != Some("1") {
            return Resp::text(403, "missing x-c3-write header");
        }
        if !req.header("content-type").unwrap_or("").to_ascii_lowercase().starts_with("application/json") {
            return Resp::text(403, "content-type must be application/json");
        }
        if let Some(o) = req.header("origin") {
            if !host_allowed(&host_of(o)) {
                return Resp::text(403, "origin not allowed");
            }
        }
    }
    (route.handler)(ctx, req)
}

const HEAD_LIMIT: usize = 16 * 1024;
const BODY_LIMIT: usize = 8 * 1024;

fn serve(ctx: &Ctx, mut stream: TcpStream) {
    let started = Instant::now();
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));
    let peer_ok = stream.peer_addr().map(|a| a.ip().is_loopback()).unwrap_or(false);

    let mut buf: Vec<u8> = Vec::with_capacity(2048);
    let mut chunk = [0u8; 2048];
    let mut head_end = None;
    while buf.len() < HEAD_LIMIT {
        let n = match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => n,
            Err(_) => break,
        };
        buf.extend_from_slice(&chunk[..n]);
        if let Some(i) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            head_end = Some(i + 4);
            break;
        }
    }
    let (req, resp) = if !peer_ok {
        (None, Resp::text(403, "Forbidden: loopback only"))
    } else if head_end.is_none() {
        (None, Resp::text(if buf.len() >= HEAD_LIMIT { 431 } else { 400 }, "Bad request"))
    } else {
        let end = head_end.unwrap();
        let head = String::from_utf8_lossy(&buf[..end]).to_string();
        match parse_request(&head) {
            Some(mut req) => {
                // A body only for POST, and only a small one: it is a JSON switch, not an upload.
                let want = req.header("content-length").and_then(|v| v.parse::<usize>().ok()).unwrap_or(0);
                if req.method == "POST" && want > BODY_LIMIT {
                    (Some(req), Resp::text(413, "body too large"))
                } else {
                    if req.method == "POST" {
                        let mut body = buf[end..].to_vec();
                        while body.len() < want {
                            match stream.read(&mut chunk) {
                                Ok(0) | Err(_) => break,
                                Ok(n) => body.extend_from_slice(&chunk[..n]),
                            }
                        }
                        body.truncate(want);
                        req.body = body;
                    }
                    let resp = handle(ctx, &req);
                    (Some(req), resp)
                }
            }
            None => (None, Resp::text(400, "Bad request")),
        }
    };
    let head_only = req.as_ref().map(|r| r.method == "HEAD").unwrap_or(false);
    let mut out = format!(
        "HTTP/1.1 {} {}\r\ncontent-type: {}\r\ncontent-length: {}\r\nconnection: close\r\n",
        resp.status,
        reason(resp.status),
        resp.ctype,
        resp.len()
    );
    if !resp.headers.iter().any(|(k, _)| k.eq_ignore_ascii_case("cache-control")) {
        out.push_str("cache-control: no-store\r\n");
    }
    for (k, v) in &resp.headers {
        out.push_str(k);
        out.push_str(": ");
        out.push_str(v);
        out.push_str("\r\n");
    }
    out.push_str("\r\n");
    let len = resp.len();
    let _ = stream.write_all(out.as_bytes());
    if !head_only {
        match resp.stream {
            Some((file, n)) => {
                let _ = std::io::copy(&mut file.take(n), &mut stream);
            }
            None => {
                let _ = stream.write_all(&resp.body);
            }
        }
    }
    let _ = stream.flush();
    let (m, p) = match &req {
        Some(r) => (r.method.as_str(), r.path.as_str()),
        None => ("-", "-"),
    };
    println!("{} {} {} {}ms {}B :{}", m, p, resp.status, started.elapsed().as_millis(), len, ctx.port);
}

/// What the app hands the server. The ports come from build.json (build.rs), never from code.
pub struct Config {
    pub drive_port: u16,
    pub diag_port: u16,
    pub root: String,
    /// Where the Engines switches persist (the app's private data dir); None = in memory only.
    pub state_dir: Option<PathBuf>,
}

fn listen(ctx: Arc<Ctx>, listener: TcpListener) {
    std::thread::spawn(move || {
        for incoming in listener.incoming() {
            match incoming {
                Ok(stream) => {
                    let ctx = ctx.clone();
                    std::thread::spawn(move || serve(&ctx, stream));
                }
                Err(e) => eprintln!("accept: {}", e),
            }
        }
    });
}

/// Bind the drive listener (a failure here is the Err), then the diagnostics listener and the DNS socket (a
/// failure there is logged and the rest keeps serving). Returns once the drive socket is listening, so a caller
/// that opens a webview on its URL next never races the bind.
///
/// The root is NOT required to be readable yet: on Android the storage grant arrives after the app has started,
/// and every handler resolves paths per request, so a root that becomes readable later is served from then on.
pub fn start(cfg: Config) -> Result<(), String> {
    routes::check_table().map_err(|e| format!("the route table is invalid: {}", e))?;
    let sv = serving::get();
    if cfg.drive_port != sv.drive_port || cfg.diag_port != sv.diag_port {
        return Err(format!(
            "the app was built with ports {}/{} but build.json::serving declares {}/{}",
            cfg.drive_port, cfg.diag_port, sv.drive_port, sv.diag_port
        ));
    }
    let root = std::fs::canonicalize(&cfg.root).unwrap_or_else(|_| PathBuf::from(&cfg.root));
    let drive = TcpListener::bind((Ipv4Addr::LOCALHOST, cfg.drive_port)).map_err(|e| format!("cannot bind 127.0.0.1:{} ({})", cfg.drive_port, e))?;
    let engines = Arc::new(engines::Engines::new(cfg.state_dir.clone()));
    let binary = std::env::current_exe().map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
    let started = Instant::now();
    let mk = |side: Side, port: u16| Arc::new(Ctx { root: root.clone(), port, started, binary: binary.clone(), side, engines: engines.clone() });
    let pages = routes::ROUTES.iter().filter(|r| r.kind == routes::Kind::Page).count();
    println!(
        "c3-webserver {} on http://127.0.0.1:{}/ root={} routes={} ({} pages) pid={}",
        env!("CARGO_PKG_VERSION"),
        cfg.drive_port,
        root.display(),
        routes::ROUTES.len(),
        pages,
        std::process::id()
    );
    listen(mk(Side::Drive, cfg.drive_port), drive);
    match TcpListener::bind((Ipv4Addr::LOCALHOST, cfg.diag_port)) {
        Ok(l) => {
            println!("c3-webserver diagnostics on http://127.0.0.1:{}/", cfg.diag_port);
            listen(mk(Side::Diag, cfg.diag_port), l);
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
    use std::io::{BufRead, BufReader};

    fn temp_root() -> PathBuf {
        static N: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
        let root = std::env::temp_dir().join(format!("c3ws-main-{}-{}", std::process::id(), N.fetch_add(1, std::sync::atomic::Ordering::SeqCst)));
        std::fs::create_dir_all(root.join("docs")).unwrap();
        std::fs::create_dir_all(root.join("site/_app")).unwrap();
        std::fs::write(root.join("docs/readme.md"), "# hello\nneedle here\n").unwrap();
        std::fs::write(root.join("docs/data.json"), "{\"a\":1,\"list\":[{\"x\":1}]}").unwrap();
        std::fs::write(root.join("docs/conf.yaml"), "a: 1\nb:\n  - c\n").unwrap();
        std::fs::write(root.join("docs/k.secrets"), "{\"token\":\"t\"}").unwrap();
        std::fs::write(root.join("docs/page.html"), "<html><body><p>hi</p></body></html>").unwrap();
        std::fs::write(root.join("docs/route.html"), "<html><body>route</body></html>").unwrap();
        std::fs::write(root.join("docs/blob.bin"), [0u8, 1, 2, 3]).unwrap();
        std::fs::write(root.join("docs/notes.weird"), "plain words").unwrap();
        std::fs::write(root.join("site/200.html"), "<html><head></head><body><script src=\"/_app/a.js\"></script></body></html>").unwrap();
        std::fs::write(root.join("index.html"), "<html><head></head><body><h1>root</h1></body></html>").unwrap();
        std::fs::write(root.join(".hidden"), "x").unwrap();
        std::fs::canonicalize(root).unwrap()
    }

    fn start_side(root: PathBuf, side: Side, engines: Arc<engines::Engines>) -> u16 {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let ctx = Arc::new(Ctx { root, port, started: Instant::now(), binary: "test".to_string(), side, engines });
        listen(ctx, listener);
        port
    }

    fn start_drive(root: PathBuf) -> (u16, Arc<engines::Engines>) {
        let en = Arc::new(engines::Engines::new(None));
        (start_side(root, Side::Drive, en.clone()), en)
    }

    struct Got {
        status: u16,
        headers: String,
        body: String,
    }

    fn call(port: u16, method: &str, target: &str, extra: &str, body: &str) -> Got {
        let mut s = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        let cl = if body.is_empty() { String::new() } else { format!("content-length: {}\r\n", body.len()) };
        write!(s, "{} {} HTTP/1.1\r\nhost: 127.0.0.1:{}\r\n{}{}\r\n{}", method, target, port, extra, cl, body).unwrap();
        let mut r = BufReader::new(s);
        let mut status_line = String::new();
        r.read_line(&mut status_line).unwrap();
        let status: u16 = status_line.split(' ').nth(1).unwrap().parse().unwrap();
        let mut headers = String::new();
        loop {
            let mut l = String::new();
            r.read_line(&mut l).unwrap();
            if l == "\r\n" || l.is_empty() {
                break;
            }
            headers.push_str(&l);
        }
        let mut raw = Vec::new();
        r.read_to_end(&mut raw).unwrap();
        Got { status, headers: headers.to_lowercase(), body: String::from_utf8_lossy(&raw).to_string() }
    }

    fn get(port: u16, target: &str) -> Got {
        call(port, "GET", target, "", "")
    }

    fn get_html(port: u16, target: &str) -> Got {
        call(port, "GET", target, "accept: text/html,application/xhtml+xml\r\n", "")
    }

    fn post(port: u16, target: &str, body: &str) -> Got {
        call(port, "POST", target, "x-c3-write: 1\r\ncontent-type: application/json\r\n", body)
    }

    #[test]
    fn end_to_end_every_declared_route_answers() {
        let root = temp_root();
        let (port, _en) = start_drive(root.clone());

        let r = get(port, "/__api__/routes");
        assert_eq!(r.status, 200);
        assert!(r.headers.contains("application/json"));
        for rt in routes::ROUTES {
            assert!(r.body.contains(&format!("\"path\":{}", routes::jstr(rt.path))), "{} not in /__api__/routes", rt.path);
        }

        for rt in routes::ROUTES {
            if rt.path == routes::CATCH_ALL || rt.post || rt.on == Side::Diag {
                continue;
            }
            let target = match rt.path {
                "/__api__/ls" => "/__api__/ls?path=/docs".to_string(),
                "/__api__/read" => "/__api__/read?path=/docs/readme.md".to_string(),
                "/__api__/search" => "/__api__/search?q=readme".to_string(),
                p => p.to_string(),
            };
            let g = get(port, &target);
            assert_eq!(g.status, 200, "{} must answer 200", target);
            let want = match rt.kind {
                routes::Kind::Page => "text/html",
                routes::Kind::Api => "application/json",
                routes::Kind::Asset => "text/",
            };
            assert!(g.headers.contains(want), "{} must be {} but is {}", target, want, g.headers);
        }

        // The catch-all: a directory browses, a file streams, an escape is 404, an undeclared api path is 404.
        let g = get(port, "/docs/");
        assert_eq!(g.status, 200);
        assert!(g.body.contains("File Browser") && g.body.contains("const INIT_PATH = \"/docs/\""), "a directory is the file browser opened there");
        let g = get(port, "/docs/notes.weird");
        assert!(g.headers.contains("text/plain") && g.body.contains("plain words"), "unknown extension sniffed as text");
        assert!(get(port, "/docs/blob.bin").headers.contains("application/octet-stream"));
        assert!(get(port, "/").body.contains("<nav"), "/ is the shell");
        assert_eq!(get(port, "/nope").status, 404);
        assert_eq!(get(port, "/__api__/nope").status, 404);
        assert_eq!(get(port, "/../../../etc/passwd").status, 404);
        assert_eq!(call(port, "POST", "/__api__/routes", "", "").status, 405);
        let h = call(port, "HEAD", "/__api__/health", "", "");
        assert_eq!(h.status, 200);
        assert!(h.headers.contains("content-length"));
        assert!(h.body.is_empty(), "HEAD carries no body");

        // Dotfiles hidden unless asked, search reports the flag header.
        assert!(!get(port, "/__api__/ls?path=/").body.contains(".hidden"));
        assert!(get(port, "/__api__/ls?path=/&dot=1").body.contains(".hidden"));
        let s = get(port, "/__api__/search?q=needle&mode=content");
        assert!(s.body.contains("docs/readme.md"));
        assert!(s.headers.contains("x-search-partial: 0"));
        let a = get(port, "/__api__/about");
        assert_eq!(a.status, 200);
        assert!(a.body.contains("\"macro\":") && a.body.contains("\"sections\":") && a.body.contains("\"title\":"), "/__api__/about is macro groups of sections");

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn the_render_engines_turn_files_into_pages_with_bundled_scripts() {
        let root = temp_root();
        let (port, _en) = start_drive(root.clone());

        let md = get_html(port, "/docs/readme.md");
        assert!(md.headers.contains("text/html") && md.body.contains("marked.parse") && md.body.contains("/__lib__/eruda.min.js"));
        assert!(!md.body.contains("jsdelivr"), "no CDN");
        let raw = get_html(port, "/docs/readme.md?raw");
        assert!(raw.headers.contains("text/plain") && raw.body.starts_with("# hello"));

        let j = get_html(port, "/docs/data.json");
        assert!(j.body.contains("renderArrayTable") && j.body.contains("const fmt = \"json\""), "JSON viewer");
        let j_plain = get(port, "/docs/data.json");
        assert!(j_plain.headers.contains("application/json") && j_plain.body.starts_with('{'), "a fetch without Accept: text/html gets the JSON");
        assert!(get_html(port, "/docs/data.json?raw").body.starts_with('{'));
        let y = get_html(port, "/docs/conf.yaml");
        assert!(y.body.contains("/__lib__/js-yaml.min.js") && y.body.contains("const fmt = \"yaml\""), "YAML viewer");
        let sec = get_html(port, "/docs/k.secrets");
        assert!(sec.body.contains("const isSecret = true"), "secrets table");

        let h = get(port, "/docs/page.html");
        assert!(h.body.contains("<p>hi</p>") && h.body.contains("eruda.init()"), "Eruda injected into HTML");
        assert!(get(port, "/docs/route").body.contains("route"), "/route falls back to route.html");
        let idx = get(port, "/");
        assert!(idx.body.contains("<nav"), "the shell is / on the drive listener");

        // The SPA fallback: an unknown path inside a project with a 200.html.
        let spa = get(port, "/site/deep/route");
        assert_eq!(spa.status, 200);
        assert!(spa.body.contains("src=\"/site/_app/a.js\"") && spa.body.contains("eruda.init()"), "{}", spa.body);

        // The bundled libraries are served from the binary.
        for (p, needle) in [("/__lib__/marked.min.js", "marked"), ("/__lib__/js-yaml.min.js", "js-yaml"), ("/__lib__/eruda.min.js", "eruda"), ("/__lib__/github-markdown-dark.css", "markdown-body")] {
            let g = get(port, p);
            assert_eq!(g.status, 200, "{}", p);
            assert!(g.body.contains(needle), "{} does not look like {}", p, needle);
            assert!(g.headers.contains("max-age=86400"));
        }
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_directory_with_an_index_serves_it_with_a_base_and_the_browser_otherwise() {
        let root = temp_root();
        let (port, en) = start_drive(root.clone());
        std::fs::create_dir_all(root.join("app")).unwrap();
        std::fs::write(root.join("app/index.html"), "<html><head><title>a</title></head><body>app</body></html>").unwrap();
        let g = get(port, "/app/");
        assert!(g.body.contains("<base href=\"/app/\">") && g.body.contains("eruda.init()"));
        en.set("browse", false).unwrap();
        let g = get(port, "/docs/");
        assert!(g.body.contains("Index of /docs/") && g.body.contains("readme.md"), "browse off: the plain index");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn every_switch_acts_on_its_handler() {
        let root = temp_root();
        let (port, en) = start_drive(root.clone());
        let off = |id: &str| en.set(id, false).unwrap();

        off("eruda");
        assert!(!get(port, "/docs/page.html").body.contains("eruda"));
        assert!(!get_html(port, "/docs/readme.md").body.contains("eruda"));
        off("markdown");
        let g = get_html(port, "/docs/readme.md");
        assert!(g.headers.contains("text/plain"), "markdown off: the source is sent as text");
        off("json");
        assert!(get_html(port, "/docs/data.json").headers.contains("application/json"), "json off: the file as it is");
        off("yaml");
        assert!(get_html(port, "/docs/conf.yaml").headers.contains("text/plain"));
        off("secrets");
        assert!(get_html(port, "/docs/k.secrets").headers.contains("text/plain"));
        off("html");
        assert!(get(port, "/docs/page.html").body.starts_with("<html><body><p>hi"), "html off: no injection");
        assert_eq!(get(port, "/docs/route").status, 404, "html off: no /route fallback");
        off("spa-fallback");
        assert_eq!(get(port, "/site/deep/route").status, 404);
        off("search");
        assert_eq!(get(port, "/__api__/search?q=readme").status, 404);
        off("preview");
        assert_eq!(get(port, "/__api__/read?path=/docs/readme.md").status, 404);
        off("dotfiles");
        assert!(!get(port, "/__api__/ls?path=/&dot=1").body.contains(".hidden"), "dotfiles off: dot=1 is ignored");
        off("browse");
        assert_eq!(get(port, "/__browse__").status, 404);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn the_switch_and_the_opener_are_guarded_posts() {
        let root = temp_root();
        let (port, en) = start_drive(root.clone());
        let body = "{\"id\":\"markdown\",\"on\":false}";
        // each guard alone refuses
        assert_eq!(call(port, "POST", "/__api__/engines", "content-type: application/json\r\n", body).status, 403, "no custom header");
        assert_eq!(call(port, "POST", "/__api__/engines", "x-c3-write: 1\r\ncontent-type: text/plain\r\n", body).status, 403, "not JSON");
        assert_eq!(call(port, "POST", "/__api__/engines", "x-c3-write: 1\r\ncontent-type: application/json\r\norigin: https://evil.example\r\n", body).status, 403, "foreign origin");
        assert!(en.on("markdown"));
        // a page of ours, on a private name or loopback, is allowed
        let ok = call(port, "POST", "/__api__/engines", "x-c3-write: 1\r\ncontent-type: application/json\r\norigin: http://emulated.app\r\n", body);
        assert_eq!(ok.status, 200);
        assert!(!en.on("markdown") && ok.body.contains("\"id\":\"markdown\""));
        assert_eq!(post(port, "/__api__/engines", "{\"id\":\"dns\",\"on\":false}").status, 400, "a core engine has no switch");
        assert_eq!(post(port, "/__api__/engines", "nope").status, 400);
        // POST is refused everywhere else
        assert_eq!(post(port, "/__api__/ls", "{}").status, 405);
        // the opener: an unknown id is 404, and the URL is never the request's
        assert_eq!(post(port, "/__api__/open", "{\"id\":\"evil\"}").status, 404);
        let opened = std::sync::Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
        let o2 = opened.clone();
        diag::set_opener(Box::new(move |u| {
            o2.lock().unwrap().push(u.to_string());
            Ok(())
        }));
        let g = post(port, "/__api__/open", "{\"id\":\"emulated\",\"via\":\"name\",\"url\":\"http://evil.example/\"}");
        assert_eq!(g.status, 200, "{}", g.body);
        let g = post(port, "/__api__/open", "{\"id\":\"cloud\",\"via\":\"ip\"}");
        assert_eq!(g.status, 200);
        let seen = opened.lock().unwrap().clone();
        assert!(seen.contains(&"http://emulated.app/".to_string()) && seen.contains(&"http://127.0.0.1:8003/".to_string()), "{:?}", seen);
        assert!(!seen.iter().any(|u| u.contains("evil")));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_foreign_host_header_is_refused_whatever_the_path() {
        let root = temp_root();
        let (port, _en) = start_drive(root.clone());
        let mut s = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        write!(s, "GET /__api__/health HTTP/1.1\r\nhost: evil.example\r\n\r\n").unwrap();
        let mut out = String::new();
        s.read_to_string(&mut out).unwrap();
        assert!(out.starts_with("HTTP/1.1 403"), "{}", out);
        for h in ["127.0.0.1:8002", "localhost:8002", "emulated.app", "cloud.app:80", "[::1]:8002"] {
            assert!(host_allowed(&host_of(h)), "{} must be allowed", h);
        }
        for h in ["termux.app.evil.com", "evil.example", "app", "10.0.0.2:8002"] {
            assert!(!host_allowed(&host_of(h)), "{} must be refused", h);
        }
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn the_diagnostics_listener_serves_the_bridge_and_nothing_else() {
        let root = temp_root();
        let en = Arc::new(engines::Engines::new(None));
        let port = start_side(root.clone(), Side::Diag, en.clone());
        diag::set_source(Box::new(|m, a| match m {
            "about" => Ok(("application/json".to_string(), "{\"app\":{\"versionName\":\"9.9\"}}".to_string())),
            "logcat" => Ok(("text/plain".to_string(), format!("I/Tag: arg={}\n", a))),
            "diagnose" => Ok(("application/json".to_string(), "{\"device\":\"stub\"}".to_string())),
            _ => Err("unknown".to_string()),
        }));

        let page = get(port, "/");
        assert_eq!(page.status, 200);
        assert!(page.body.contains("Fleet diagnostics") && page.body.contains("/__api__/diag/logcat"));
        assert!(get(port, "/__api__/diag/about").body.contains("\"versionName\":\"9.9\""));
        assert!(get(port, "/__api__/diag/diagnose").body.contains("stub"));
        let lc = get(port, "/__api__/diag/logcat?n=50&scope=all&errors=1");
        assert!(lc.headers.contains("text/plain") && lc.body.contains("arg=n=50&scope=all&errors=1"), "{}", lc.body);
        assert!(get(port, "/__api__/diag/logcat?n=999999").body.contains("n=5000"), "n is clamped");

        // Files, the shell, search, the engines switch and the drive's root are NOT on this port.
        for p in ["/docs/readme.md", "/index.html", "/__tab__/files", "/__api__/ls?path=/", "/__api__/read?path=/docs/readme.md", "/__api__/search?q=a", "/__api__/engines", "/__lib__/eruda.min.js", "/__browse__", "/.hidden"] {
            assert_eq!(get(port, p).status, 404, "{} must not be served on the diagnostics port", p);
        }
        assert_eq!(post(port, "/__api__/engines", "{}").status, 404);
        let h = get(port, "/__api__/health");
        assert!(h.body.contains("\"root\":null"), "the diagnostics port does not say where the drive is: {}", h.body);

        en.set("diag", false).unwrap();
        let g = get(port, "/__api__/diag/about");
        assert_eq!(g.status, 503);
        assert!(g.body.contains("switched off"));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn the_served_map_is_reachable_through_serving_json() {
        let root = temp_root();
        let (port, _en) = start_drive(root.clone());
        let g = get(port, "/__api__/serving");
        assert_eq!(g.status, 200);
        for n in ["termux.app", "nix.app", "emulated.app", "cloud.app", "http://127.0.0.1:8003/", "\"port\":5353"] {
            assert!(g.body.contains(n), "{} missing from /__api__/serving", n);
        }
        let e = get(port, "/__api__/engines");
        assert!(e.body.contains("\"id\":\"markdown\"") && e.body.contains("\"group\":\"not-portable\""));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_route_added_to_the_table_shows_in_the_json_and_a_removed_one_does_not() {
        // The tabs render /__api__/routes verbatim, so this is the mutation
        // pair "add a route and the tab shows it; remove one and it disappears"
        // at the layer the tabs read. The real table is const; the serialiser
        // is exercised on a copy with one row more and one row fewer.
        let planted = routes::Route {
            kind: routes::Kind::Api,
            path: "/__api__/planted",
            tab: None,
            on: Side::Drive,
            post: false,
            summary: "planted by the test",
            handler: |_c, _r| Resp::text(200, "planted"),
        };
        let real = routes::routes_json();
        assert!(real.contains("\"path\":\"/__api__/health\""));
        assert!(!real.contains("/__api__/planted"));
        let more = routes::routes_json_of(routes::ROUTES.iter().chain(std::iter::once(&planted)));
        assert!(more.contains("\"path\":\"/__api__/planted\""), "a row added to the table appears in the JSON");
        let fewer = routes::routes_json_of(routes::ROUTES.iter().filter(|r| r.path != "/__api__/health"));
        assert!(!fewer.contains("/__api__/health"), "a row removed from the table disappears from the JSON");
        assert_eq!(fewer.matches("\"path\":").count(), routes::ROUTES.len() - 1);
    }

    #[test]
    fn start_refuses_a_build_whose_ports_drifted_from_build_json() {
        let sv = serving::get();
        let r = start(Config { drive_port: sv.drive_port + 1, diag_port: sv.diag_port, root: "/".to_string(), state_dir: None });
        assert!(r.unwrap_err().contains("build.json"));
    }

    #[test]
    fn request_parsing() {
        let r = parse_request("GET /a%20b/c?x=1&y=hello+world&z HTTP/1.1\r\nHost: h\r\nAccept: text/html\r\n\r\n").unwrap();
        assert_eq!(r.method, "GET");
        assert_eq!(r.path, "/a b/c");
        assert_eq!(r.q("x"), Some("1"));
        assert_eq!(r.q("y"), Some("hello world"));
        assert_eq!(r.q("z"), Some(""));
        assert_eq!(r.header("accept"), Some("text/html"));
        assert_eq!(r.header("host"), Some("h"));
        assert!(parse_request("GARBAGE\r\n\r\n").is_none());
        assert!(parse_request("GET nope HTTP/1.1\r\n\r\n").is_none());
    }
}
