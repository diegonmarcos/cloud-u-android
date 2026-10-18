//! The Linux server itself is the oracle: vendor/my-webserver.cjs is started under node on the
//! same fixture root, and every page and API answer must equal ours (Eruda's CDN tag aside, which
//! the phone replaces by the bundled copy). This is what makes "same UI, same API" a checked
//! fact rather than a claim.
mod common;
use common::*;
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

struct Node(Child);
impl Drop for Node {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn start_node(root: &std::path::Path) -> (Node, u16) {
    let port = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap().local_addr().unwrap().port();
    let cjs = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("vendor/my-webserver.cjs");
    // Outside a SEA binary the Linux server reads its libs from $HOME/.local/lib/httpd.
    let home = std::env::temp_dir().join(format!("cws-home-{}", port));
    let lib = home.join(".local/lib/httpd");
    std::fs::create_dir_all(&lib).unwrap();
    for f in ["browse.html", "marked.min.js", "github-markdown-dark.css", "js-yaml.min.js"] {
        std::fs::copy(std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("vendor").join(f), lib.join(f)).unwrap();
    }
    let child = Command::new("node")
        .env("HOME", &home)
        .arg(cjs)
        .arg(port.to_string())
        .arg(root)
        .env("HTTPD_VERBOSE", "0")
        .stdout(Stdio::null())
        .stderr(Stdio::inherit())
        .spawn()
        .expect("node is required to run the parity test against the Linux server (apt install nodejs)");
    let t = Instant::now();
    while TcpStream::connect((Ipv4Addr::LOCALHOST, port)).is_err() {
        assert!(t.elapsed() < Duration::from_secs(15), "node my-webserver.cjs did not come up");
        std::thread::sleep(Duration::from_millis(50));
    }
    (Node(child), port)
}

const ERUDA_CDN: &str = "<script src=\"https://cdn.jsdelivr.net/npm/eruda\"></script><script>eruda.init();</script>";

fn norm(s: String) -> String {
    s.replace(ERUDA_CDN, cloud_webserver::render::ERUDA_TAG)
}

fn sorted_json_rows(s: &str) -> Vec<String> {
    let inner = s.trim().trim_start_matches('[').trim_end_matches(']');
    let mut rows: Vec<String> = inner.split("},{").map(|r| r.trim_matches(|c| c == '{' || c == '}').to_string()).filter(|r| !r.is_empty()).collect();
    rows.sort();
    rows
}

#[test]
fn ours_equals_the_linux_server() {
    let root = fixture_root("parity");
    let (_node, np) = start_node(&root);
    let op = serve(&root);

    // pages: byte-equal once Eruda's source is the bundled one
    for (target, html) in [
        ("/", false),
        ("/docs/", false),
        ("/__browse__", false),
        ("/docs/readme.md", true),
        ("/docs/data.json", true),
        ("/docs/conf.yaml", true),
        ("/docs/k.secrets", true),
        ("/docs/page.html", true),
        ("/docs/readme.md?raw", false),
        ("/docs/data.json?raw", true),
        ("/docs/data.json", false),
        ("/docs/notes.weird", false),
    ] {
        let a = if html { get_html(np, target) } else { get(np, target) };
        let b = if html { get_html(op, target) } else { get(op, target) };
        assert_eq!(a.status, b.status, "{} status", target);
        assert_eq!(norm(a.text()), b.text(), "{}: page differs from the Linux server's", target);
    }
    // lib assets
    for lib in ["marked.min.js", "github-markdown-dark.css", "js-yaml.min.js", "browse.html"] {
        assert_eq!(get(np, &format!("/__lib__/{}", lib)).body, get(op, &format!("/__lib__/{}", lib)).body, "{}", lib);
    }
    // API: the same rows (order inside a BFS level follows readdir, so compare as sets)
    for target in [
        "/__api__/ls?path=/",
        "/__api__/ls?path=/docs",
        "/__api__/ls?path=/&dot=1",
        "/__api__/ls?path=/docs/readme.md",
        "/__api__/read?path=/docs/readme.md",
        "/__api__/read?path=/docs",
        "/__api__/search?q=needle&mode=content&path=/",
        "/__api__/search?q=e&mode=filename&path=/",
        "/__api__/search?q=sub&mode=folder&path=/",
        "/__api__/search?q=hidden&path=/&dot=1",
        "/__api__/search?q=",
    ] {
        let a = get(np, target);
        let b = get(op, target);
        assert_eq!(a.status, b.status, "{} status", target);
        if target.contains("/ls?") && a.status == 200 {
            assert_eq!(a.text(), b.text(), "{}", target);
        } else {
            assert_eq!(sorted_json_rows(&a.text()), sorted_json_rows(&b.text()), "{}", target);
        }
        if target.contains("/search?") {
            assert_eq!(a.headers.contains("x-search-partial: 0"), b.headers.contains("x-search-partial: 0"), "{} partial flag", target);
        }
    }
}
