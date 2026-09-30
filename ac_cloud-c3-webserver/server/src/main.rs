//! c3-webserver — the phone's local web server, natively on Android.
//!
//! `c3-webserver <port> <root>`: binds 127.0.0.1:<port> only, serves <root>,
//! answers GET and HEAD, one thread per connection, and refuses to start on a
//! route table that is not well-formed (see routes.rs). Static-musl, so it runs
//! from the APK's nativeLibraryDir with no loader, no libc from a rootfs and no
//! proot in front of it.

mod about;
mod routes;

use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::time::{Duration, Instant};

pub struct Ctx {
    pub root: PathBuf,
    pub port: u16,
    pub started: Instant,
    pub binary: String,
}

pub struct Req {
    pub method: String,
    pub path: String,
    pub query: Vec<(String, String)>,
}

impl Req {
    pub fn q(&self, key: &str) -> Option<&str> {
        self.query.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    }
}

pub struct Resp {
    pub status: u16,
    pub ctype: &'static str,
    pub body: Vec<u8>,
    pub headers: Vec<(String, String)>,
}

impl Resp {
    pub fn bytes(status: u16, ctype: &'static str, body: Vec<u8>) -> Resp {
        Resp { status, ctype, body, headers: Vec::new() }
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
    Some(Req { method, path, query })
}

pub fn handle(ctx: &Ctx, req: &Req) -> Resp {
    if req.method != "GET" && req.method != "HEAD" {
        let mut r = Resp::text(405, "method not allowed: this server answers GET and HEAD");
        r.headers.push(("allow".to_string(), "GET, HEAD".to_string()));
        return r;
    }
    let route = routes::find(&req.path);
    (route.handler)(ctx, req)
}

const HEAD_LIMIT: usize = 16 * 1024;

fn serve(ctx: &Ctx, mut stream: TcpStream) {
    let started = Instant::now();
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));
    let peer_ok = stream.peer_addr().map(|a| a.ip().is_loopback()).unwrap_or(false);

    let mut buf: Vec<u8> = Vec::with_capacity(2048);
    let mut chunk = [0u8; 2048];
    let mut complete = false;
    while buf.len() < HEAD_LIMIT {
        let n = match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => n,
            Err(_) => break,
        };
        buf.extend_from_slice(&chunk[..n]);
        if buf.windows(4).any(|w| w == b"\r\n\r\n") {
            complete = true;
            break;
        }
    }
    let head = String::from_utf8_lossy(&buf).to_string();
    let (req, resp) = if !peer_ok {
        (None, Resp::text(403, "Forbidden: loopback only"))
    } else if !complete {
        (None, Resp::text(if buf.len() >= HEAD_LIMIT { 431 } else { 400 }, "Bad request"))
    } else {
        match parse_request(&head) {
            Some(req) => {
                let resp = handle(ctx, &req);
                (Some(req), resp)
            }
            None => (None, Resp::text(400, "Bad request")),
        }
    };
    let head_only = req.as_ref().map(|r| r.method == "HEAD").unwrap_or(false);
    let mut out = format!(
        "HTTP/1.1 {} {}\r\ncontent-type: {}\r\ncontent-length: {}\r\ncache-control: no-store\r\nconnection: close\r\n",
        resp.status,
        reason(resp.status),
        resp.ctype,
        resp.body.len()
    );
    for (k, v) in &resp.headers {
        out.push_str(k);
        out.push_str(": ");
        out.push_str(v);
        out.push_str("\r\n");
    }
    out.push_str("\r\n");
    let _ = stream.write_all(out.as_bytes());
    if !head_only {
        let _ = stream.write_all(&resp.body);
    }
    let _ = stream.flush();
    let (m, p) = match &req {
        Some(r) => (r.method.as_str(), r.path.as_str()),
        None => ("-", "-"),
    };
    println!("{} {} {} {}ms {}B", m, p, resp.status, started.elapsed().as_millis(), resp.body.len());
}

fn usage() -> ! {
    eprintln!("usage: c3-webserver <port> <root>");
    std::process::exit(2)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        usage();
    }
    let port: u16 = match args[1].parse() {
        Ok(p) => p,
        Err(_) => usage(),
    };
    let root = match std::fs::canonicalize(&args[2]) {
        Ok(r) if r.is_dir() => r,
        _ => {
            eprintln!("c3-webserver did not start: root {} is not a readable directory", args[2]);
            std::process::exit(1);
        }
    };
    if let Err(e) = routes::check_table() {
        eprintln!("c3-webserver did not start: the route table is invalid: {}", e);
        std::process::exit(1);
    }
    let listener = match TcpListener::bind((Ipv4Addr::LOCALHOST, port)) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("c3-webserver did not start: cannot bind 127.0.0.1:{} ({})", port, e);
            eprintln!("  if the port is taken, start with another: c3-webserver <port> <root>");
            std::process::exit(1);
        }
    };
    let ctx = std::sync::Arc::new(Ctx {
        root,
        port,
        started: Instant::now(),
        binary: std::env::current_exe().map(|p| p.to_string_lossy().to_string()).unwrap_or_else(|_| args[0].clone()),
    });
    let pages = routes::ROUTES.iter().filter(|r| r.kind == routes::Kind::Page).count();
    let apis = routes::ROUTES.len() - pages;
    println!(
        "c3-webserver {} on http://127.0.0.1:{}/ root={} routes={} ({} pages, {} api) pid={}",
        env!("CARGO_PKG_VERSION"),
        port,
        ctx.root.display(),
        routes::ROUTES.len(),
        pages,
        apis,
        std::process::id()
    );
    for incoming in listener.incoming() {
        match incoming {
            Ok(stream) => {
                let ctx = ctx.clone();
                std::thread::spawn(move || serve(&ctx, stream));
            }
            Err(e) => eprintln!("accept: {}", e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};

    fn temp_root() -> PathBuf {
        let root = std::env::temp_dir().join(format!("c3ws-main-{}-{}", std::process::id(), Instant::now().elapsed().as_nanos()));
        std::fs::create_dir_all(root.join("docs")).unwrap();
        std::fs::write(root.join("docs/readme.md"), "# hello\nneedle here\n").unwrap();
        std::fs::write(root.join("index.html"), "<h1>root</h1>").unwrap();
        std::fs::write(root.join(".hidden"), "x").unwrap();
        std::fs::canonicalize(root).unwrap()
    }

    fn start(root: PathBuf) -> (u16, std::sync::Arc<Ctx>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let ctx = std::sync::Arc::new(Ctx { root, port, started: Instant::now(), binary: "test".to_string() });
        let c2 = ctx.clone();
        std::thread::spawn(move || {
            for s in listener.incoming().flatten() {
                let c = c2.clone();
                std::thread::spawn(move || serve(&c, s));
            }
        });
        (port, ctx)
    }

    fn get(port: u16, target: &str) -> (u16, String, String) {
        get_method(port, "GET", target)
    }

    fn get_method(port: u16, method: &str, target: &str) -> (u16, String, String) {
        let mut s = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        write!(s, "{} {} HTTP/1.1\r\nhost: x\r\n\r\n", method, target).unwrap();
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
        let mut body = String::new();
        r.read_to_string(&mut body).unwrap();
        (status, headers.to_lowercase(), body)
    }

    #[test]
    fn end_to_end_every_declared_route_answers() {
        let root = temp_root();
        let (port, _ctx) = start(root.clone());

        let (st, h, body) = get(port, "/__api__/routes");
        assert_eq!(st, 200);
        assert!(h.contains("application/json"));
        for r in routes::ROUTES {
            assert!(body.contains(&format!("\"path\":{}", routes::jstr(r.path))), "{} not in /__api__/routes", r.path);
        }

        for r in routes::ROUTES {
            if r.path == routes::CATCH_ALL {
                continue;
            }
            let target = match r.path {
                "/__api__/ls" => "/__api__/ls?path=/docs".to_string(),
                "/__api__/read" => "/__api__/read?path=/docs/readme.md".to_string(),
                "/__api__/search" => "/__api__/search?q=readme".to_string(),
                p => p.to_string(),
            };
            let (st, h, _) = get(port, &target);
            assert_eq!(st, 200, "{} must answer 200", target);
            let want = if r.kind == routes::Kind::Page { "text/html" } else { "application/json" };
            assert!(h.contains(want), "{} must be {}", target, want);
        }

        // The catch-all: a directory lists, a file streams, an escape is 404, an undeclared api path is 404.
        let (st, _, body) = get(port, "/docs/");
        assert_eq!(st, 200);
        assert!(body.contains("readme.md"));
        let (st, h, body) = get(port, "/docs/readme.md");
        assert_eq!(st, 200);
        assert!(h.contains("text/plain"));
        assert!(body.contains("needle"));
        assert_eq!(get(port, "/").2.contains("<nav"), true, "/ is the shell");
        assert_eq!(get(port, "/nope").0, 404);
        assert_eq!(get(port, "/__api__/nope").0, 404);
        assert_eq!(get(port, "/../../../etc/passwd").0, 404);
        assert_eq!(get_method(port, "POST", "/__api__/routes").0, 405);
        let (st, h, body) = get_method(port, "HEAD", "/__api__/health");
        assert_eq!(st, 200);
        assert!(h.contains("content-length"));
        assert!(body.is_empty(), "HEAD carries no body");

        // Dotfiles hidden unless asked, search reports the flag header.
        let (_, _, body) = get(port, "/__api__/ls?path=/");
        assert!(!body.contains(".hidden"));
        let (_, _, body) = get(port, "/__api__/ls?path=/&dot=1");
        assert!(body.contains(".hidden"));
        let (_, h, body) = get(port, "/__api__/search?q=needle&mode=content");
        assert!(body.contains("docs/readme.md"));
        assert!(h.contains("x-search-partial: 0"));
        let (st, _, body) = get(port, "/__api__/about");
        assert_eq!(st, 200);
        assert!(body.contains("\"title\":"));

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
    fn request_parsing() {
        let r = parse_request("GET /a%20b/c?x=1&y=hello+world&z HTTP/1.1\r\nhost: h\r\n\r\n").unwrap();
        assert_eq!(r.method, "GET");
        assert_eq!(r.path, "/a b/c");
        assert_eq!(r.q("x"), Some("1"));
        assert_eq!(r.q("y"), Some("hello world"));
        assert_eq!(r.q("z"), Some(""));
        assert!(parse_request("GARBAGE\r\n\r\n").is_none());
        assert!(parse_request("GET nope HTTP/1.1\r\n\r\n").is_none());
    }
}
