//! The fleet-diagnostics page (cloud.app, :8003) and its data.
//!
//! The data is the SuperApp's About, Logcat and Diagnose. This crate cannot read
//! them itself (a plain process sees only its own logcat, and About / Diagnose
//! live inside the SuperApp), so it asks the SuperApp's bridge: an exported,
//! signature-permission ContentProvider (CONSTELLATION_DATA, authority
//! `<superapp>.fleetdiag`; aa_cloud-superapp FleetDiagnosticsProvider). The
//! provider call is JNI and belongs to the Tauri crate, which installs it here
//! with [`set_source`] / [`set_opener`]; this crate stays platform-free and its
//! tests run against a stub source.

use crate::{Ctx, Req, Resp};
use fleet_webserver::render::jstr;
use std::sync::OnceLock;

/// (method, arg) -> Ok((mime, payload)) | Err(reason). Methods: about, logcat, diagnose.
pub type Source = dyn Fn(&str, &str) -> Result<(String, String), String> + Send + Sync;
/// Hand a URL to cloud-browser. Err = it could not be opened (not installed, refused).
pub type Opener = dyn Fn(&str) -> Result<(), String> + Send + Sync;

static SOURCE: OnceLock<Box<Source>> = OnceLock::new();
static OPENER: OnceLock<Box<Opener>> = OnceLock::new();

pub fn set_source(f: Box<Source>) -> bool {
    SOURCE.set(f).is_ok()
}

pub fn set_opener(f: Box<Opener>) -> bool {
    OPENER.set(f).is_ok()
}

pub fn open(url: &str) -> Result<(), String> {
    match OPENER.get() {
        Some(f) => f(url),
        None => Err("no browser opener is installed in this process".to_string()),
    }
}

pub const PAGE: &str = include_str!("../ui/diag.html");

pub fn page(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::html(PAGE.to_string())
}

fn answer(ctx: &Ctx, method: &str, arg: &str) -> Resp {
    let src = match SOURCE.get() {
        Some(s) => s,
        None => return Resp::json(503, format!("{{\"error\":{}}}", jstr("no diagnostics bridge in this process"))),
    };
    match src(method, arg) {
        Ok((mime, payload)) => {
            let ctype: &'static str = if mime.contains("json") { "application/json; charset=utf-8" } else { "text/plain; charset=utf-8" };
            Resp::bytes(200, ctype, payload.into_bytes())
        }
        Err(e) => Resp::json(503, format!("{{\"error\":{}}}", jstr(&e))),
    }
}

pub fn api_about(ctx: &Ctx, _req: &Req) -> Resp {
    answer(ctx, "about", "")
}

/// ?n=<lines, default 500, at most 5000>&scope=app|all&errors=1
pub fn api_logcat(ctx: &Ctx, req: &Req) -> Resp {
    let n = req.q("n").and_then(|v| v.parse::<u32>().ok()).unwrap_or(500).clamp(1, 5000);
    let scope = if req.q("scope") == Some("all") { "all" } else { "app" };
    let errors = if req.q("errors") == Some("1") { 1 } else { 0 };
    answer(ctx, "logcat", &format!("n={}&scope={}&errors={}", n, scope, errors))
}

pub fn api_diagnose(ctx: &Ctx, _req: &Req) -> Resp {
    answer(ctx, "diagnose", "")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_page_reads_only_the_three_diag_apis_and_loads_nothing_remote() {
        for api in ["/__api__/diag/about", "/__api__/diag/logcat", "/__api__/diag/diagnose"] {
            assert!(PAGE.contains(api), "{} missing from the page", api);
        }
        assert!(!PAGE.contains("https://") && !PAGE.contains("//cdn"), "the page must not load anything remote");
        for tab in ["About", "Logcat", "Diagnose"] {
            assert!(PAGE.contains(tab), "{} tab missing", tab);
        }
    }
}
