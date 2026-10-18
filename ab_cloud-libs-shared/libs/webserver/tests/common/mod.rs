#![allow(dead_code)]
use cloud_webserver::{start, Config};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpStream};
use std::path::{Path, PathBuf};

pub struct Got {
    pub status: u16,
    pub headers: String,
    pub body: Vec<u8>,
}

impl Got {
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).to_string()
    }
}

pub fn call(port: u16, method: &str, target: &str, extra: &str) -> Got {
    let mut s = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
    write!(s, "{} {} HTTP/1.1\r\nhost: 127.0.0.1:{}\r\nconnection: close\r\n{}\r\n", method, target, port, extra).unwrap();
    let mut raw = Vec::new();
    s.read_to_end(&mut raw).unwrap();
    let split = raw.windows(4).position(|w| w == b"\r\n\r\n").expect("no header end") + 4;
    let head = String::from_utf8_lossy(&raw[..split]).to_string();
    let status: u16 = head.split(' ').nth(1).unwrap().parse().unwrap();
    let head = head.to_lowercase();
    let mut body = raw[split..].to_vec();
    if head.contains("transfer-encoding: chunked") {
        body = dechunk(&body);
    }
    Got { status, headers: head, body }
}

/// Node answers with chunked transfer encoding; ours sends a length.
fn dechunk(b: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut i = 0;
    loop {
        let nl = b[i..].windows(2).position(|w| w == b"\r\n").expect("bad chunk") + i;
        let n = usize::from_str_radix(std::str::from_utf8(&b[i..nl]).unwrap().trim(), 16).expect("bad chunk size");
        if n == 0 {
            return out;
        }
        out.extend_from_slice(&b[nl + 2..nl + 2 + n]);
        i = nl + 2 + n + 2;
    }
}

pub fn get(port: u16, target: &str) -> Got {
    call(port, "GET", target, "")
}

pub fn get_html(port: u16, target: &str) -> Got {
    call(port, "GET", target, "accept: text/html,application/xhtml+xml\r\n")
}

/// A root with one of every kind of file the frontend renders.
pub fn fixture_root(tag: &str) -> PathBuf {
    let root = std::env::temp_dir().join(format!("cws-{}-{}", tag, std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(root.join("docs/sub")).unwrap();
    std::fs::create_dir_all(root.join("site")).unwrap();
    std::fs::write(root.join("docs/readme.md"), "# hello\nneedle here\n").unwrap();
    std::fs::write(root.join("docs/data.json"), "{\"a\":1,\"list\":[{\"x\":1}]}").unwrap();
    std::fs::write(root.join("docs/conf.yaml"), "a: 1\nb:\n  - c\n").unwrap();
    std::fs::write(root.join("docs/k.secrets"), "{\"token\":\"t\"}").unwrap();
    std::fs::write(root.join("docs/page.html"), "<html><body><p>hi</p></body></html>").unwrap();
    std::fs::write(root.join("docs/blob.bin"), [0u8, 1, 2, 3]).unwrap();
    std::fs::write(root.join("docs/notes.weird"), "plain words").unwrap();
    std::fs::write(root.join("docs/sub/deep.txt"), "deep needle").unwrap();
    std::fs::write(root.join("docs/pic.png"), [0x89, b'P', b'N', b'G']).unwrap();
    std::fs::write(root.join("site/200.html"), "<html><head></head><body><script src=\"/_app/a.js\"></script></body></html>").unwrap();
    std::fs::write(root.join(".hidden"), "x").unwrap();
    std::fs::canonicalize(root).unwrap()
}

pub fn serve(root: &Path) -> u16 {
    start(Config { port: 0, root: root.to_string_lossy().to_string(), extra_hosts: vec![] }).unwrap()
}

/// The cloud-u-linux checkout the vendored UI must equal: $LINUX_REPO, else the usual places.
/// There is no skip: a missing checkout is a failure with the instruction to fix it.
pub fn linux_repo() -> PathBuf {
    let mut cands: Vec<PathBuf> = Vec::new();
    if let Ok(p) = std::env::var("LINUX_REPO") {
        cands.push(PathBuf::from(p));
    }
    let here = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    for up in 1..=6 {
        let mut d = here.clone();
        for _ in 0..up {
            d.pop();
        }
        cands.push(d.join("cloud-u-linux"));
    }
    cands.push(PathBuf::from("/home/user/wt/ws8002-linux"));
    cands.push(PathBuf::from("/home/user/cloud-u-linux"));
    for c in &cands {
        if c.join("da_my-webserver/src/lib/browse.html").is_file() {
            return c.clone();
        }
    }
    panic!("the cloud-u-linux checkout was not found: set LINUX_REPO to a checkout of diegonmarcos/cloud-u-linux (da_my-webserver/src must be present). Tried {:?}", cands);
}
