//! The frontend is ONE copy: cloud-u-linux/da_my-webserver/src. vendor/ is a byte-identical,
//! hash-checked mirror of it; these tests fail the moment either side moves without the other.
mod common;
use cloud_webserver::sha256::sha256_hex;
use std::fs;
use std::path::PathBuf;

const MAP: &[(&str, &str)] = &[
    ("browse.html", "lib/browse.html"),
    ("github-markdown-dark.css", "lib/github-markdown-dark.css"),
    ("js-yaml.min.js", "lib/js-yaml.min.js"),
    ("marked.min.js", "lib/marked.min.js"),
    ("my-webserver.cjs", "my-webserver.cjs"),
];

fn vendor() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("vendor")
}

#[test]
fn sha256_matches_the_reference_vectors() {
    assert_eq!(sha256_hex(b""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assert_eq!(sha256_hex(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    let long = vec![b'a'; 1000];
    assert_eq!(sha256_hex(&long), "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3");
}

#[test]
fn bundled_frontend_hash_equals_the_linux_repos_copy() {
    let linux = common::linux_repo().join("da_my-webserver/src");
    let sums = fs::read_to_string(vendor().join("VENDOR.sha256")).unwrap();
    for (mine, theirs) in MAP {
        let bundled = fs::read(vendor().join(mine)).unwrap();
        let source = fs::read(linux.join(theirs)).unwrap_or_else(|e| panic!("{}: {}", theirs, e));
        assert_eq!(
            sha256_hex(&bundled),
            sha256_hex(&source),
            "vendor/{} drifted from cloud-u-linux da_my-webserver/src/{}: run sync.sh --from <cloud-u-linux>",
            mine,
            theirs
        );
        assert_eq!(bundled, source, "vendor/{} is not byte-identical", mine);
        assert!(sums.contains(&format!("{}  {}", sha256_hex(&bundled), mine)), "VENDOR.sha256 does not list the bundled {}", mine);
    }
}

#[test]
fn the_binary_embeds_exactly_the_vendored_bytes() {
    use cloud_webserver::render;
    for (mine, got) in [
        ("browse.html", render::BROWSE_HTML),
        ("github-markdown-dark.css", render::MARKDOWN_CSS),
        ("js-yaml.min.js", render::YAML_JS),
        ("marked.min.js", render::MARKED_JS),
        ("my-webserver.cjs", render::MY_WEBSERVER_CJS),
    ] {
        assert_eq!(sha256_hex(got.as_bytes()), sha256_hex(&fs::read(vendor().join(mine)).unwrap()), "the compiled-in {} is not vendor/{}", mine, mine);
    }
}

#[test]
fn nothing_is_downloaded_at_runtime() {
    use cloud_webserver::render;
    for page in [
        render::markdown_page("/a.md", "x"),
        render::structured_page("/a.yaml", "a: 1", render::Structured::Yaml),
        render::structured_page("/a.json", "{}", render::Structured::Json),
        render::browse_page("/"),
    ] {
        for host in ["cdn.jsdelivr", "unpkg.com", "cdnjs", "googleapis"] {
            assert!(!page.contains(host), "a served page names {}", host);
        }
    }
}
