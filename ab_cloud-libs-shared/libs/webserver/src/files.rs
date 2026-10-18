//! Everything that is not `/__api__/*` or `/__lib__/*`: files under the served root. A directory
//! with an index.html is that page, any other directory is the file browser (the Linux
//! frontend) opened there, a file is sent with its MIME type or rendered the way
//! my-webserver.cjs renders it (markdown, JSON, YAML, secrets, HTML + Eruda, `?raw`).

use crate::api::resolve_in_root;
use crate::http::{Req, Resp};
use crate::render::{self, Structured};
use crate::Ctx;
use std::fs;
use std::path::Path;

fn not_found() -> Resp {
    Resp::text(404, "Not found")
}

/// The nearest ancestor `200.html` of an unknown path (SvelteKit adapter-static's SPA fallback),
/// with the URL dir it sits in.
fn ancestor_fallback(ctx: &Ctx, url_path: &str) -> Option<(std::path::PathBuf, String)> {
    let parent = |p: &str| -> String {
        match p.rfind('/') {
            Some(i) if i > 0 => p[..i].to_string(),
            _ => "/".to_string(),
        }
    };
    let mut dir = parent(url_path.trim_end_matches('/'));
    loop {
        let cand = format!("{}/200.html", dir.trim_end_matches('/'));
        if let Some(p) = resolve_in_root(&ctx.root, &cand) {
            if p.is_file() {
                return Some((p, dir));
            }
        }
        if dir == "/" || dir.is_empty() {
            return None;
        }
        dir = parent(&dir);
    }
}

fn sniff_text(head: &[u8]) -> bool {
    !head.contains(&0)
}

pub fn page_files(ctx: &Ctx, req: &Req) -> Resp {
    let mut fs_path = resolve_in_root(&ctx.root, &req.path);

    // /route -> /route.html (SvelteKit prerender)
    if fs_path.is_none() && Path::new(&req.path).extension().is_none() && !req.path.ends_with('/') {
        if let Some(p) = resolve_in_root(&ctx.root, &format!("{}.html", req.path)) {
            if p.is_file() {
                fs_path = Some(p);
            }
        }
    }
    let fs_path = match fs_path {
        Some(p) => p,
        None => {
            if let Some((file, dir)) = ancestor_fallback(ctx, &req.path) {
                if let Ok(html) = fs::read_to_string(&file) {
                    return Resp::html(render::inject_eruda(&render::rewrite_fallback_paths(&html, &dir)));
                }
            }
            return not_found();
        }
    };
    let meta = match fs::metadata(&fs_path) {
        Ok(m) => m,
        Err(_) => return not_found(),
    };

    if meta.is_dir() {
        let index = fs_path.join("index.html");
        if index.is_file() {
            return match fs::read_to_string(&index) {
                Ok(html) => {
                    let dir_url = if req.path.ends_with('/') { req.path.clone() } else { format!("{}/", req.path) };
                    Resp::html(render::inject_eruda(&render::inject_base(&html, &render::href_of(&dir_url))))
                }
                Err(_) => Resp::text(500, "Cannot read index.html"),
            };
        }
        return Resp::html(render::browse_page(&req.path));
    }

    let name = fs_path.file_name().map(|n| n.to_string_lossy().to_lowercase()).unwrap_or_default();
    let ext = fs_path.extension().map(|e| format!(".{}", e.to_string_lossy().to_lowercase())).unwrap_or_default();
    let raw = req.q("raw").is_some();
    let accepts_html = req.header("accept").map(|a| a.contains("text/html")).unwrap_or(false);
    let read_text = || fs::read(&fs_path).map(|b| String::from_utf8_lossy(&b).to_string());

    if raw && ext != ".md" {
        if let Ok(t) = read_text() {
            return Resp::bytes(200, "text/plain; charset=utf-8", t.into_bytes());
        }
    }
    let structured = if name.ends_with(".secrets") || name.ends_with(".secrets.json") {
        Some(Structured::Secrets)
    } else if ext == ".json" {
        Some(Structured::Json)
    } else if ext == ".yaml" || ext == ".yml" {
        Some(Structured::Yaml)
    } else {
        None
    };
    if let Some(kind) = structured {
        if accepts_html && !raw {
            return match read_text() {
                Ok(t) => Resp::html(render::structured_page(&req.path, &t, kind)),
                Err(_) => Resp::text(500, "Cannot read file"),
            };
        }
    }
    if ext == ".md" {
        return match read_text() {
            Ok(t) if raw => Resp::bytes(200, "text/plain; charset=utf-8", t.into_bytes()),
            Ok(t) => Resp::html(render::markdown_page(&req.path, &t)),
            Err(_) => Resp::text(500, "Cannot read file"),
        };
    }
    if ext == ".html" {
        return match read_text() {
            Ok(t) => Resp::html(render::inject_eruda(&t)),
            Err(_) => Resp::text(500, "Cannot read file"),
        };
    }

    // Everything else streams from disk: a phone's drive holds videos, and a whole file in a Vec
    // is how a 2 GB movie ends the process.
    let mime = mime_for(&fs_path);
    let file = match fs::File::open(&fs_path) {
        Ok(f) => f,
        Err(_) => return Resp::text(500, "Cannot read file"),
    };
    let ctype = if mime == "application/octet-stream" {
        use std::io::{Read, Seek};
        let mut head = [0u8; 512];
        let n = (&file).read(&mut head).unwrap_or(0);
        let text = sniff_text(&head[..n]);
        let _ = (&file).seek(std::io::SeekFrom::Start(0));
        if text {
            "text/plain; charset=utf-8"
        } else {
            mime
        }
    } else {
        mime
    };
    Resp::stream(200, ctype, file, meta.len())
}

/// The MIME table of my-webserver.cjs (code and config are served as text so iframes render them).
pub fn mime_for(path: &Path) -> &'static str {
    let ext = path.extension().map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    match ext.as_str() {
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "js" | "mjs" | "cjs" => "text/javascript; charset=utf-8",
        "json" => "application/json; charset=utf-8",
        "md" | "txt" | "log" | "yaml" | "yml" | "toml" | "csv" | "sh" | "fish" | "kt" | "rs" | "py" | "ini" | "conf" | "cfg" | "env" | "lock" | "secrets"
        | "service" | "timer" | "sql" | "graphql" | "tf" | "scss" | "less" | "nix" | "ts" | "tsx" | "gradle" | "properties" => "text/plain; charset=utf-8",
        "xml" => "text/xml; charset=utf-8",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        "ttf" => "font/ttf",
        "pdf" => "application/pdf",
        "mp4" => "video/mp4",
        "webm" => "video/webm",
        "mp3" => "audio/mpeg",
        "ogg" => "audio/ogg",
        "wasm" => "application/wasm",
        "zip" => "application/zip",
        "apk" => "application/vnd.android.package-archive",
        _ => "application/octet-stream",
    }
}
