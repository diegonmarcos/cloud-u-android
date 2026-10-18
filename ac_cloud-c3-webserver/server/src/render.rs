//! The page renderers ported from my-webserver (da_my-webserver/src/my-webserver.cjs):
//! markdown, JSON / YAML / secrets tables, the plain directory index, the file
//! browser SPA, and the Eruda tag. Every script they load is bundled in the
//! binary and served from /__lib__/ (see routes.rs); nothing is fetched from a
//! CDN, which is the one thing the Node original did at runtime.

use crate::routes::{esc_html, jstr, pct_encode};

pub const MARKED_JS: &str = include_str!("../ui/lib/marked.min.js");
pub const MARKDOWN_CSS: &str = include_str!("../ui/lib/github-markdown-dark.css");
pub const YAML_JS: &str = include_str!("../ui/lib/js-yaml.min.js");
pub const ERUDA_JS: &str = include_str!("../ui/lib/eruda.min.js");
pub const ERUDA_VERSION: &str = "3.4.3";
pub const MARKED_VERSION: &str = "15.0.12";
pub const YAML_VERSION: &str = "4.1.0";

const MARKDOWN_TPL: &str = include_str!("../ui/markdown.html");
const STRUCTURED_TPL: &str = include_str!("../ui/structured.html");
const BROWSE_TPL: &str = include_str!("../ui/browse.html");

/// The tag every HTML page gets when the `eruda` engine is on. Same-origin and
/// bundled: the Node original pointed at cdn.jsdelivr.net.
pub const ERUDA_TAG: &str = "<script src=\"/__lib__/eruda.min.js\"></script><script>eruda.init();</script>";

/// Embed a string in an inline <script> as a JS string literal. The HTML parser
/// closes a <script> on the first `</script`, so that, `<!--` and the two JS line
/// terminators are escaped: the fix every framework applies to inlined JSON.
pub fn safe_json_for_script(s: &str) -> String {
    let mut o = jstr(s);
    o = o.replace("</script", "<\\/script").replace("</SCRIPT", "<\\/SCRIPT");
    o = o.replace("<!--", "<\\!--");
    o = o.replace('\u{2028}', "\\u2028").replace('\u{2029}', "\\u2029");
    o
}

/// `~ / dir / dir / file` with every segment escaped (the original did not escape).
pub fn breadcrumb(url_path: &str) -> String {
    let parts: Vec<&str> = url_path.split('/').filter(|p| !p.is_empty()).collect();
    let mut out = String::from("<a href=\"/\">~</a>");
    let mut href = String::new();
    for (i, part) in parts.iter().enumerate() {
        href.push('/');
        href.push_str(&pct_encode(part));
        if i + 1 < parts.len() {
            out.push_str(&format!("<span class=\"sep\">/</span><a href=\"{}/\">{}</a>", esc_html(&href), esc_html(part)));
        } else {
            out.push_str(&format!("<span class=\"sep\">/</span>{}", esc_html(part)));
        }
    }
    out
}

/// A URL path as an href: each segment percent-encoded.
pub fn href_of(url_path: &str) -> String {
    let segs: Vec<String> = url_path.split('/').map(pct_encode).collect();
    let h = segs.join("/");
    if h.starts_with('/') {
        h
    } else {
        format!("/{}", h)
    }
}

fn eruda_for(eruda: bool) -> &'static str {
    if eruda {
        ERUDA_TAG
    } else {
        ""
    }
}

/// Fill `__KEY__` slots in ONE pass over the template: a value (a file name, the document
/// itself) is never re-scanned, so text that happens to spell a slot name cannot be substituted.
fn fill(tpl: &str, vals: &[(&str, String)]) -> String {
    let mut out = String::with_capacity(tpl.len() + 256);
    let mut rest = tpl;
    'scan: while let Some(i) = rest.find("__") {
        out.push_str(&rest[..i]);
        rest = &rest[i..];
        for (k, v) in vals {
            if rest.starts_with(k) {
                out.push_str(v);
                rest = &rest[k.len()..];
                continue 'scan;
            }
        }
        out.push_str("__");
        rest = &rest[2..];
    }
    out.push_str(rest);
    out
}

pub fn markdown_page(url_path: &str, md: &str, eruda: bool) -> String {
    fill(
        MARKDOWN_TPL,
        &[
            ("__TITLE__", esc_html(url_path)),
            ("__HREF__", esc_html(&href_of(url_path))),
            ("__CRUMBS__", breadcrumb(url_path)),
            ("__ERUDA__", eruda_for(eruda).to_string()),
            ("__RAWJS__", safe_json_for_script(md)),
        ],
    )
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Structured {
    Json,
    Yaml,
    Secrets,
}

impl Structured {
    pub fn name(self) -> &'static str {
        match self {
            Structured::Json => "json",
            Structured::Yaml => "yaml",
            Structured::Secrets => "secrets",
        }
    }
}

pub fn structured_page(url_path: &str, raw: &str, kind: Structured, eruda: bool) -> String {
    let file = url_path.rsplit('/').next().unwrap_or("");
    let (color, bg, border, text) = match kind {
        Structured::Secrets => ("#ff6b6b", "rgba(255,107,107,0.15)", "rgba(255,107,107,0.3)", "SECRETS"),
        _ => ("#5bc0eb", "rgba(91,192,235,0.15)", "rgba(91,192,235,0.3)", if kind == Structured::Yaml { "YAML" } else { "JSON" }),
    };
    fill(
        STRUCTURED_TPL,
        &[
            ("__TITLE__", esc_html(url_path)),
            ("__BADGE_COLOR__", color.to_string()),
            ("__BADGE_BG__", bg.to_string()),
            ("__BADGE_BORDER__", border.to_string()),
            ("__BADGE_TEXT__", text.to_string()),
            ("__HREF__", esc_html(&href_of(url_path))),
            ("__CRUMBS__", breadcrumb(url_path)),
            ("__FILENAME__", esc_html(file)),
            ("__ISSECRET__", (kind == Structured::Secrets).to_string()),
            ("__FMT__", jstr(kind.name())),
            ("__YAMLSCRIPT__", if kind == Structured::Yaml { "<script src=\"/__lib__/js-yaml.min.js\"></script>".to_string() } else { String::new() }),
            ("__ERUDA__", eruda_for(eruda).to_string()),
            ("__RAWJS__", safe_json_for_script(raw)),
        ],
    )
}

/// The file browser SPA, opened on `init_path`.
pub fn browse_page(init_path: &str) -> String {
    fill(BROWSE_TPL, &[("__INIT_PATH__", safe_json_for_script(init_path))])
}

/// Insert the Eruda tag before the last `</body>` (case-insensitive); append when there is none.
pub fn inject_eruda(html: &str) -> String {
    inject_before_body_end(html, ERUDA_TAG)
}

pub fn inject_before_body_end(html: &str, tag: &str) -> String {
    let lower = html.to_ascii_lowercase();
    match lower.find("</body>") {
        Some(i) => format!("{}{}{}", &html[..i], tag, &html[i..]),
        None => format!("{}{}", html, tag),
    }
}

/// `<base href=dir>` right after `<head>` unless the page names a base already.
pub fn inject_base(html: &str, dir_url: &str) -> String {
    let lower = html.to_ascii_lowercase();
    let has_base = lower.match_indices("<base").any(|(i, _)| lower[i + 5..].starts_with(|c: char| c == '>' || c.is_whitespace()));
    if has_base {
        return html.to_string();
    }
    if let Some(i) = lower.find("<head") {
        let rest = &lower[i + 5..];
        if rest.starts_with('>') || rest.starts_with(|c: char| c.is_whitespace()) {
            if let Some(end) = lower[i..].find('>') {
                let at = i + end + 1;
                return format!("{}<base href=\"{}\">{}", &html[..at], esc_html(dir_url), &html[at..]);
            }
        }
    }
    html.to_string()
}

/// SvelteKit adapter-static's 200.html names root-absolute assets; this server hosts many projects
/// under one root, so rewrite `href="/x"` and `src="/x"` to be absolute from the project's own dir.
pub fn rewrite_fallback_paths(html: &str, project_dir: &str) -> String {
    if project_dir == "/" || project_dir.is_empty() {
        return html.to_string();
    }
    let mut out = String::with_capacity(html.len() + 64);
    let b = html.as_bytes();
    let mut i = 0;
    while i < b.len() {
        let rest = &html[i..];
        let hit = ["href=\"/", "src=\"/"].iter().find(|p| rest.starts_with(**p));
        if let Some(p) = hit {
            // `//host/x` is protocol-relative, not root-absolute
            if !rest[p.len()..].starts_with('/') {
                out.push_str(&p[..p.len() - 1]);
                out.push_str(project_dir);
                out.push('/');
                i += p.len();
                continue;
            }
        }
        let ch = rest.chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

/// The plain index, used when the `browse` engine is off.
pub fn plain_listing(dir_url: &str, names: &[(bool, String)], eruda: bool) -> String {
    let mut html = String::new();
    html.push_str("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">");
    html.push_str(&format!("<title>Index of {}</title>", esc_html(dir_url)));
    html.push_str("<style>body{font:15px system-ui;background:#0b0e14;color:#e6e6e6;margin:0;padding:12px}a{color:#7cc4ff;text-decoration:none;display:block;padding:8px 4px;border-bottom:1px solid #1e2430}</style>");
    html.push_str(&format!("<h3>Index of {}</h3>", esc_html(dir_url)));
    if dir_url != "/" {
        html.push_str("<a href=\"../\">../</a>");
    }
    for (is_dir, name) in names {
        let suffix = if *is_dir { "/" } else { "" };
        html.push_str(&format!("<a href=\"{}{}\">{}{}</a>", esc_html(&pct_encode(name)), suffix, esc_html(name), suffix));
    }
    if eruda {
        html.push_str(ERUDA_TAG);
    }
    html
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_versions_we_print_are_the_versions_we_ship() {
        assert!(MARKED_JS.contains(&format!("marked v{}", MARKED_VERSION)));
        assert!(YAML_JS.contains(&format!("js-yaml {}", YAML_VERSION)));
        assert!(ERUDA_JS.contains(&format!("eruda v{}", ERUDA_VERSION)));
    }

    #[test]
    fn script_embedding_cannot_break_out() {
        let s = safe_json_for_script("a</script><script>alert(1)</script><!-- \u{2028}");
        assert!(!s.to_lowercase().contains("</script"), "{}", s);
        assert!(!s.contains("<!--"));
        assert!(!s.contains('\u{2028}'));
    }

    #[test]
    fn markdown_page_embeds_document_and_bundled_scripts_only() {
        let p = markdown_page("/docs/a b.md", "# hi </script>", true);
        assert!(p.contains("/__lib__/marked.min.js") && p.contains("/__lib__/github-markdown-dark.css"));
        assert!(p.contains("/__lib__/eruda.min.js"));
        assert!(!p.contains("cdn.jsdelivr") && !p.contains("https://"), "a rendered page must not name a CDN");
        assert!(p.contains("a%20b.md?raw"), "raw link is percent-encoded");
        assert!(!p.contains("__RAWJS__") && !p.contains("__TITLE__"));
        assert!(!markdown_page("/a.md", "x", false).contains("eruda"));
    }

    #[test]
    fn structured_pages_per_kind() {
        let j = structured_page("/d/x.json", "{\"a\":1}", Structured::Json, true);
        assert!(j.contains("const fmt = \"json\"") && j.contains("const isSecret = false") && j.contains(">JSON<"));
        assert!(!j.contains("/__lib__/js-yaml.min.js"), "json does not load the yaml parser");
        let y = structured_page("/d/x.yaml", "a: 1", Structured::Yaml, false);
        assert!(y.contains("/__lib__/js-yaml.min.js") && y.contains("const fmt = \"yaml\""));
        assert!(!y.contains("eruda"));
        let s = structured_page("/d/x.secrets", "{}", Structured::Secrets, true);
        assert!(s.contains("const isSecret = true") && s.contains("#ff6b6b"));
        for k in ["__TITLE__", "__BADGE_", "__HREF__", "__CRUMBS__", "__FILENAME__", "__ISSECRET__", "__FMT__", "__YAMLSCRIPT__", "__ERUDA__", "__RAWJS__"] {
            assert!(!s.contains(k) && !j.contains(k) && !y.contains(k), "slot {} left unfilled in a page", k);
        }
    }

    #[test]
    fn a_value_is_never_rescanned_for_slots() {
        let p = markdown_page("/__RAWJS__.md", "doc", false);
        assert!(p.contains("<title>/__RAWJS__.md</title>"), "a file named like a slot keeps its name");
        assert!(p.contains("const rawMarkdown = \"doc\""));
    }

    #[test]
    fn breadcrumb_escapes_names() {
        let b = breadcrumb("/a<b>/c.md");
        assert!(b.contains("a&lt;b&gt;") && !b.contains("<b>"));
    }

    #[test]
    fn eruda_and_base_injection() {
        assert_eq!(inject_eruda("<body>x</BODY>"), format!("<body>x{}</BODY>", ERUDA_TAG));
        assert!(inject_eruda("plain").ends_with(ERUDA_TAG));
        assert!(inject_base("<html><head><title>t</title>", "/p/").contains("<head><base href=\"/p/\">"));
        assert_eq!(inject_base("<head><base href=\"/x/\">", "/p/"), "<head><base href=\"/x/\">");
        assert!(inject_base("<head data-a=1>", "/p/").contains("<base href=\"/p/\">"));
        assert_eq!(inject_base("<header>", "/p/"), "<header>", "<header> is not <head>");
    }

    #[test]
    fn fallback_paths_become_project_absolute() {
        let h = "<link href=\"/_app/a.css\"><script src=\"/_app/b.js\"></script><a href=\"//cdn.x/y\">";
        let r = rewrite_fallback_paths(h, "/proj");
        assert!(r.contains("href=\"/proj/_app/a.css\"") && r.contains("src=\"/proj/_app/b.js\""));
        assert!(r.contains("href=\"//cdn.x/y\""), "protocol-relative stays");
        assert_eq!(rewrite_fallback_paths(h, "/"), h);
    }

    #[test]
    fn browse_page_carries_init_path_and_is_phone_sized() {
        let p = browse_page("/a/</script>");
        assert!(p.contains("const INIT_PATH = \"/a/<\\/script>\";"));
        assert!(p.contains("width=device-width"));
    }
}
