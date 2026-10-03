//! THE ONE ROUTE TABLE.
//!
//! Every path this server answers is a row of [`ROUTES`], and dispatch is
//! nothing but a walk over that table ([`find`]). There is no second place a
//! handler can be attached, so "served" and "declared" are the same list by
//! construction; [`check_table`] refuses to start on a table that is not
//! well-formed (a duplicate path, a tab page without a label, a catch-all that
//! is not last). The app's Pages and API tabs render `/__api__/routes`, which
//! is this table serialised, so a row added here appears in the tabs and a
//! row removed here disappears from them with no other edit.

use crate::{Ctx, Req, Resp};
use std::collections::HashSet;
use std::fs;
use std::os::unix::fs::MetadataExt;
use std::path::{Component, Path, PathBuf};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    Page,
    Api,
}

pub struct Route {
    pub kind: Kind,
    pub path: &'static str,
    /// Bottom-nav caption when this page is one of the tabs; None otherwise.
    pub tab: Option<&'static str>,
    pub summary: &'static str,
    pub handler: fn(&Ctx, &Req) -> Resp,
}

/// The catch-all row's path. Matched last, for anything no exact row claims.
pub const CATCH_ALL: &str = "/*";

pub const ROUTES: &[Route] = &[
    Route { kind: Kind::Page, path: "/", tab: None, summary: "The app shell, opened on the Home tab", handler: page_shell },
    Route { kind: Kind::Page, path: "/__tab__/pages", tab: Some("Pages"), summary: "Every page this server serves, read from its own route table", handler: page_shell },
    Route { kind: Kind::Page, path: "/__tab__/api", tab: Some("API"), summary: "Every API route this server serves, read from its own route table", handler: page_shell },
    Route { kind: Kind::Page, path: "/__tab__/home", tab: Some("Home"), summary: "About this phone: device, kernel, CPU, memory, storage, battery, network", handler: page_shell },
    Route { kind: Kind::Page, path: "/__tab__/files", tab: Some("Files"), summary: "Browse, open and search the served root", handler: page_shell },
    Route { kind: Kind::Page, path: "/__tab__/configs", tab: Some("Configs"), summary: "The server's effective configuration", handler: page_shell },
    Route { kind: Kind::Api, path: "/__api__/routes", tab: None, summary: "This route table as JSON: kind, path, tab, summary", handler: api_routes },
    Route { kind: Kind::Api, path: "/__api__/health", tab: None, summary: "Liveness: pid, uptime, port, root", handler: api_health },
    Route { kind: Kind::Api, path: "/__api__/config", tab: None, summary: "Effective configuration: bind, port, root, limits, route counts", handler: api_config },
    Route { kind: Kind::Api, path: "/__api__/about", tab: None, summary: "About this phone: macro groups of sections of rows, the Configs ▸ About shape (what the Home tab renders)", handler: api_about },
    Route { kind: Kind::Api, path: "/__api__/ls", tab: None, summary: "Directory listing JSON: ?path=<dir>&dot=1", handler: api_ls },
    Route { kind: Kind::Api, path: "/__api__/read", tab: None, summary: "File content JSON, at most 5 MB: ?path=<file>", handler: api_read },
    Route { kind: Kind::Api, path: "/__api__/search", tab: None, summary: "Bounded breadth-first search: ?q=<text>&mode=filename|folder|content&path=<dir>&dot=1", handler: api_search },
    Route { kind: Kind::Page, path: CATCH_ALL, tab: None, summary: "Files under the served root: a directory lists itself, a file is sent with its MIME type", handler: page_files },
];

/// Exact match over the table; the catch-all row otherwise.
pub fn find(path: &str) -> &'static Route {
    // Trailing slashes are noise; an all-slash path is the root, not the catch-all.
    let trimmed = path.trim_end_matches('/');
    let p = if trimmed.is_empty() { "/" } else { trimmed };
    for r in ROUTES {
        if r.path != CATCH_ALL && r.path == p {
            return r;
        }
    }
    ROUTES.last().expect("check_table guarantees a catch-all row")
}

/// Refuse a table that cannot be dispatched unambiguously.
pub fn check_table() -> Result<(), String> {
    let mut seen = HashSet::new();
    for r in ROUTES {
        if !seen.insert(r.path) {
            return Err(format!("route {} is declared twice", r.path));
        }
        if !r.path.starts_with('/') {
            return Err(format!("route {} does not start with /", r.path));
        }
        if r.path.contains('*') && r.path != CATCH_ALL {
            return Err(format!("route {} uses a wildcard; only {} may", r.path, CATCH_ALL));
        }
        if r.path.starts_with("/__tab__/") && r.tab.is_none() {
            return Err(format!("tab page {} has no tab caption", r.path));
        }
        if r.tab.is_some() && (r.kind != Kind::Page || !r.path.starts_with("/__tab__/")) {
            return Err(format!("route {} carries a tab caption but is not a /__tab__/ page", r.path));
        }
    }
    match ROUTES.last() {
        Some(r) if r.path == CATCH_ALL => {}
        _ => return Err(format!("the last route must be the catch-all {}", CATCH_ALL)),
    }
    if !ROUTES.iter().any(|r| r.tab.is_some()) {
        return Err("no route is a tab; the shell would have no bottom nav".to_string());
    }
    Ok(())
}

// ── JSON helpers (hand-written: the shapes here are flat) ─────────────────

pub fn jstr(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

fn jerr(status: u16, msg: &str) -> Resp {
    Resp::json(status, format!("{{\"error\":{}}}", jstr(msg)))
}

pub fn routes_json() -> String {
    routes_json_of(ROUTES.iter())
}

/// Serialise any sequence of rows; `routes_json` feeds it the real table and
/// the tests feed it the table with a row planted and a row removed.
pub fn routes_json_of<'a, I: Iterator<Item = &'a Route>>(rows: I) -> String {
    let rows: Vec<String> = rows
        .map(|r| {
            format!(
                "{{\"kind\":{},\"path\":{},\"tab\":{},\"summary\":{}}}",
                jstr(if r.kind == Kind::Page { "page" } else { "api" }),
                jstr(r.path),
                match r.tab { Some(t) => jstr(t), None => "null".to_string() },
                jstr(r.summary)
            )
        })
        .collect();
    format!("[{}]", rows.join(","))
}

// ── Handlers ──────────────────────────────────────────────────────────────

const SHELL: &str = include_str!("../ui/shell.html");

fn page_shell(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::html(SHELL.to_string())
}

fn api_routes(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::json(200, routes_json())
}

fn api_health(ctx: &Ctx, _req: &Req) -> Resp {
    Resp::json(
        200,
        format!(
            "{{\"ok\":true,\"pid\":{},\"uptime_s\":{},\"port\":{},\"root\":{}}}",
            std::process::id(),
            ctx.started.elapsed().as_secs(),
            ctx.port,
            jstr(&ctx.root.to_string_lossy())
        ),
    )
}

pub const READ_MAX_BYTES: u64 = 5 * 1024 * 1024;
pub const SEARCH_BUDGET_ENTRIES: u32 = 50_000;
pub const SEARCH_DEADLINE_MS: u64 = 3_000;
pub const SEARCH_RESULT_CAP: usize = 200;

fn api_config(ctx: &Ctx, _req: &Req) -> Resp {
    let pages = ROUTES.iter().filter(|r| r.kind == Kind::Page).count();
    let apis = ROUTES.iter().filter(|r| r.kind == Kind::Api).count();
    Resp::json(
        200,
        format!(
            concat!(
                "{{\"name\":{},\"version\":{},\"bind\":\"127.0.0.1\",\"port\":{},\"root\":{},\"binary\":{},",
                "\"pid\":{},\"methods\":[\"GET\",\"HEAD\"],\"write\":false,\"read_max_bytes\":{},",
                "\"search\":{{\"budget_entries\":{},\"deadline_ms\":{},\"result_cap\":{}}},",
                "\"routes\":{{\"pages\":{},\"api\":{}}},\"arch\":{},\"os\":{}}}"
            ),
            jstr(env!("CARGO_PKG_NAME")),
            jstr(env!("CARGO_PKG_VERSION")),
            ctx.port,
            jstr(&ctx.root.to_string_lossy()),
            jstr(&ctx.binary),
            std::process::id(),
            READ_MAX_BYTES,
            SEARCH_BUDGET_ENTRIES,
            SEARCH_DEADLINE_MS,
            SEARCH_RESULT_CAP,
            pages,
            apis,
            jstr(std::env::consts::ARCH),
            jstr(std::env::consts::OS)
        ),
    )
}

fn api_about(ctx: &Ctx, _req: &Req) -> Resp {
    let out: Vec<String> = crate::about::groups(ctx)
        .iter()
        .map(|(macro_label, sections)| {
            let secs: Vec<String> = sections
                .iter()
                .map(|(title, rows)| {
                    let r: Vec<String> = rows.iter().map(|(k, v)| format!("[{},{}]", jstr(k), jstr(v))).collect();
                    format!("{{\"title\":{},\"rows\":[{}]}}", jstr(title), r.join(","))
                })
                .collect();
            format!("{{\"macro\":{},\"sections\":[{}]}}", jstr(macro_label), secs.join(","))
        })
        .collect();
    Resp::json(200, format!("[{}]", out.join(",")))
}

/// Map a URL path onto the served root, refusing anything that would escape it.
/// Returns None when the path is outside the root or does not exist.
pub fn resolve_in_root(root: &Path, url_path: &str) -> Option<PathBuf> {
    let mut p = root.to_path_buf();
    for c in Path::new(url_path).components() {
        match c {
            Component::Normal(seg) => p.push(seg),
            Component::RootDir | Component::CurDir => {}
            Component::ParentDir | Component::Prefix(_) => return None,
        }
    }
    let real = fs::canonicalize(&p).ok()?;
    if real == *root || real.starts_with(root) {
        Some(real)
    } else {
        None
    }
}

fn api_ls(ctx: &Ctx, req: &Req) -> Resp {
    let dir = req.q("path").unwrap_or("/");
    let show_dot = req.q("dot") == Some("1");
    let fs_dir = match resolve_in_root(&ctx.root, dir) {
        Some(p) if p.is_dir() => p,
        _ => return jerr(404, "Not a directory"),
    };
    let rd = match fs::read_dir(&fs_dir) {
        Ok(rd) => rd,
        Err(e) => return jerr(500, &format!("Cannot list: {}", e)),
    };
    let mut entries: Vec<(bool, String, String)> = Vec::new();
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        if !show_dot && name.starts_with('.') {
            continue;
        }
        match fs::metadata(e.path()) {
            Ok(m) => {
                let is_dir = m.is_dir();
                entries.push((
                    is_dir,
                    name.clone(),
                    format!("{{\"name\":{},\"isDir\":{},\"size\":{},\"mtime\":{}}}", jstr(&name), is_dir, m.len(), m.mtime()),
                ));
            }
            Err(_) => {
                // A dangling symlink: stat() follows and fails, so list what it is
                // rather than dropping it, which reads as "never there".
                if let Ok(l) = fs::symlink_metadata(e.path()) {
                    if l.file_type().is_symlink() {
                        let target = fs::read_link(e.path()).map(|t| t.to_string_lossy().to_string()).unwrap_or_default();
                        entries.push((
                            false,
                            name.clone(),
                            format!("{{\"name\":{},\"isDir\":false,\"broken\":true,\"target\":{}}}", jstr(&name), jstr(&target)),
                        ));
                    }
                }
            }
        }
    }
    entries.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.to_lowercase().cmp(&b.1.to_lowercase())));
    let body: Vec<String> = entries.into_iter().map(|e| e.2).collect();
    Resp::json(200, format!("[{}]", body.join(",")))
}

fn api_read(ctx: &Ctx, req: &Req) -> Resp {
    let path = req.q("path").unwrap_or("/");
    let fs_file = match resolve_in_root(&ctx.root, path) {
        Some(p) if p.is_file() => p,
        _ => return jerr(404, "Not a file"),
    };
    let meta = match fs::metadata(&fs_file) {
        Ok(m) => m,
        Err(_) => return jerr(404, "Not a file"),
    };
    if meta.len() > READ_MAX_BYTES {
        return jerr(413, "File too large (>5MB)");
    }
    let bytes = match fs::read(&fs_file) {
        Ok(b) => b,
        Err(_) => return jerr(500, "Cannot read file"),
    };
    let ext = fs_file.extension().map(|e| format!(".{}", e.to_string_lossy().to_lowercase())).unwrap_or_default();
    let content = String::from_utf8_lossy(&bytes);
    Resp::json(
        200,
        format!("{{\"path\":{},\"ext\":{},\"size\":{},\"content\":{}}}", jstr(path), jstr(&ext), meta.len(), jstr(&content)),
    )
}

fn api_search(ctx: &Ctx, req: &Req) -> Resp {
    let q = req.q("q").unwrap_or("").to_lowercase();
    let mode = req.q("mode").unwrap_or("filename");
    let base = req.q("path").unwrap_or("/");
    let show_dot = req.q("dot") == Some("1");
    if q.is_empty() {
        return Resp::json(200, "[]".to_string());
    }
    let fs_base = match resolve_in_root(&ctx.root, base) {
        Some(p) if p.is_dir() => p,
        _ => return jerr(404, "Not a directory"),
    };
    // Three bounds, kept separate because they fail differently: an entry
    // budget so the walk ends whatever the tree is, a wall-clock deadline so a
    // cold disk cannot turn 50k stats into minutes, and a (dev, ino) set so a
    // symlink back up the tree is not an infinite descent. Breadth-first, so
    // the budget is spent on the shallow paths the user meant.
    let deadline = Instant::now() + Duration::from_millis(SEARCH_DEADLINE_MS);
    let mut budget = SEARCH_BUDGET_ENTRIES;
    let mut timed_out = false;
    let mut seen: HashSet<(u64, u64)> = HashSet::new();
    let mut results: Vec<String> = Vec::new();
    let mut queue: std::collections::VecDeque<(PathBuf, String)> = std::collections::VecDeque::new();
    queue.push_back((fs_base, String::new()));
    'walk: while let Some((cur, cur_rel)) = queue.pop_front() {
        if budget == 0 || results.len() > SEARCH_RESULT_CAP {
            break;
        }
        if Instant::now() > deadline {
            timed_out = true;
            break;
        }
        let rd = match fs::read_dir(&cur) {
            Ok(rd) => rd,
            Err(_) => continue,
        };
        for e in rd.flatten() {
            if budget == 0 || results.len() > SEARCH_RESULT_CAP {
                break 'walk;
            }
            if Instant::now() > deadline {
                timed_out = true;
                break 'walk;
            }
            let name = e.file_name().to_string_lossy().to_string();
            if !show_dot && name.starts_with('.') {
                continue;
            }
            budget -= 1;
            let child_rel = if cur_rel.is_empty() { name.clone() } else { format!("{}/{}", cur_rel, name) };
            let child = e.path();
            let m = match fs::metadata(&child) {
                Ok(m) => m,
                Err(_) => continue,
            };
            let lname = name.to_lowercase();
            if m.is_dir() {
                if (mode == "filename" || mode == "folder") && lname.contains(&q) {
                    results.push(format!("{{\"path\":{},\"isDir\":true}}", jstr(&child_rel)));
                }
                if seen.insert((m.dev(), m.ino())) {
                    queue.push_back((child, child_rel));
                }
            } else if mode == "filename" {
                if lname.contains(&q) {
                    results.push(format!("{{\"path\":{},\"isDir\":false}}", jstr(&child_rel)));
                }
            } else if mode == "content" && m.len() < 1024 * 1024 {
                if let Ok(bytes) = fs::read(&child) {
                    if String::from_utf8_lossy(&bytes).to_lowercase().contains(&q) {
                        results.push(format!("{{\"path\":{},\"isDir\":false}}", jstr(&child_rel)));
                    }
                }
            }
        }
    }
    let partial = timed_out || budget == 0 || results.len() > SEARCH_RESULT_CAP;
    let mut resp = Resp::json(200, format!("[{}]", results.join(",")));
    resp.headers.push(("x-search-partial".to_string(), if partial { "1".to_string() } else { "0".to_string() }));
    resp
}

pub fn esc_html(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => o.push_str("&amp;"),
            '<' => o.push_str("&lt;"),
            '>' => o.push_str("&gt;"),
            '"' => o.push_str("&quot;"),
            c => o.push(c),
        }
    }
    o
}

pub fn mime_for(path: &Path) -> &'static str {
    let ext = path.extension().map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    match ext.as_str() {
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "js" | "mjs" | "cjs" => "text/javascript; charset=utf-8",
        "json" => "application/json; charset=utf-8",
        "md" | "txt" | "log" | "yaml" | "yml" | "toml" | "csv" | "sh" | "kt" | "rs" | "py" | "xml" | "ini" | "conf" | "cfg" => {
            "text/plain; charset=utf-8"
        }
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "pdf" => "application/pdf",
        "mp4" => "video/mp4",
        "mp3" => "audio/mpeg",
        "ogg" => "audio/ogg",
        "wasm" => "application/wasm",
        "zip" => "application/zip",
        "apk" => "application/vnd.android.package-archive",
        _ => "application/octet-stream",
    }
}

fn page_files(ctx: &Ctx, req: &Req) -> Resp {
    let fs_path = match resolve_in_root(&ctx.root, &req.path) {
        Some(p) => p,
        None => return Resp::text(404, "Not found"),
    };
    let meta = match fs::metadata(&fs_path) {
        Ok(m) => m,
        Err(_) => return Resp::text(404, "Not found"),
    };
    if meta.is_dir() {
        let index = fs_path.join("index.html");
        if index.is_file() {
            return match fs::read(&index) {
                Ok(b) => Resp::bytes(200, "text/html; charset=utf-8", b),
                Err(_) => Resp::text(500, "Cannot read index.html"),
            };
        }
        let mut names: Vec<(bool, String)> = Vec::new();
        if let Ok(rd) = fs::read_dir(&fs_path) {
            for e in rd.flatten() {
                let is_dir = e.path().is_dir();
                names.push((is_dir, e.file_name().to_string_lossy().to_string()));
            }
        }
        names.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.to_lowercase().cmp(&b.1.to_lowercase())));
        let dir_url = if req.path.ends_with('/') { req.path.clone() } else { format!("{}/", req.path) };
        let mut html = String::new();
        html.push_str("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">");
        html.push_str("<title>");
        html.push_str(&esc_html(&dir_url));
        html.push_str("</title><style>body{font:15px system-ui;background:#0b0e14;color:#e6e6e6;margin:0;padding:12px}a{color:#7cc4ff;text-decoration:none;display:block;padding:8px 4px;border-bottom:1px solid #1e2430}</style>");
        html.push_str("<h3>");
        html.push_str(&esc_html(&dir_url));
        html.push_str("</h3>");
        if dir_url != "/" {
            html.push_str("<a href=\"../\">../</a>");
        }
        for (is_dir, name) in names {
            let suffix = if is_dir { "/" } else { "" };
            html.push_str(&format!("<a href=\"{}{}\">{}{}</a>", esc_html(&pct_encode(&name)), suffix, esc_html(&name), suffix));
        }
        return Resp::html(html);
    }
    match fs::read(&fs_path) {
        Ok(b) => Resp::bytes(200, mime_for(&fs_path), b),
        Err(_) => Resp::text(500, "Cannot read file"),
    }
}

/// Percent-encode one path segment for an href.
pub fn pct_encode(seg: &str) -> String {
    let mut o = String::new();
    for b in seg.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => o.push(b as char),
            _ => o.push_str(&format!("%{:02X}", b)),
        }
    }
    o
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn table_is_well_formed() {
        check_table().expect("ROUTES must pass its own startup check");
    }

    #[test]
    fn every_declared_path_dispatches_to_itself() {
        for r in ROUTES {
            if r.path == CATCH_ALL {
                continue;
            }
            assert_eq!(find(r.path).path, r.path, "{} must resolve to its own row", r.path);
            let with_slash = format!("{}/", r.path);
            assert_eq!(find(&with_slash).path, r.path, "{} must tolerate a trailing slash", r.path);
        }
        assert_eq!(find("/some/file.txt").path, CATCH_ALL);
        assert_eq!(find("/__api__/nothing").path, CATCH_ALL, "an undeclared API path is a file lookup, never a handler");
    }

    #[test]
    fn routes_json_lists_exactly_the_table() {
        let json = routes_json();
        for r in ROUTES {
            assert!(json.contains(&format!("\"path\":{}", jstr(r.path))), "{} missing from /__api__/routes", r.path);
        }
        assert_eq!(json.matches("\"path\":").count(), ROUTES.len(), "the JSON has a row per route and nothing else");
    }

    #[test]
    fn the_five_tabs_in_order_with_home_centre() {
        let tabs: Vec<&str> = ROUTES.iter().filter_map(|r| r.tab).collect();
        assert_eq!(tabs, vec!["Pages", "API", "Home", "Files", "Configs"]);
    }

    #[test]
    fn resolve_refuses_escape() {
        let root = std::env::temp_dir().join(format!("c3ws-routes-{}", std::process::id()));
        fs::create_dir_all(root.join("sub")).unwrap();
        fs::write(root.join("sub/a.txt"), b"hi").unwrap();
        let root = fs::canonicalize(&root).unwrap();
        assert!(resolve_in_root(&root, "/sub/a.txt").is_some());
        assert!(resolve_in_root(&root, "/../../etc/passwd").is_none());
        assert!(resolve_in_root(&root, "/sub/../../..").is_none());
        assert!(resolve_in_root(&root, "/missing").is_none());
        let _ = fs::remove_dir_all(&root);
    }

    /// Not a check: prints what `/__api__/routes` serves, so
    /// test-c3-webserver.sh (T7) can feed the REAL table to the shell's nav
    /// under node, and plant/remove rows in a copy of this file.
    #[test]
    #[ignore]
    fn dump_routes_json() {
        println!("ROUTES_JSON_BEGIN{}ROUTES_JSON_END", routes_json());
    }

    #[test]
    fn json_strings_escape() {
        assert_eq!(jstr("a\"b\\c\n"), "\"a\\\"b\\\\c\\n\"");
    }
}
