//! About this phone, from what a plain process on Android can read: getprop,
//! /proc, /sys and statvfs. The Home tab mirrors cloud-superapp's Configs ▸
//! About: an index of MACRO groups, each group a run of sections, each section
//! key/value rows, every group closing with a way back to the index and the
//! page offering "Copy All Infos". The macro labels are superapp's own
//! (DevControlFragment.kt's macroHeader calls), in superapp's order; the
//! groups superapp has and a plain process cannot fill (REPO & RELEASES, MESH,
//! DEV TOOLS, LOGCAT) are left out rather than faked. A source that is absent
//! on the host says so in its one row rather than being skipped, so the page
//! never silently loses a section.

use crate::{routes, Ctx};
use std::fs;
use std::process::Command;

pub type Rows = Vec<(String, String)>;

fn row(rows: &mut Rows, k: &str, v: impl Into<String>) {
    rows.push((k.to_string(), v.into()));
}

fn read(path: &str) -> Option<String> {
    fs::read_to_string(path).ok().map(|s| s.trim().to_string())
}

fn dash(v: Option<String>) -> String {
    match v {
        Some(s) if !s.is_empty() => s,
        _ => "—".to_string(),
    }
}

pub fn fmt_bytes(n: u64) -> String {
    const UNITS: [&str; 6] = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
    let mut v = n as f64;
    let mut i = 0;
    while v >= 1024.0 && i < UNITS.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if i == 0 {
        format!("{} B", n)
    } else {
        format!("{:.1} {}", v, UNITS[i])
    }
}

fn fmt_secs(s: u64) -> String {
    let (d, h, m) = (s / 86400, (s % 86400) / 3600, (s % 3600) / 60);
    if d > 0 {
        format!("{}d {}h {}m", d, h, m)
    } else if h > 0 {
        format!("{}h {}m", h, m)
    } else {
        format!("{}m {}s", m, s % 60)
    }
}

/// `getprop` once, parsed into (key, value). Empty when the host has no getprop.
fn getprops() -> Vec<(String, String)> {
    let out = match Command::new("getprop").output() {
        Ok(o) if o.status.success() => o.stdout,
        _ => return Vec::new(),
    };
    let text = String::from_utf8_lossy(&out);
    let mut props = Vec::new();
    for line in text.lines() {
        // [ro.product.model]: [Pixel 8]
        if let Some(rest) = line.strip_prefix('[') {
            if let Some(i) = rest.find("]: [") {
                let key = &rest[..i];
                let val = rest[i + 4..].trim_end_matches(']');
                props.push((key.to_string(), val.to_string()));
            }
        }
    }
    props
}

fn prop<'a>(props: &'a [(String, String)], key: &str) -> Option<&'a str> {
    props.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str()).filter(|v| !v.is_empty())
}

fn meminfo_kb(text: &str, key: &str) -> Option<u64> {
    text.lines()
        .find(|l| l.starts_with(key) && l[key.len()..].starts_with(':'))
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|n| n.parse::<u64>().ok())
}

fn disk(path: &str) -> Option<(u64, u64)> {
    let c = std::ffi::CString::new(path).ok()?;
    let mut s: libc::statvfs = unsafe { std::mem::zeroed() };
    let rc = unsafe { libc::statvfs(c.as_ptr(), &mut s) };
    if rc != 0 {
        return None;
    }
    let frsize = s.f_frsize as u64;
    Some((s.f_blocks as u64 * frsize, s.f_bavail as u64 * frsize))
}

fn disk_rows(rows: &mut Rows, label: &str, path: &str) {
    match disk(path) {
        Some((total, avail)) => {
            let used = total.saturating_sub(avail);
            let pct = if total > 0 { used * 100 / total } else { 0 };
            row(rows, label, format!("{} — {} free of {} ({}% used)", path, fmt_bytes(avail), fmt_bytes(total), pct));
        }
        None => row(rows, label, format!("{} — not mounted or not readable", path)),
    }
}

/// The macro groups, in page order. Each label is byte-for-byte one of
/// cloud-superapp's About macro headers (test/test-c3-webserver.sh T7 reads
/// both files and fails on a label superapp does not have or an order it does
/// not use).
pub const MACROS: &[&str] = &[
    "📱  APP & BUILD",
    "🖥️  DEVICE",
    "🔐  SECURITY",
    "🔋  RESOURCES",
    "🌐  NETWORK",
    "🔌  API",
    "🌐  WEBSERVER",
];

pub type Section = (String, Rows);

/// (macro label, its sections), one entry per [`MACROS`] label, in order.
pub fn groups(ctx: &Ctx) -> Vec<(&'static str, Vec<Section>)> {
    let props = getprops();
    let p = |k: &str| dash(prop(&props, k).map(|s| s.to_string()));
    let no_getprop = "not available on this host (not Android)";

    // ── 📱 APP & BUILD ─────────────────────────────────────────────────────
    let mut app = Rows::new();
    row(&mut app, "Name", env!("CARGO_PKG_NAME"));
    row(&mut app, "Version", env!("CARGO_PKG_VERSION"));
    row(&mut app, "Process image", ctx.binary.clone());
    row(&mut app, "PID", std::process::id().to_string());
    row(&mut app, "UID / GID", format!("{} / {}", unsafe { libc::getuid() }, unsafe { libc::getgid() }));
    row(&mut app, "Server uptime", fmt_secs(ctx.started.elapsed().as_secs()));
    if let Some(status) = read("/proc/self/status") {
        row(&mut app, "Threads", dash(meminfo_kb(&status, "Threads").map(|n| n.to_string())));
        row(&mut app, "RSS", dash(meminfo_kb(&status, "VmRSS").map(|kb| fmt_bytes(kb * 1024))));
    }
    row(&mut app, "HOME", dash(std::env::var("HOME").ok()));
    row(&mut app, "TMPDIR", dash(std::env::var("TMPDIR").ok()));
    let mut stack = Rows::new();
    row(&mut stack, "Server", format!("{} {} — Rust std, one thread per connection", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION")));
    row(&mut stack, "UI", "server/ui/shell.html, served at / and rendered by the app's webview");
    row(&mut stack, "Rust target", format!("{} / {}", std::env::consts::ARCH, std::env::consts::OS));

    // ── 🖥️ DEVICE ─────────────────────────────────────────────────────────
    let mut device = Rows::new();
    if props.is_empty() {
        row(&mut device, "getprop", no_getprop);
    } else {
        row(&mut device, "Manufacturer", p("ro.product.manufacturer"));
        row(&mut device, "Model", p("ro.product.model"));
        row(&mut device, "Brand", p("ro.product.brand"));
        row(&mut device, "Device", p("ro.product.device"));
        row(&mut device, "Hardware", p("ro.hardware"));
        row(&mut device, "Board", p("ro.product.board"));
        row(&mut device, "Android", format!("{} (SDK {})", p("ro.build.version.release"), p("ro.build.version.sdk")));
        row(&mut device, "Security patch", p("ro.build.version.security_patch"));
        row(&mut device, "Codename", p("ro.build.version.codename"));
        row(&mut device, "Incremental", p("ro.build.version.incremental"));
        row(&mut device, "Fingerprint", p("ro.build.fingerprint"));
        row(&mut device, "Bootloader", p("ro.bootloader"));
        row(&mut device, "SoC", format!("{} {}", p("ro.soc.manufacturer"), p("ro.soc.model")));
        row(&mut device, "ABIs", p("ro.product.cpu.abilist"));
    }

    let mut kernel = Rows::new();
    row(&mut kernel, "Kernel", dash(read("/proc/sys/kernel/osrelease")));
    row(&mut kernel, "/proc/version", dash(read("/proc/version")));
    row(&mut kernel, "Hostname", dash(read("/proc/sys/kernel/hostname")));
    let up = read("/proc/uptime").and_then(|s| s.split_whitespace().next().and_then(|n| n.parse::<f64>().ok()));
    row(&mut kernel, "System uptime", dash(up.map(|s| fmt_secs(s as u64))));
    row(&mut kernel, "Load average", dash(read("/proc/loadavg").map(|s| s.split_whitespace().take(3).collect::<Vec<_>>().join(" "))));

    let mut cpu = Rows::new();
    row(&mut cpu, "CPU cores", std::thread::available_parallelism().map(|n| n.get().to_string()).unwrap_or_else(|_| "—".to_string()));
    if let Some(info) = read("/proc/cpuinfo") {
        let pick = |key: &str| {
            info.lines()
                .find(|l| l.to_lowercase().starts_with(key))
                .and_then(|l| l.split(':').nth(1))
                .map(|v| v.trim().to_string())
        };
        row(&mut cpu, "CPU model", dash(pick("model name").or_else(|| pick("hardware")).or_else(|| pick("processor"))));
        row(&mut cpu, "Features", dash(pick("features").or_else(|| pick("flags")).map(|f| {
            let words: Vec<&str> = f.split_whitespace().collect();
            if words.len() > 24 { format!("{} … ({} flags)", words[..24].join(" "), words.len()) } else { f.clone() }
        })));
    }
    let khz = |p: &str| read(p).and_then(|s| s.parse::<u64>().ok());
    row(&mut cpu, "cpu0 cur freq", dash(khz("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").map(|k| format!("{} MHz", k / 1000))));
    row(&mut cpu, "cpu0 max freq", dash(khz("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").map(|k| format!("{} MHz", k / 1000))));
    row(&mut cpu, "Governor", dash(read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")));
    let mut max_all: Option<u64> = None;
    if let Ok(rd) = fs::read_dir("/sys/devices/system/cpu") {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if n.starts_with("cpu") && n[3..].chars().all(|c| c.is_ascii_digit()) {
                if let Some(k) = khz(&format!("/sys/devices/system/cpu/{}/cpufreq/cpuinfo_max_freq", n)) {
                    max_all = Some(max_all.map_or(k, |m| m.max(k)));
                }
            }
        }
    }
    row(&mut cpu, "Max freq (any core)", dash(max_all.map(|k| format!("{} MHz", k / 1000))));

    let mut locale = Rows::new();
    if !props.is_empty() {
        row(&mut locale, "Timezone", p("persist.sys.timezone"));
        row(&mut locale, "Locale", p("persist.sys.locale"));
    }
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    row(&mut locale, "Epoch (s)", now.to_string());
    row(&mut locale, "UTC", utc_string(now));
    row(&mut locale, "TZ env", dash(std::env::var("TZ").ok()));

    // ── 🔐 SECURITY ───────────────────────────────────────────────────────
    let mut security = Rows::new();
    if props.is_empty() {
        row(&mut security, "getprop", no_getprop);
    } else {
        row(&mut security, "Verified boot", p("ro.boot.verifiedbootstate"));
        row(&mut security, "Bootloader locked", p("ro.boot.flash.locked"));
        row(&mut security, "Encryption", format!("{} ({})", p("ro.crypto.state"), p("ro.crypto.type")));
        row(&mut security, "Build type / tags", format!("{} / {}", p("ro.build.type"), p("ro.build.tags")));
        row(&mut security, "ro.secure / ro.debuggable", format!("{} / {}", p("ro.secure"), p("ro.debuggable")));
        row(&mut security, "USB config", p("sys.usb.config"));
        row(&mut security, "ADB over network port", p("service.adb.tcp.port"));
    }
    row(&mut security, "SELinux", dash(read("/sys/fs/selinux/enforce").map(|v| if v == "1" { "enforcing".to_string() } else { format!("permissive ({})", v) })
        .or_else(|| prop(&props, "ro.boot.selinux").map(|s| s.to_string()))));

    // ── 🔋 RESOURCES ──────────────────────────────────────────────────────
    let mut storage = Rows::new();
    disk_rows(&mut storage, "Served root", &ctx.root.to_string_lossy());
    if let Ok(home) = std::env::var("HOME") {
        disk_rows(&mut storage, "App data", &home);
    }
    disk_rows(&mut storage, "/data", "/data");

    let mut battery = Rows::new();
    let bat = "/sys/class/power_supply/battery";
    if std::path::Path::new(bat).is_dir() {
        let b = |f: &str| dash(read(&format!("{}/{}", bat, f)));
        row(&mut battery, "Level", format!("{}%", b("capacity")));
        row(&mut battery, "Status", b("status"));
        row(&mut battery, "Health", b("health"));
        row(&mut battery, "Technology", b("technology"));
        row(&mut battery, "Temperature", dash(read(&format!("{}/temp", bat)).and_then(|t| t.parse::<i64>().ok()).map(|t| format!("{:.1} °C", t as f64 / 10.0))));
        row(&mut battery, "Voltage", dash(read(&format!("{}/voltage_now", bat)).and_then(|t| t.parse::<i64>().ok()).map(|v| format!("{:.3} V", v as f64 / 1_000_000.0))));
        row(&mut battery, "Current", dash(read(&format!("{}/current_now", bat)).and_then(|t| t.parse::<i64>().ok()).map(|c| format!("{} mA", c / 1000))));
        row(&mut battery, "Cycle count", b("cycle_count"));
    } else {
        row(&mut battery, "Battery", "no /sys/class/power_supply/battery on this host");
    }

    let mut memory = Rows::new();
    match read("/proc/meminfo") {
        Some(m) => {
            let kb = |k: &str| dash(meminfo_kb(&m, k).map(|v| fmt_bytes(v * 1024)));
            row(&mut memory, "Total RAM", kb("MemTotal"));
            row(&mut memory, "Available", kb("MemAvailable"));
            row(&mut memory, "Free", kb("MemFree"));
            row(&mut memory, "Cached", kb("Cached"));
            row(&mut memory, "Swap total", kb("SwapTotal"));
            row(&mut memory, "Swap free", kb("SwapFree"));
        }
        None => row(&mut memory, "/proc/meminfo", "not readable"),
    }

    // SYSFS-PROC: the thermal zones, superapp's raw-telemetry section.
    let mut sysfs = Rows::new();
    let mut zones: Vec<String> = fs::read_dir("/sys/class/thermal")
        .map(|rd| rd.flatten().map(|e| e.file_name().to_string_lossy().to_string()).filter(|n| n.starts_with("thermal_zone")).collect())
        .unwrap_or_default();
    zones.sort_by_key(|z| z["thermal_zone".len()..].parse::<u32>().unwrap_or(u32::MAX));
    for z in zones.iter().take(16) {
        let base = format!("/sys/class/thermal/{}", z);
        let temp = read(&format!("{}/temp", base)).and_then(|t| t.parse::<i64>().ok()).map(|t| format!("{:.1} °C", t as f64 / 1000.0));
        row(&mut sysfs, &dash(read(&format!("{}/type", base))), format!("{} ({})", dash(temp), z));
    }
    if zones.is_empty() {
        row(&mut sysfs, "/sys/class/thermal", "no thermal zone readable on this host");
    } else if zones.len() > 16 {
        row(&mut sysfs, "…", format!("{} more zones", zones.len() - 16));
    }

    // ── 🌐 NETWORK ────────────────────────────────────────────────────────
    let mut network = Rows::new();
    let mut ifaces: Vec<String> = fs::read_dir("/sys/class/net")
        .map(|rd| rd.flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect())
        .unwrap_or_default();
    ifaces.sort();
    if ifaces.is_empty() {
        row(&mut network, "/sys/class/net", "not readable");
    }
    for i in ifaces {
        let base = format!("/sys/class/net/{}", i);
        let state = dash(read(&format!("{}/operstate", base)));
        let rx = read(&format!("{}/statistics/rx_bytes", base)).and_then(|s| s.parse::<u64>().ok()).map(fmt_bytes);
        let tx = read(&format!("{}/statistics/tx_bytes", base)).and_then(|s| s.parse::<u64>().ok()).map(fmt_bytes);
        row(&mut network, &i, format!("{} · rx {} · tx {} · mtu {}", state, dash(rx), dash(tx), dash(read(&format!("{}/mtu", base)))));
    }
    let mut firewall = Rows::new();
    row(&mut firewall, "Bind", "127.0.0.1 only");
    row(&mut firewall, "Peers", "every non-loopback peer is refused with 403");
    row(&mut firewall, "Methods", "GET and HEAD; anything else is 405");

    // ── 🔌 API ─────────────────────────────────────────────────────────────
    // Derived from the route table, like the API tab: no second list.
    let mut api = Rows::new();
    for r in routes::ROUTES.iter().filter(|r| r.kind == routes::Kind::Api) {
        row(&mut api, r.path, r.summary);
    }

    // ── 🌐 WEBSERVER ──────────────────────────────────────────────────────
    let mut web = Rows::new();
    let pages = routes::ROUTES.iter().filter(|r| r.kind == routes::Kind::Page).count();
    row(&mut web, "Listening", format!("http://127.0.0.1:{}/", ctx.port));
    row(&mut web, "Served root", ctx.root.to_string_lossy().to_string());
    row(&mut web, "Root readable", match fs::read_dir(&ctx.root) {
        Ok(_) => "yes".to_string(),
        Err(e) => format!("no — {} (on Android: grant the storage permission)", e),
    });
    row(&mut web, "Routes", format!("{} ({} pages, {} api)", routes::ROUTES.len(), pages, routes::ROUTES.len() - pages));
    row(&mut web, "Read limit", fmt_bytes(routes::READ_MAX_BYTES));
    row(&mut web, "Search bounds", format!("{} entries, {} ms, {} results", routes::SEARCH_BUDGET_ENTRIES, routes::SEARCH_DEADLINE_MS, routes::SEARCH_RESULT_CAP));

    let s = |t: &str, r: Rows| (t.to_string(), r);
    let out = vec![
        (MACROS[0], vec![s("App", app), s("Stack", stack)]),
        (MACROS[1], vec![s("Device / stack", device), s("Kernel / OS", kernel), s("SoC / CPU", cpu), s("Locale & time", locale)]),
        (MACROS[2], vec![s("Security posture", security)]),
        (MACROS[3], vec![s("Storage", storage), s("Battery & Usage", battery), s("Memory", memory), s("SYSFS-PROC", sysfs)]),
        (MACROS[4], vec![s("Network", network), s("Firewall", firewall)]),
        (MACROS[5], vec![s("HTTP API", api)]),
        (MACROS[6], vec![s("Web server", web)]),
    ];
    debug_assert_eq!(out.len(), MACROS.len());
    out
}

/// YYYY-MM-DD HH:MM:SS from epoch seconds (civil-from-days, Howard Hinnant).
fn utc_string(secs: u64) -> String {
    let days = (secs / 86400) as i64;
    let rem = secs % 86400;
    let z = days + 719468;
    let era = (if z >= 0 { z } else { z - 146096 }) / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", y, m, d, rem / 3600, (rem % 3600) / 60, rem % 60)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bytes_and_dates() {
        assert_eq!(fmt_bytes(512), "512 B");
        assert_eq!(fmt_bytes(1536), "1.5 KiB");
        assert_eq!(utc_string(0), "1970-01-01 00:00:00");
        assert_eq!(utc_string(1_700_000_000), "2023-11-14 22:13:20");
    }

    #[test]
    fn every_group_is_a_declared_macro_and_every_section_has_rows() {
        let ctx = Ctx {
            root: std::env::temp_dir(),
            port: 1,
            started: std::time::Instant::now(),
            binary: "t".to_string(),
        };
        let g = groups(&ctx);
        let labels: Vec<&str> = g.iter().map(|(m, _)| *m).collect();
        assert_eq!(labels, MACROS.to_vec(), "groups come out in MACROS order, one per label");
        for (m, sections) in &g {
            assert!(!sections.is_empty(), "macro {} has no section", m);
            for (t, rows) in sections {
                assert!(!rows.is_empty(), "section {} under {} rendered no rows", t, m);
            }
        }
        // The API group is the route table's API rows, no more and no less.
        let api: Vec<&str> = g[5].1[0].1.iter().map(|(k, _)| k.as_str()).collect();
        let want: Vec<&str> = routes::ROUTES.iter().filter(|r| r.kind == routes::Kind::Api).map(|r| r.path).collect();
        assert_eq!(api, want);
    }
}
