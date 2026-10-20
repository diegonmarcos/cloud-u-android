//! `POST /__api__/write` -- the Linux my-webserver write API, same contract, same guards:
//! loopback only (http.rs), OFF unless `HTTPD_WRITE=1`, confined to the `HTTPD_WRITE_ROOTS`
//! allowlist (colon separated, absolute or root-relative; empty allowlist = nothing writable),
//! and every request needs the CSRF header `x-httpd-write: 1`, a JSON content type, a
//! loopback `Origin` (when sent) and the shared secret from the 0600 file `HTTPD_WRITE_TOKEN_FILE`
//! in `x-httpd-token` (no token file = no writes). Body: `{"path": "...", "content": "..."}` or
//! `{"path": "...", "b64": "..."}`; answer `{"ok":true,"path":...,"bytes":N}`. Reads are unaffected.
//! No dependencies: the JSON reader and the base64 decoder below are all this needs.

use crate::api::jerr;
use crate::http::{Req, Resp};
use crate::render::jstr;
use std::fs;
use std::path::{Component, Path, PathBuf};

/// Largest accepted request body (the Linux server's cap).
pub const MAX_BODY: usize = 32 * 1024 * 1024;

pub struct WriteCfg {
    pub enabled: bool,
    pub roots: Vec<PathBuf>,
    pub token: String,
}

impl WriteCfg {
    pub fn disabled() -> WriteCfg {
        WriteCfg { enabled: false, roots: Vec::new(), token: String::new() }
    }

    /// `HTTPD_WRITE`, `HTTPD_WRITE_ROOTS`, `HTTPD_WRITE_TOKEN_FILE`, read once at startup.
    pub fn from_env(served_root: &Path) -> WriteCfg {
        let enabled = std::env::var("HTTPD_WRITE").map(|v| v == "1").unwrap_or(false);
        let roots = std::env::var("HTTPD_WRITE_ROOTS").unwrap_or_default();
        let token = std::env::var("HTTPD_WRITE_TOKEN_FILE").ok().filter(|f| !f.is_empty()).and_then(|f| fs::read_to_string(f).ok()).map(|t| t.trim().to_string()).unwrap_or_default();
        WriteCfg::new(enabled, served_root, roots.split(':').filter(|s| !s.is_empty()), token)
    }

    pub fn new<'a>(enabled: bool, served_root: &Path, roots: impl Iterator<Item = &'a str>, token: String) -> WriteCfg {
        WriteCfg { enabled, roots: roots.filter_map(|r| configured_root(served_root, r)).collect(), token }
    }
}

/// Lexical normalisation (`..` pops, `.` vanishes); never touches the disk.
fn normalize(p: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::Prefix(_) | Component::RootDir => out.push(c.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            Component::Normal(s) => out.push(s),
        }
    }
    out
}

fn real_or_lexical(p: &Path) -> PathBuf {
    fs::canonicalize(p).unwrap_or_else(|_| normalize(p))
}

/// An allowlist entry written as an absolute path or relative to the served root, absolute first
/// (a non-root absolute path fails containment and falls through to the relative reading).
fn configured_root(root: &Path, entry: &str) -> Option<PathBuf> {
    let abs = real_or_lexical(Path::new(entry));
    if abs.starts_with(root) {
        return Some(abs);
    }
    let rel = real_or_lexical(&root.join(entry.trim_start_matches('/')));
    if rel.starts_with(root) {
        Some(rel)
    } else {
        None
    }
}

/// The file a request path names, if it lies in a write root AND no symlink lets it leave the
/// served root or the write root. The file need not exist yet.
fn resolve_writable(root: &Path, roots: &[PathBuf], url_path: &str) -> Option<PathBuf> {
    let target = normalize(&root.join(url_path.trim_start_matches('/')));
    if target == *root || !target.starts_with(root) {
        return None;
    }
    let wr: Vec<&PathBuf> = roots.iter().filter(|r| target.starts_with(r)).collect();
    if wr.is_empty() {
        return None;
    }
    // The deepest ancestor that exists decides where the bytes really land.
    let mut anc = target.clone();
    while !anc.exists() {
        if !anc.pop() {
            return None;
        }
    }
    let real = fs::canonicalize(&anc).ok()?;
    if !real.starts_with(root) {
        return None;
    }
    // Inside a write root, or a not-yet-created part of the path leading down to one.
    if wr.iter().any(|r| real.starts_with(r) || r.starts_with(&real)) {
        Some(target)
    } else {
        None
    }
}

fn token_matches(provided: &str, expected: &str) -> bool {
    let (a, b) = (provided.as_bytes(), expected.as_bytes());
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// The host of an `Origin` value (`http://127.0.0.1:8000`), lower-cased; None when unparsable.
fn origin_host(o: &str) -> Option<String> {
    let rest = o.split_once("://")?.1;
    let auth = rest.split('/').next()?;
    let host = match auth.strip_prefix('[') {
        Some(r) => format!("[{}]", r.split(']').next()?),
        None => auth.split(':').next()?.to_string(),
    };
    if host.is_empty() {
        None
    } else {
        Some(host.to_ascii_lowercase())
    }
}

pub fn write(root: &Path, w: &WriteCfg, req: &Req) -> Resp {
    if !w.enabled {
        return jerr(403, "write mode disabled (start with HTTPD_WRITE=1)");
    }
    if w.roots.is_empty() {
        return jerr(403, "no write roots configured (set HTTPD_WRITE_ROOTS)");
    }
    // The Linux guard, in the Linux order. The custom header forces a CORS preflight in a
    // browser (never answered here), a plain <form> cannot set it: do not "simplify" it away.
    if req.method != "POST" {
        return jerr(405, "method not allowed, use POST").with("allow", "POST");
    }
    if req.header("x-httpd-write") != Some("1") {
        return jerr(403, "missing x-httpd-write header");
    }
    if !req.header("content-type").unwrap_or("").to_ascii_lowercase().starts_with("application/json") {
        return jerr(403, "content-type must be application/json");
    }
    if let Some(o) = req.header("origin") {
        match origin_host(o) {
            Some(h) if h == "localhost" || h == "127.0.0.1" => {}
            Some(_) => return jerr(403, "origin not allowed"),
            None => return jerr(403, "invalid origin header"),
        }
    }
    if w.token.is_empty() {
        return jerr(403, "write token not configured");
    }
    if !token_matches(req.header("x-httpd-token").unwrap_or(""), &w.token) {
        return jerr(403, "invalid write token");
    }
    let body = match parse_object(&req.body) {
        Some(b) => b,
        None => return jerr(400, "malformed JSON body"),
    };
    let get = |k: &str| body.iter().rev().find(|(n, _)| n == k).and_then(|(_, v)| v.as_ref());
    let (path, content, b64) = (get("path"), get("content"), get("b64"));
    let path = match (path, content.is_some() != b64.is_some()) {
        (Some(p), true) => p,
        _ => return jerr(400, "body must have \"path\" and exactly one of \"content\"/\"b64\""),
    };
    let target = match resolve_writable(root, &w.roots, path) {
        Some(t) => t,
        None => return jerr(403, "path is outside the write allowlist (HTTPD_WRITE_ROOTS)"),
    };
    let bytes = match (content, b64) {
        (Some(c), _) => c.as_bytes().to_vec(),
        (_, Some(b)) => match b64_decode(b) {
            Some(d) => d,
            None => return jerr(400, "invalid base64"),
        },
        _ => unreachable!(),
    };
    if target.is_dir() {
        return jerr(400, "path is a directory");
    }
    if let Some(dir) = target.parent() {
        if let Err(e) = fs::create_dir_all(dir) {
            return jerr(500, &format!("cannot create directory: {}", e));
        }
    }
    match fs::write(&target, &bytes) {
        Ok(()) => Resp::json(200, format!("{{\"ok\":true,\"path\":{},\"bytes\":{}}}", jstr(path), bytes.len())),
        Err(e) => jerr(500, &format!("cannot write: {}", e)),
    }
}

// ── base64 (standard and url-safe alphabets, padding and whitespace optional) ──────────────────

pub fn b64_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len() / 4 * 3);
    let (mut acc, mut bits) = (0u32, 0u32);
    for c in s.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            b'=' | b' ' | b'\t' | b'\r' | b'\n' => continue,
            _ => return None,
        };
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

// ── a JSON object reader: every string member, anything else skipped ───────────────────────────

/// The members of a top-level JSON object as (name, Some(string) | None for a non-string value),
/// in document order. None when the body is not one well-formed JSON object (an empty body is `{}`).
pub fn parse_object(body: &[u8]) -> Option<Vec<(String, Option<String>)>> {
    let text = std::str::from_utf8(body).ok()?;
    if text.trim().is_empty() {
        return Some(Vec::new());
    }
    let mut p = P { s: text.as_bytes(), i: 0, depth: 0 };
    p.ws();
    p.eat(b'{')?;
    let mut out = Vec::new();
    p.ws();
    if p.peek() == Some(b'}') {
        p.i += 1;
    } else {
        loop {
            p.ws();
            let k = p.string()?;
            p.ws();
            p.eat(b':')?;
            p.ws();
            let v = if p.peek() == Some(b'"') { Some(p.string()?) } else { p.skip_value()?; None };
            out.push((k, v));
            p.ws();
            match p.next()? {
                b',' => continue,
                b'}' => break,
                _ => return None,
            }
        }
    }
    p.ws();
    if p.i == p.s.len() {
        Some(out)
    } else {
        None
    }
}

struct P<'a> {
    s: &'a [u8],
    i: usize,
    depth: u32,
}

impl P<'_> {
    fn peek(&self) -> Option<u8> {
        self.s.get(self.i).copied()
    }
    fn next(&mut self) -> Option<u8> {
        let c = self.peek()?;
        self.i += 1;
        Some(c)
    }
    fn eat(&mut self, c: u8) -> Option<()> {
        if self.next()? == c {
            Some(())
        } else {
            None
        }
    }
    fn ws(&mut self) {
        while matches!(self.peek(), Some(b' ' | b'\t' | b'\r' | b'\n')) {
            self.i += 1;
        }
    }
    fn hex4(&mut self) -> Option<u32> {
        let h = self.s.get(self.i..self.i + 4)?;
        self.i += 4;
        u32::from_str_radix(std::str::from_utf8(h).ok()?, 16).ok()
    }
    fn string(&mut self) -> Option<String> {
        self.eat(b'"')?;
        let mut out: Vec<u8> = Vec::new();
        loop {
            match self.next()? {
                b'"' => return String::from_utf8(out).ok(),
                b'\\' => {
                    let ch = match self.next()? {
                        b'"' => '"',
                        b'\\' => '\\',
                        b'/' => '/',
                        b'b' => '\u{8}',
                        b'f' => '\u{c}',
                        b'n' => '\n',
                        b'r' => '\r',
                        b't' => '\t',
                        b'u' => {
                            let hi = self.hex4()?;
                            let cp = if (0xD800..0xDC00).contains(&hi) {
                                self.eat(b'\\')?;
                                self.eat(b'u')?;
                                let lo = self.hex4()?;
                                if !(0xDC00..0xE000).contains(&lo) {
                                    return None;
                                }
                                0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)
                            } else {
                                hi
                            };
                            char::from_u32(cp)?
                        }
                        _ => return None,
                    };
                    let mut b = [0u8; 4];
                    out.extend_from_slice(ch.encode_utf8(&mut b).as_bytes());
                }
                c if c < 0x20 => return None,
                c => out.push(c),
            }
        }
    }
    fn skip_value(&mut self) -> Option<()> {
        self.depth += 1;
        if self.depth > 64 {
            return None;
        }
        self.ws();
        match self.peek()? {
            b'"' => {
                self.string()?;
            }
            b'{' => {
                self.i += 1;
                self.ws();
                if self.peek() == Some(b'}') {
                    self.i += 1;
                } else {
                    loop {
                        self.ws();
                        self.string()?;
                        self.ws();
                        self.eat(b':')?;
                        self.skip_value()?;
                        self.ws();
                        match self.next()? {
                            b',' => continue,
                            b'}' => break,
                            _ => return None,
                        }
                    }
                }
            }
            b'[' => {
                self.i += 1;
                self.ws();
                if self.peek() == Some(b']') {
                    self.i += 1;
                } else {
                    loop {
                        self.skip_value()?;
                        self.ws();
                        match self.next()? {
                            b',' => continue,
                            b']' => break,
                            _ => return None,
                        }
                    }
                }
            }
            b't' | b'f' | b'n' | b'-' | b'0'..=b'9' => {
                let start = self.i;
                while matches!(self.peek(), Some(c) if c.is_ascii_alphanumeric() || matches!(c, b'-' | b'+' | b'.')) {
                    self.i += 1;
                }
                let lit = std::str::from_utf8(&self.s[start..self.i]).ok()?;
                if !(matches!(lit, "true" | "false" | "null") || lit.parse::<f64>().is_ok()) {
                    return None;
                }
            }
            _ => return None,
        }
        self.depth -= 1;
        Some(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn obj(s: &str) -> Option<Vec<(String, Option<String>)>> {
        parse_object(s.as_bytes())
    }

    #[test]
    fn reads_string_members_and_skips_the_rest() {
        let got = obj(r#" {"path":"a/b.txt","n":12.5e3,"ok":true,"z":null,"arr":[1,[2],{"x":"y"}],"o":{"k":"v"},"content":"hi"} "#).unwrap();
        let names: Vec<&str> = got.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(names, ["path", "n", "ok", "z", "arr", "o", "content"]);
        assert_eq!(got[0].1.as_deref(), Some("a/b.txt"));
        assert_eq!(got[1].1, None);
        assert_eq!(got[6].1.as_deref(), Some("hi"));
    }

    #[test]
    fn decodes_escapes_and_surrogate_pairs() {
        let got = obj(r#"{"content":"l1\nl2\t\"q\" \\ \/ \u00e9 \ud83d\ude00 é"}"#).unwrap();
        assert_eq!(got[0].1.as_deref(), Some("l1\nl2\t\"q\" \\ / é 😀 é"));
    }

    #[test]
    fn empty_body_is_an_empty_object_and_garbage_is_refused() {
        assert_eq!(obj("").unwrap().len(), 0);
        assert_eq!(obj("{}").unwrap().len(), 0);
        for bad in ["{", "[]", "\"x\"", "{\"a\":}", "{\"a\":1,}", "{\"a\" 1}", "{\"a\":1} x", "{\"a\":\"\\q\"}", "{\"a\":\"\\ud83d\"}", "{\"a\":tru}", "{\"a\":\"line\nbreak\"}"] {
            assert!(obj(bad).is_none(), "accepted {:?}", bad);
        }
        assert!(parse_object(&[b'{', b'"', 0xff, b'"', b':', b'1', b'}']).is_none());
        let deep = format!("{{\"a\":{}{}}}", "[".repeat(70), "]".repeat(70));
        assert!(obj(&deep).is_none());
    }

    #[test]
    fn base64_standard_urlsafe_padding_and_whitespace() {
        assert_eq!(b64_decode("aGVsbG8=").unwrap(), b"hello");
        assert_eq!(b64_decode("aGVsbG8").unwrap(), b"hello");
        assert_eq!(b64_decode("aGVs\nbG8=").unwrap(), b"hello");
        assert_eq!(b64_decode("-_-_").unwrap(), b64_decode("+/+/").unwrap());
        assert_eq!(b64_decode("").unwrap(), b"");
        assert!(b64_decode("a$b").is_none());
    }

    #[test]
    fn token_comparison_needs_equal_length_and_bytes() {
        assert!(token_matches("s3cret", "s3cret"));
        assert!(!token_matches("s3cret", "s3cre"));
        assert!(!token_matches("s3cret", "s3creT"));
        assert!(!token_matches("", "x"));
    }

    #[test]
    fn origin_hosts() {
        assert_eq!(origin_host("http://127.0.0.1:8000").as_deref(), Some("127.0.0.1"));
        assert_eq!(origin_host("http://LOCALHOST").as_deref(), Some("localhost"));
        assert_eq!(origin_host("http://[::1]:80").as_deref(), Some("[::1]"));
        assert_eq!(origin_host("null"), None);
        assert_eq!(origin_host("https://evil.example").as_deref(), Some("evil.example"));
    }
}
