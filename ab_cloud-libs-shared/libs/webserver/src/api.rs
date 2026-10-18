//! The HTTP API of the Linux my-webserver, rooted at one directory: `/__api__/ls`,
//! `/__api__/read`, `/__api__/search` and `/__lib__/*`. Same paths, same query parameters
//! (`path`, `dot`, `q`, `mode`), same JSON shapes, same `x-search-partial` header.

use crate::http::{Req, Resp};
use crate::render::{self, jstr};
use crate::Ctx;
use std::collections::HashSet;
use std::fs;
use std::os::unix::fs::MetadataExt;
use std::path::{Component, Path, PathBuf};
use std::time::{Duration, Instant};

pub const READ_MAX_BYTES: u64 = 5 * 1024 * 1024;
pub const SEARCH_BUDGET_ENTRIES: u32 = 50_000;
pub const SEARCH_DEADLINE_MS: u64 = 3_000;
pub const SEARCH_RESULT_CAP: usize = 200;

pub fn jerr(status: u16, msg: &str) -> Resp {
    Resp::json(status, format!("{{\"error\":{}}}", jstr(msg)))
}

/// Map a URL path onto the served root, refusing anything that would escape it (`..`, or a
/// symlink that leaves the root). None when outside the root or missing.
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

pub fn ls(ctx: &Ctx, req: &Req) -> Resp {
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
            Ok(m) => entries.push((m.is_dir(), name.clone(), format!("{{\"name\":{},\"isDir\":{}}}", jstr(&name), m.is_dir()))),
            Err(_) => {
                // A dangling symlink: stat() follows and fails; list what it is rather than dropping it.
                if let Ok(l) = fs::symlink_metadata(e.path()) {
                    if l.file_type().is_symlink() {
                        let target = fs::read_link(e.path()).map(|t| t.to_string_lossy().to_string()).unwrap_or_default();
                        entries.push((false, name.clone(), format!("{{\"name\":{},\"isDir\":false,\"broken\":true,\"target\":{}}}", jstr(&name), jstr(&target))));
                    }
                }
            }
        }
    }
    entries.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.to_lowercase().cmp(&b.1.to_lowercase())));
    let body: Vec<String> = entries.into_iter().map(|e| e.2).collect();
    Resp::json(200, format!("[{}]", body.join(",")))
}

pub fn read(ctx: &Ctx, req: &Req) -> Resp {
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
    Resp::json(200, format!("{{\"path\":{},\"ext\":{},\"size\":{},\"content\":{}}}", jstr(path), jstr(&ext), meta.len(), jstr(&content)))
}

pub fn search(ctx: &Ctx, req: &Req) -> Resp {
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
    // Three bounds, kept separate because they fail differently: an entry budget so the walk
    // ends whatever the tree is, a wall-clock deadline, and a (dev, ino) set so a symlink back
    // up the tree is not an infinite descent. Breadth-first, so the budget is spent on the
    // shallow paths the user meant.
    let deadline = Instant::now() + Duration::from_millis(ctx.search_ms);
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
            // A symlink that leaves the root is neither listed nor walked nor read.
            match fs::canonicalize(&child) {
                Ok(real) if real.starts_with(&ctx.root) => {}
                _ => continue,
            }
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
    Resp::json(200, format!("[{}]", results.join(","))).with("x-search-partial", if partial { "1" } else { "0" })
}

/// `/__lib__/<file>`: the bundled scripts and styles, and nothing else.
pub fn lib(name: &str) -> Option<Resp> {
    let (ctype, body): (&'static str, &str) = match name {
        "marked.min.js" => ("application/javascript", render::MARKED_JS),
        "github-markdown-dark.css" => ("text/css", render::MARKDOWN_CSS),
        "js-yaml.min.js" => ("application/javascript", render::YAML_JS),
        "eruda.min.js" => ("application/javascript", render::ERUDA_JS),
        "browse.html" => ("text/html", render::BROWSE_HTML),
        _ => return None,
    };
    Some(Resp::bytes(200, ctype, body.as_bytes().to_vec()).with("cache-control", "public, max-age=86400"))
}
