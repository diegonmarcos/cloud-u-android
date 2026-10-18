//! The pages the Linux my-webserver renders, from the Linux frontend itself.
//!
//! Nothing in here is a design: the browse SPA is vendor/browse.html, the two rendered pages are
//! the template literals of vendor/my-webserver.cjs (cut out by build.rs), the stylesheet and the
//! parsers are the vendored files. What this module owns is only the slot filling and the
//! escaping of the values that go into them. Every script a page loads is bundled in the binary
//! and served from /__lib__/; nothing is fetched from a CDN (the Node original pointed Eruda at
//! cdn.jsdelivr.net; build.rs asserts that and swaps in the bundled copy).

include!(concat!(env!("OUT_DIR"), "/templates.rs"));

pub const BROWSE_HTML: &str = include_str!("../vendor/browse.html");
pub const MARKED_JS: &str = include_str!("../vendor/marked.min.js");
pub const MARKDOWN_CSS: &str = include_str!("../vendor/github-markdown-dark.css");
pub const YAML_JS: &str = include_str!("../vendor/js-yaml.min.js");
pub const ERUDA_JS: &str = include_str!("../assets/eruda.min.js");
pub const MY_WEBSERVER_CJS: &str = include_str!("../vendor/my-webserver.cjs");

pub const MARKED_VERSION: &str = "15.0.12";
pub const YAML_VERSION: &str = "4.1.0";
pub const ERUDA_VERSION: &str = "3.4.3";

/// The tag every HTML page gets: same-origin and bundled.
pub const ERUDA_TAG: &str = "<script src=\"/__lib__/eruda.min.js\"></script><script>eruda.init();</script>";

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

/// Embed a string in an inline <script> as a JS string literal. The HTML parser closes a <script>
/// on the first `</script`, so that, `<!--` and the two JS line terminators are escaped.
pub fn safe_json_for_script(s: &str) -> String {
    let mut o = jstr(s);
    o = o.replace("</script", "<\\/script").replace("</SCRIPT", "<\\/SCRIPT");
    o = o.replace("<!--", "<\\!--");
    o = o.replace('\u{2028}', "\\u2028").replace('\u{2029}', "\\u2029");
    o
}

/// `~ / dir / dir / file` with every segment escaped.
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

/// Fill the `\u{1}slot\u{1}` markers build.rs left in a template in ONE pass: a value (a file
/// name, the document itself) is never re-scanned, so text that spells a slot name is inert.
fn fill(tpl: &str, vals: &[(&str, String)]) -> String {
    let mut out = String::with_capacity(tpl.len() + 1024);
    let mut rest = tpl;
    while let Some(i) = rest.find('\u{1}') {
        out.push_str(&rest[..i]);
        let after = &rest[i + 1..];
        let j = after.find('\u{1}').expect("unterminated slot");
        let name = &after[..j];
        let v = vals.iter().find(|(k, _)| *k == name).unwrap_or_else(|| panic!("slot {} has no value", name));
        out.push_str(&v.1);
        rest = &after[j + 1..];
    }
    out.push_str(rest);
    out
}

pub fn markdown_page(url_path: &str, md: &str) -> String {
    fill(
        MARKDOWN_TPL,
        &[
            ("urlPath", esc_html(url_path)),
            ("urlPath:href", esc_html(&href_of(url_path))),
            ("breadcrumb", breadcrumb(url_path)),
            ("rawjs", safe_json_for_script(md)),
            ("eruda", ERUDA_TAG.to_string()),
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

pub fn structured_page(url_path: &str, raw: &str, kind: Structured) -> String {
    let file = url_path.rsplit('/').next().unwrap_or("");
    let secret = kind == Structured::Secrets;
    fill(
        STRUCTURED_TPL,
        &[
            ("urlPath", esc_html(url_path)),
            ("urlPath:href", esc_html(&href_of(url_path))),
            ("breadcrumb", breadcrumb(url_path)),
            ("fileName", esc_html(file)),
            ("badgeColor", if secret { "#ff6b6b" } else { "#5bc0eb" }.to_string()),
            ("badgeBg", if secret { "rgba(255,107,107,0.15)" } else { "rgba(91,192,235,0.15)" }.to_string()),
            ("badgeBorder", if secret { "rgba(255,107,107,0.3)" } else { "rgba(91,192,235,0.3)" }.to_string()),
            ("badgeText", if secret { "DECRYPTED".to_string() } else { kind.name().to_uppercase() }),
            ("isSecret", secret.to_string()),
            ("formatJson", jstr(kind.name())),
            ("yamlScript", if kind == Structured::Yaml { "<script src=\"/__lib__/js-yaml.min.js\"></script>".to_string() } else { String::new() }),
            ("rawjs", safe_json_for_script(raw)),
            ("eruda", ERUDA_TAG.to_string()),
        ],
    )
}

/// The file browser SPA (vendor/browse.html), opened on `init_path`.
pub fn browse_page(init_path: &str) -> String {
    BROWSE_HTML.replacen("__INIT_PATH__", &safe_json_for_script(init_path), 1)
}

/// Insert the Eruda tag before the last `</body>` (case-insensitive); append when there is none.
pub fn inject_eruda(html: &str) -> String {
    let lower = html.to_ascii_lowercase();
    match lower.rfind("</body>") {
        Some(i) => format!("{}{}{}", &html[..i], ERUDA_TAG, &html[i..]),
        None => format!("{}{}", html, ERUDA_TAG),
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

/// SvelteKit adapter-static's 200.html names root-absolute assets; rewrite `href="/x"` and
/// `src="/x"` to be absolute from the project's own dir.
pub fn rewrite_fallback_paths(html: &str, project_dir: &str) -> String {
    if project_dir == "/" || project_dir.is_empty() {
        return html.to_string();
    }
    let mut out = String::with_capacity(html.len() + 64);
    let mut i = 0;
    while i < html.len() {
        let rest = &html[i..];
        let hit = ["href=\"/", "src=\"/"].iter().find(|p| rest.starts_with(**p));
        if let Some(p) = hit {
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
