// Build-time bundle of the Linux frontend. Nothing here is written by hand and nothing is fetched:
//   1. vendor/ must hash to vendor/VENDOR.sha256 (sync.sh writes both from cloud-u-linux). A vendor/
//      that was edited, or half-synced, stops the build here.
//   2. The two server-rendered pages (markdown, JSON/YAML/secrets) are not files in the Linux repo:
//      they are JS template literals inside my-webserver.cjs. They are cut out of the vendored .cjs
//      HERE, `${expr}` becomes a named slot, and the result is compiled into the binary. An
//      expression this script does not know stops the build, so a Linux change to those pages is
//      either picked up verbatim or fails loudly; it can never be silently half-ported.
//   3. Linux loads Eruda from a CDN; the phone must not download anything at runtime. The CDN tag
//      is asserted to be exactly what we expect and replaced by the bundled assets/eruda.min.js.
use std::path::{Path, PathBuf};
use std::{env, fs};

#[path = "src/sha256.rs"]
mod sha256;

const SLOT: char = '\u{1}';

fn main() {
    let dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let vendor = dir.join("vendor");
    println!("cargo:rerun-if-changed={}", vendor.display());
    println!("cargo:rerun-if-changed=build.rs");

    let sums = fs::read_to_string(vendor.join("VENDOR.sha256")).expect("vendor/VENDOR.sha256 missing: run sync.sh --from <cloud-u-linux>");
    let mut n = 0;
    for line in sums.lines().filter(|l| !l.trim().is_empty()) {
        let (want, name) = line.split_once("  ").unwrap_or_else(|| panic!("bad VENDOR.sha256 line: {}", line));
        let got = sha256::sha256_hex(&fs::read(vendor.join(name)).unwrap_or_else(|e| panic!("vendor/{}: {}", name, e)));
        assert_eq!(got, want, "vendor/{} does not match VENDOR.sha256: it was edited by hand. Re-run sync.sh --from <cloud-u-linux>", name);
        n += 1;
    }
    assert!(n >= 5, "VENDOR.sha256 lists {} files, expected the 5 vendored ones", n);

    let cjs = fs::read_to_string(vendor.join("my-webserver.cjs")).unwrap();
    let eruda_line = cjs.lines().find(|l| l.starts_with("const ERUDA_SCRIPT = ")).expect("ERUDA_SCRIPT not found in my-webserver.cjs");
    assert!(
        eruda_line.contains("<script src=\"https://cdn.jsdelivr.net/npm/eruda\"></script><script>eruda.init();</script>"),
        "my-webserver.cjs changed its Eruda tag: update build.rs to match"
    );

    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let md = template(&cjs, "function markdownPage(");
    let st = template(&cjs, "function structuredPage(");
    let src = format!("pub const MARKDOWN_TPL: &str = {:?};\npub const STRUCTURED_TPL: &str = {:?};\n", md, st);
    fs::write(Path::new(&out).join("templates.rs"), src).unwrap();
}

/// The cooked value of the first template literal returned by the function that starts at `func`,
/// with each `${expr}` replaced by `\u{1}slot\u{1}`.
fn template(cjs: &str, func: &str) -> String {
    let f = cjs.find(func).unwrap_or_else(|| panic!("{} not found in my-webserver.cjs", func));
    let r = cjs[f..].find("return `").unwrap_or_else(|| panic!("no template literal in {}", func)) + f + "return `".len();
    let b: Vec<char> = cjs[r..].chars().collect();
    let mut out = String::new();
    let mut i = 0;
    loop {
        let c = *b.get(i).unwrap_or_else(|| panic!("unterminated template literal in {}", func));
        match c {
            '`' => break,
            '\\' => {
                i += 1;
                match b[i] {
                    '\\' => out.push('\\'),
                    '`' => out.push('`'),
                    '$' => out.push('$'),
                    'n' => out.push('\n'),
                    't' => out.push('\t'),
                    '\'' => out.push('\''),
                    '"' => out.push('"'),
                    'r' => out.push('\r'),
                    'b' => out.push('\u{8}'),
                    'f' => out.push('\u{c}'),
                    'v' => out.push('\u{b}'),
                    '0' if !b.get(i + 1).map_or(false, |c| c.is_ascii_digit()) => out.push('\0'),
                    'x' => {
                        let h: String = b[i + 1..i + 3].iter().collect();
                        out.push(char::from_u32(u32::from_str_radix(&h, 16).expect("bad \\x escape")).unwrap());
                        i += 2;
                    }
                    'u' => {
                        let (h, used) = if b[i + 1] == '{' {
                            let end = b[i + 1..].iter().position(|c| *c == '}').expect("bad \\u{} escape");
                            (b[i + 2..i + 1 + end].iter().collect::<String>(), end + 1)
                        } else {
                            (b[i + 1..i + 5].iter().collect::<String>(), 4)
                        };
                        out.push(char::from_u32(u32::from_str_radix(&h, 16).expect("bad \\u escape")).unwrap());
                        i += used;
                    }
                    '\n' => {}
                    // JS cooks an unknown escape (\d, \w, \/ ...) to the bare character, exactly as Node serves it.
                    other if !other.is_ascii_digit() => out.push(other),
                    other => panic!("octal escape \\{} in {}", other, func),
                }
            }
            '$' if b.get(i + 1) == Some(&'{') => {
                let mut depth = 1;
                let mut j = i + 2;
                while depth > 0 {
                    match b[j] {
                        '{' => depth += 1,
                        '}' => depth -= 1,
                        _ => {}
                    }
                    j += 1;
                }
                let expr: String = b[i + 2..j - 1].iter().collect();
                let expr = expr.trim();
                let in_href = out.ends_with("href=\"");
                let slot = match expr {
                    "urlPath" if in_href => "urlPath:href",
                    "urlPath" => "urlPath",
                    "breadcrumb(urlPath)" => "breadcrumb",
                    "safeJsonForScript(mdContent)" | "safeJsonForScript(rawContent)" => "rawjs",
                    "ERUDA_SCRIPT" => "eruda",
                    "fileName" => "fileName",
                    "badgeColor" => "badgeColor",
                    "badgeBg" => "badgeBg",
                    "badgeBorder" => "badgeBorder",
                    "badgeText" => "badgeText",
                    "isSecret" => "isSecret",
                    "JSON.stringify(format)" => "formatJson",
                    "format === 'yaml' ? '<script src=\"/__lib__/js-yaml.min.js\"></script>' : ''" => "yamlScript",
                    other => panic!("{}: template expression `{}` has no slot in build.rs", func, other),
                };
                out.push(SLOT);
                out.push_str(slot);
                out.push(SLOT);
                i = j;
                continue;
            }
            c => out.push(c),
        }
        i += 1;
    }
    out
}
