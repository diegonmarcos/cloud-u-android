//! POST /__api__/write: the Linux write API's guards, one test each. Every server here is started
//! with an EXPLICIT WriteCfg (not the environment), so the tests cannot race on HTTPD_WRITE*.
use fleet_webserver::write::WriteCfg;
use fleet_webserver::{handler_with, http, Config};
use std::fs;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};

const TOKEN: &str = "s3cret-token";

struct Srv {
    port: u16,
    root: PathBuf,
}

fn tmp(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("fws-write-{}-{}", tag, std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    fs::canonicalize(&d).unwrap()
}

/// A served root holding `store/` (writable) and `private/` (readable only), a server on it.
fn serve(tag: &str, enabled: bool, roots: &[&str], token: &str) -> Srv {
    let root = tmp(tag);
    fs::create_dir_all(root.join("store")).unwrap();
    fs::create_dir_all(root.join("private")).unwrap();
    fs::write(root.join("private/keep.txt"), "original").unwrap();
    let cfg = Config { port: 0, root: root.to_string_lossy().to_string(), extra_hosts: vec![] };
    let w = WriteCfg::new(enabled, &root, roots.iter().copied(), token.to_string());
    let l = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let port = l.local_addr().unwrap().port();
    http::listen(l, handler_with(&cfg, w));
    Srv { port, root }
}

struct Got {
    status: u16,
    body: String,
}

fn raw(port: u16, head_extra: &str, body: &str, declared_len: Option<usize>, method: &str) -> Got {
    let mut s = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
    let len = declared_len.unwrap_or(body.len());
    write!(s, "{} /__api__/write HTTP/1.1\r\nhost: 127.0.0.1:{}\r\nconnection: close\r\ncontent-length: {}\r\n{}\r\n", method, port, len, head_extra).unwrap();
    s.write_all(body.as_bytes()).unwrap();
    let mut out = Vec::new();
    s.read_to_end(&mut out).unwrap();
    let text = String::from_utf8_lossy(&out).to_string();
    let status = text.split(' ').nth(1).unwrap().parse().unwrap();
    Got { status, body: text.split("\r\n\r\n").nth(1).unwrap_or("").to_string() }
}

const GOOD: &str = "x-httpd-write: 1\r\ncontent-type: application/json\r\nx-httpd-token: s3cret-token\r\n";

fn post(s: &Srv, headers: &str, body: &str) -> Got {
    raw(s.port, headers, body, None, "POST")
}

fn get(s: &Srv, target: &str) -> Got {
    let mut c = TcpStream::connect((Ipv4Addr::LOCALHOST, s.port)).unwrap();
    write!(c, "GET {} HTTP/1.1\r\nhost: 127.0.0.1:{}\r\nconnection: close\r\n\r\n", target, s.port).unwrap();
    let mut out = Vec::new();
    c.read_to_end(&mut out).unwrap();
    let text = String::from_utf8_lossy(&out).to_string();
    Got { status: text.split(' ').nth(1).unwrap().parse().unwrap(), body: text.split("\r\n\r\n").nth(1).unwrap_or("").to_string() }
}

fn read(p: &Path) -> String {
    fs::read_to_string(p).unwrap()
}

#[test]
fn off_by_default() {
    let s = serve("off", false, &["store"], TOKEN);
    let g = post(&s, GOOD, r#"{"path":"store/a.txt","content":"x"}"#);
    assert_eq!(g.status, 403);
    assert!(g.body.contains("write mode disabled"), "{}", g.body);
    assert!(!s.root.join("store/a.txt").exists());
}

#[test]
fn enabled_with_no_roots_writes_nothing() {
    let s = serve("noroots", true, &[], TOKEN);
    let g = post(&s, GOOD, r#"{"path":"store/a.txt","content":"x"}"#);
    assert_eq!(g.status, 403);
    assert!(g.body.contains("no write roots configured"), "{}", g.body);
}

#[test]
fn a_write_root_outside_the_served_root_is_dropped() {
    // `../x` leaves the served root under either reading: nothing is writable.
    let s = serve("outside-root", true, &["../x", "../../etc"], TOKEN);
    let g = post(&s, GOOD, r#"{"path":"store/a.txt","content":"x"}"#);
    assert_eq!(g.status, 403);
    assert!(g.body.contains("no write roots configured"), "{}", g.body);
}

#[test]
fn an_absolute_entry_outside_the_root_reads_as_root_relative_like_linux() {
    // Linux tries the absolute reading first and falls back to <root>/<entry>: "/etc" can only ever
    // allowlist <root>/etc, never the machine's /etc.
    let s = serve("abs-entry", true, &["/etc"], TOKEN);
    let g = post(&s, GOOD, r#"{"path":"etc/x.txt","content":"x"}"#);
    assert_eq!(g.status, 200, "{}", g.body);
    assert_eq!(read(&s.root.join("etc/x.txt")), "x");
    let g = post(&s, GOOD, r#"{"path":"../../../../../etc/pwn.txt","content":"x"}"#);
    assert_eq!(g.status, 403);
    assert!(!Path::new("/etc/pwn.txt").exists() && !Path::new("/etc/x.txt").exists());
}

#[test]
fn only_post_is_accepted() {
    let s = serve("method", true, &["store"], TOKEN);
    assert_eq!(raw(s.port, GOOD, "", None, "GET").status, 405);
    assert_eq!(raw(s.port, GOOD, "", None, "PUT").status, 405);
    assert_eq!(raw(s.port, GOOD, "", None, "OPTIONS").status, 405);
    // POST anywhere else is refused outright.
    let mut c = TcpStream::connect((Ipv4Addr::LOCALHOST, s.port)).unwrap();
    write!(c, "POST /store/a.txt HTTP/1.1\r\nhost: 127.0.0.1\r\nconnection: close\r\ncontent-length: 0\r\n\r\n").unwrap();
    let mut out = String::new();
    c.read_to_string(&mut out).unwrap();
    assert!(out.starts_with("HTTP/1.1 405"), "{}", out);
}

#[test]
fn csrf_header_content_type_and_origin_are_required() {
    let s = serve("csrf", true, &["store"], TOKEN);
    let body = r#"{"path":"store/a.txt","content":"x"}"#;
    let g = post(&s, "content-type: application/json\r\nx-httpd-token: s3cret-token\r\n", body);
    assert_eq!((g.status, g.body.contains("missing x-httpd-write header")), (403, true), "{}", g.body);
    let g = post(&s, "x-httpd-write: 1\r\nx-httpd-token: s3cret-token\r\ncontent-type: text/plain\r\n", body);
    assert_eq!((g.status, g.body.contains("content-type must be application/json")), (403, true), "{}", g.body);
    let g = post(&s, &format!("{}origin: https://evil.example\r\n", GOOD), body);
    assert_eq!((g.status, g.body.contains("origin not allowed")), (403, true), "{}", g.body);
    let g = post(&s, &format!("{}origin: null\r\n", GOOD), body);
    assert_eq!((g.status, g.body.contains("invalid origin header")), (403, true), "{}", g.body);
    assert!(!s.root.join("store/a.txt").exists());
    let g = post(&s, &format!("{}origin: http://localhost:8000\r\n", GOOD), body);
    assert_eq!(g.status, 200, "{}", g.body);
    let g = post(&s, &format!("{}origin: http://127.0.0.1:8001\r\n", GOOD), body);
    assert_eq!(g.status, 200, "{}", g.body);
}

#[test]
fn no_token_configured_means_no_writes() {
    let s = serve("notoken", true, &["store"], "");
    let g = post(&s, GOOD, r#"{"path":"store/a.txt","content":"x"}"#);
    assert_eq!(g.status, 403);
    assert!(g.body.contains("write token not configured"), "{}", g.body);
    assert!(!s.root.join("store/a.txt").exists());
}

#[test]
fn a_wrong_or_missing_token_is_refused_and_the_right_one_writes() {
    let s = serve("token", true, &["store"], TOKEN);
    let body = r#"{"path":"store/a.txt","content":"hello"}"#;
    for bad in ["x-httpd-token: nope\r\n", "x-httpd-token: s3cret-toke\r\n", "x-httpd-token: s3cret-token-and-more\r\n", ""] {
        let g = post(&s, &format!("x-httpd-write: 1\r\ncontent-type: application/json\r\n{}", bad), body);
        assert_eq!((g.status, g.body.contains("invalid write token")), (403, true), "{} / {}", bad, g.body);
    }
    assert!(!s.root.join("store/a.txt").exists());
    let g = post(&s, GOOD, body);
    assert_eq!(g.status, 200, "{}", g.body);
    assert_eq!(g.body, r#"{"ok":true,"path":"store/a.txt","bytes":5}"#);
    assert_eq!(read(&s.root.join("store/a.txt")), "hello");
}

#[test]
fn json_escapes_binary_b64_and_new_directories() {
    let s = serve("content", true, &["store"], TOKEN);
    let g = post(&s, GOOD, r#"{"path":"store/deep/er/t.txt","content":"l1\nl2 é 😀 \"q\""}"#);
    assert_eq!(g.status, 200, "{}", g.body);
    assert_eq!(read(&s.root.join("store/deep/er/t.txt")), "l1\nl2 é 😀 \"q\"");
    let g = post(&s, GOOD, r#"{"path":"/store/bin.dat","b64":"AAEC/+8="}"#);
    assert_eq!(g.status, 200, "{}", g.body);
    assert_eq!(fs::read(s.root.join("store/bin.dat")).unwrap(), vec![0, 1, 2, 255, 239]);
    assert!(g.body.contains(r#""bytes":5"#));
    let g = post(&s, GOOD, r#"{"path":"store/bad.dat","b64":"a$b"}"#);
    assert_eq!((g.status, g.body.contains("invalid base64")), (400, true), "{}", g.body);
    // an overwrite replaces
    post(&s, GOOD, r#"{"path":"store/t2.txt","content":"first and long"}"#);
    post(&s, GOOD, r#"{"path":"store/t2.txt","content":"2nd"}"#);
    assert_eq!(read(&s.root.join("store/t2.txt")), "2nd");
}

#[test]
fn the_body_must_name_a_path_and_exactly_one_payload() {
    let s = serve("shape", true, &["store"], TOKEN);
    for b in [r#"{"content":"x"}"#, r#"{"path":"store/a","content":"x","b64":"eA=="}"#, r#"{"path":"store/a"}"#, r#"{"path":5,"content":"x"}"#, r#"{"path":"store/a","content":5}"#, "{}", ""] {
        let g = post(&s, GOOD, b);
        assert_eq!(g.status, 400, "{:?} -> {}", b, g.body);
        assert!(g.body.contains("exactly one of"), "{:?} -> {}", b, g.body);
    }
    for b in ["{", "[1]", "not json", r#"{"path":"a","content":"x"} trailing"#] {
        let g = post(&s, GOOD, b);
        assert_eq!((g.status, g.body.contains("malformed JSON body")), (400, true), "{:?} -> {}", b, g.body);
    }
}

#[test]
fn writes_stay_inside_the_allowlist() {
    let s = serve("allow", true, &["store", &"private/nope"], TOKEN);
    let outside = std::env::temp_dir().join(format!("fws-escape-{}", std::process::id()));
    let _ = fs::remove_file(&outside);
    let mut paths = vec![
        "private/keep.txt".to_string(),
        "private/new.txt".to_string(),
        "store/../private/keep.txt".to_string(),
        "../store/x.txt".to_string(),
        "store/../../escape.txt".to_string(),
        "".to_string(),
        "/".to_string(),
        "x.txt".to_string(),
    ];
    paths.push(format!("..{}", outside.to_string_lossy()));
    for p in &paths {
        let g = post(&s, GOOD, &format!(r#"{{"path":"{}","content":"pwn"}}"#, p));
        assert_eq!(g.status, 403, "{:?} -> {} {}", p, g.status, g.body);
        assert!(g.body.contains("outside the write allowlist"), "{:?} -> {}", p, g.body);
    }
    // the write root itself is a directory: refused cleanly, not written
    let g = post(&s, GOOD, r#"{"path":"store","content":"x"}"#);
    assert_eq!((g.status, g.body.contains("path is a directory")), (400, true), "{}", g.body);
    assert_eq!(read(&s.root.join("private/keep.txt")), "original");
    assert!(!s.root.join("private/new.txt").exists() && !outside.exists());
    assert!(s.root.parent().unwrap().join("escape.txt").exists() == false);
}

#[cfg(unix)]
#[test]
fn a_symlink_cannot_carry_a_write_out_of_the_root_or_the_write_root() {
    let s = serve("symlink", true, &["store"], TOKEN);
    let elsewhere = tmp("symlink-target");
    std::os::unix::fs::symlink(&elsewhere, s.root.join("store/out")).unwrap();
    std::os::unix::fs::symlink(s.root.join("private"), s.root.join("store/inner")).unwrap();
    let g = post(&s, GOOD, r#"{"path":"store/out/pwn.txt","content":"x"}"#);
    assert_eq!(g.status, 403, "{}", g.body);
    let g = post(&s, GOOD, r#"{"path":"store/inner/keep.txt","content":"x"}"#);
    assert_eq!(g.status, 403, "{}", g.body);
    assert!(!elsewhere.join("pwn.txt").exists());
    assert_eq!(read(&s.root.join("private/keep.txt")), "original");
}

#[test]
fn an_oversized_body_is_refused_before_it_is_read() {
    let s = serve("big", true, &["store"], TOKEN);
    let g = raw(s.port, GOOD, "", Some(40 * 1024 * 1024), "POST");
    assert_eq!(g.status, 413, "{}", g.body);
    assert!(g.body.contains("too large"), "{}", g.body);
}

#[test]
fn a_write_is_visible_to_the_read_api_and_reading_needs_no_token() {
    let s = serve("visible", true, &["store"], TOKEN);
    assert_eq!(post(&s, GOOD, r#"{"path":"store/seen.txt","content":"visible"}"#).status, 200);
    let r = get(&s, "/__api__/read?path=store/seen.txt");
    assert_eq!(r.status, 200);
    assert!(r.body.contains("visible"), "{}", r.body);
    let ls = get(&s, "/__api__/ls?path=store");
    assert!(ls.body.contains("seen.txt"), "{}", ls.body);
}

#[test]
fn the_ui_and_the_other_routes_are_untouched() {
    let s = serve("ui", true, &["store"], TOKEN);
    let g = get(&s, "/");
    assert_eq!(g.status, 200);
    assert!(g.body.contains(r#"id="viewer-toolbar""#));
    // GET on the write route is a method error, not a page.
    assert_eq!(get(&s, "/__api__/write").status, 405);
}
