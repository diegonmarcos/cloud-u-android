//! API contract: every endpoint the Linux frontend (vendor/browse.html) calls, and every page it
//! opens in its viewer, answered the way my-webserver.cjs answers it.
mod common;
use common::*;

#[test]
fn root_is_the_linux_frontend_and_nothing_else() {
    let root = fixture_root("root");
    let port = serve(&root);
    for target in ["/", "/__browse__", "/__browse__/", "/docs/"] {
        let g = get(port, target);
        assert_eq!(g.status, 200, "{}", target);
        assert!(g.headers.contains("text/html"));
        let body = g.text();
        // the vendored browse.html, with only INIT_PATH filled in
        let want = fleet_webserver::render::BROWSE_HTML.replacen("__INIT_PATH__", &fleet_webserver::render::safe_json_for_script(if target.starts_with("/docs") { "/docs/" } else { "/" }), 1);
        assert_eq!(body, want, "{} must be vendor/browse.html verbatim", target);
        assert!(body.contains("id=\"sidebar\"") && body.contains("id=\"file-list\""), "the folder tree is on the left");
        assert!(body.contains("id=\"viewer\"") && body.contains("id=\"right-panel\""), "the viewer is on the right");
        assert!(body.contains("id=\"viewer-toolbar\"") && body.contains("id=\"raw-toggle\"") && body.contains("id=\"showdot\"") && body.contains("id=\"search\""), "the toolbar");
    }
}

#[test]
fn ls_contract() {
    let root = fixture_root("ls");
    let port = serve(&root);
    let g = get(port, "/__api__/ls?path=/docs");
    assert_eq!(g.status, 200);
    assert!(g.headers.contains("content-type: application/json"));
    let b = g.text();
    // dirs first, then files, each {name,isDir}
    assert!(b.starts_with("[{\"name\":\"sub\",\"isDir\":true},"), "{}", b);
    assert!(b.contains("{\"name\":\"readme.md\",\"isDir\":false}"));
    let top = get(port, "/__api__/ls?path=/").text();
    assert!(!top.contains(".hidden"), "dotfiles are hidden by default: {}", top);
    assert!(get(port, "/__api__/ls?path=/&dot=1").text().contains("{\"name\":\".hidden\",\"isDir\":false}"));
    assert_eq!(get(port, "/__api__/ls").status, 200, "no path means the root");
    let e = get(port, "/__api__/ls?path=/docs/readme.md");
    assert_eq!(e.status, 404);
    assert_eq!(e.text(), "{\"error\":\"Not a directory\"}");
    assert_eq!(get(port, "/__api__/ls?path=/../../etc").status, 404, "no escape from the root");
    assert_eq!(get(port, "/__api__/ls?path=/docs/..%2F..%2F").status, 404);
}

#[test]
fn read_contract() {
    let root = fixture_root("read");
    let port = serve(&root);
    let g = get(port, "/__api__/read?path=/docs/readme.md");
    assert_eq!(g.status, 200);
    assert_eq!(g.text(), "{\"path\":\"/docs/readme.md\",\"ext\":\".md\",\"size\":20,\"content\":\"# hello\\nneedle here\\n\"}");
    assert_eq!(get(port, "/__api__/read?path=/docs").text(), "{\"error\":\"Not a file\"}");
    assert_eq!(get(port, "/__api__/read?path=/nope").status, 404);
    std::fs::write(root.join("big.txt"), vec![b'a'; 5 * 1024 * 1024 + 1]).unwrap();
    let big = get(port, "/__api__/read?path=/big.txt");
    assert_eq!(big.status, 413);
    assert_eq!(big.text(), "{\"error\":\"File too large (>5MB)\"}");
}

#[test]
fn search_contract() {
    let root = fixture_root("search");
    let port = serve(&root);
    let by_name = get(port, "/__api__/search?q=READ&mode=filename&path=/");
    assert_eq!(by_name.status, 200);
    assert!(by_name.headers.contains("x-search-partial: 0"));
    assert!(by_name.headers.contains("content-type: application/json"));
    assert_eq!(by_name.text(), "[{\"path\":\"docs/readme.md\",\"isDir\":false}]");
    let folder = get(port, "/__api__/search?q=sub&mode=folder&path=/").text();
    assert_eq!(folder, "[{\"path\":\"docs/sub\",\"isDir\":true}]");
    assert_eq!(get(port, "/__api__/search?q=readme&mode=folder&path=/").text(), "[]", "folder mode reports directories only");
    let content = get(port, "/__api__/search?q=NEEDLE&mode=content&path=/docs").text();
    assert!(content.contains("{\"path\":\"readme.md\",\"isDir\":false}") && content.contains("{\"path\":\"sub/deep.txt\",\"isDir\":false}"), "{}", content);
    assert_eq!(get(port, "/__api__/search?q=").text(), "[]");
    assert_eq!(get(port, "/__api__/search?q=hidden&path=/").text(), "[]");
    assert_eq!(get(port, "/__api__/search?q=hidden&path=/&dot=1").text(), "[{\"path\":\".hidden\",\"isDir\":false}]");
    assert_eq!(get(port, "/__api__/search?q=x&path=/nope").status, 404);
}

#[test]
fn viewer_pages_and_raw() {
    let root = fixture_root("view");
    let port = serve(&root);
    // .md is rendered with marked from /__lib__
    let md = get_html(port, "/docs/readme.md").text();
    assert!(md.contains("/__lib__/marked.min.js") && md.contains("/__lib__/github-markdown-dark.css") && md.contains("const rawMarkdown = \"# hello\\nneedle here\\n\""));
    assert!(md.contains("href=\"/docs/readme.md?raw\""), "the view-raw link");
    assert_eq!(get(port, "/docs/readme.md?raw").text(), "# hello\nneedle here\n");
    assert!(get(port, "/docs/readme.md?raw").headers.contains("text/plain"));
    // json / yaml / secrets: a table for browsers, the file for everything else
    let j = get_html(port, "/docs/data.json").text();
    assert!(j.contains("const raw = \"{\\\"a\\\":1,\\\"list\\\":[{\\\"x\\\":1}]}\"") && j.contains(">JSON<") && j.contains("id=\"docfilter\""));
    assert!(!j.contains("js-yaml.min.js"));
    let y = get_html(port, "/docs/conf.yaml").text();
    assert!(y.contains("/__lib__/js-yaml.min.js") && y.contains(">YAML<"));
    let s = get_html(port, "/docs/k.secrets").text();
    assert!(s.contains("const isSecret = true") && s.contains("DECRYPTED"));
    assert_eq!(get(port, "/docs/data.json").text(), "{\"a\":1,\"list\":[{\"x\":1}]}", "no text/html Accept: the raw file");
    assert_eq!(get_html(port, "/docs/data.json?raw").text(), "{\"a\":1,\"list\":[{\"x\":1}]}");
    // html gets Eruda (bundled), images stream with their type, unknown text sniffed
    let h = get(port, "/docs/page.html").text();
    assert!(h.contains("<p>hi</p>") && h.contains("/__lib__/eruda.min.js") && h.contains("eruda.init()"));
    assert!(get(port, "/docs/pic.png").headers.contains("image/png"));
    assert!(get(port, "/docs/notes.weird").headers.contains("text/plain"));
    assert!(get(port, "/docs/blob.bin").headers.contains("application/octet-stream"));
    // SvelteKit SPA fallback under a project holding 200.html
    assert!(get(port, "/site/some/route").text().contains("src=\"/site/_app/a.js\""));
    assert_eq!(get(port, "/nope").status, 404);
}

#[test]
fn lib_assets_are_served_and_only_those() {
    let port = serve(&fixture_root("lib"));
    for (n, ct) in [("marked.min.js", "javascript"), ("github-markdown-dark.css", "text/css"), ("js-yaml.min.js", "javascript"), ("eruda.min.js", "javascript"), ("browse.html", "text/html")] {
        let g = get(port, &format!("/__lib__/{}", n));
        assert_eq!(g.status, 200, "{}", n);
        assert!(g.headers.contains(ct), "{}", n);
        assert!(g.body.len() > 1000);
    }
    assert_eq!(get(port, "/__lib__/../Cargo.toml").status, 404);
    assert_eq!(get(port, "/__lib__/nothing.js").status, 404);
}

#[test]
fn the_root_is_a_wall() {
    let root = fixture_root("wall");
    let outside = root.parent().unwrap().join(format!("cws-outside-{}", std::process::id()));
    std::fs::create_dir_all(&outside).unwrap();
    std::fs::write(outside.join("leak-me.txt"), "TOPSECRET").unwrap();
    std::os::unix::fs::symlink(&outside, root.join("escape")).unwrap();
    let port = serve(&root);
    assert_eq!(get(port, "/escape/leak-me.txt").status, 404, "a symlink out of the root is not followed");
    assert_eq!(get(port, "/%2e%2e/%2e%2e/etc/passwd").status, 404);
    assert_eq!(get(port, "/docs/../../etc/passwd").status, 404);
    assert_eq!(get(port, "/__api__/read?path=/escape/leak-me.txt").status, 404);
    assert_eq!(get(port, "/__api__/search?q=leak-me&path=/&mode=filename").text(), "[]", "search does not walk out through a symlink");
    assert_eq!(get(port, "/__api__/search?q=topsecret&path=/&mode=content").text(), "[]");
    assert!(!get(port, "/__api__/ls?path=/").text().contains("TOPSECRET"));
    let _ = std::fs::remove_dir_all(&outside);
}

#[test]
fn only_get_and_head_and_only_known_hosts() {
    let port = serve(&fixture_root("verbs"));
    assert_eq!(call(port, "POST", "/__api__/ls", "content-length: 0\r\n").status, 405);
    assert_eq!(call(port, "DELETE", "/docs/readme.md", "").status, 405);
    let h = call(port, "HEAD", "/docs/readme.md", "");
    assert_eq!(h.status, 200);
    assert!(h.body.is_empty());
    let mut s = std::net::TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port)).unwrap();
    use std::io::{Read, Write};
    write!(s, "GET / HTTP/1.1\r\nhost: evil.example\r\n\r\n").unwrap();
    let mut out = String::new();
    s.read_to_string(&mut out).unwrap();
    assert!(out.starts_with("HTTP/1.1 403"), "{}", out);
}
