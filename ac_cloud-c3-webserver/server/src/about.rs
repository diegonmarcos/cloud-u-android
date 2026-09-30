//! About this phone, from what a plain process on Android can read: getprop,
//! /proc, /sys and statvfs. The Home tab renders these sections as rows, the
//! same shape as cloud-superapp's Configs ▸ About (section → key/value rows).
//! A source that is absent on the host says so in its one row rather than
//! being skipped, so the page never silently loses a section.

use crate::Ctx;
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

pub fn sections(ctx: &Ctx) -> Vec<(String, Rows)> {
    let mut out: Vec<(String, Rows)> = Vec::new();
    let props = getprops();

    // App
    let mut r = Rows::new();
    row(&mut r, "Name", env!("CARGO_PKG_NAME"));
    row(&mut r, "Version", env!("CARGO_PKG_VERSION"));
    row(&mut r, "Binary", ctx.binary.clone());
    row(&mut r, "Listening", format!("http://127.0.0.1:{}/", ctx.port));
    row(&mut r, "Served root", ctx.root.to_string_lossy().to_string());
    row(&mut r, "PID", std::process::id().to_string());
    row(&mut r, "UID / GID", format!("{} / {}", unsafe { libc::getuid() }, unsafe { libc::getgid() }));
    row(&mut r, "Server uptime", fmt_secs(ctx.started.elapsed().as_secs()));
    row(&mut r, "Arch / OS", format!("{} / {}", std::env::consts::ARCH, std::env::consts::OS));
    if let Some(status) = read("/proc/self/status") {
        row(&mut r, "Threads", dash(meminfo_kb(&status, "Threads").map(|n| n.to_string())));
        row(&mut r, "RSS", dash(meminfo_kb(&status, "VmRSS").map(|kb| fmt_bytes(kb * 1024))));
    }
    row(&mut r, "HOME", dash(std::env::var("HOME").ok()));
    row(&mut r, "TMPDIR", dash(std::env::var("TMPDIR").ok()));
    out.push(("App".to_string(), r));

    // Device (getprop)
    let mut r = Rows::new();
    if props.is_empty() {
        row(&mut r, "getprop", "not available on this host (not Android)");
    } else {
        let p = |k: &str| dash(prop(&props, k).map(|s| s.to_string()));
        row(&mut r, "Manufacturer", p("ro.product.manufacturer"));
        row(&mut r, "Model", p("ro.product.model"));
        row(&mut r, "Brand", p("ro.product.brand"));
        row(&mut r, "Device", p("ro.product.device"));
        row(&mut r, "Hardware", p("ro.hardware"));
        row(&mut r, "Board", p("ro.product.board"));
        row(&mut r, "Android", format!("{} (SDK {})", p("ro.build.version.release"), p("ro.build.version.sdk")));
        row(&mut r, "Security patch", p("ro.build.version.security_patch"));
        row(&mut r, "Codename", p("ro.build.version.codename"));
        row(&mut r, "Incremental", p("ro.build.version.incremental"));
        row(&mut r, "Fingerprint", p("ro.build.fingerprint"));
        row(&mut r, "Bootloader", p("ro.bootloader"));
        row(&mut r, "SoC", format!("{} {}", p("ro.soc.manufacturer"), p("ro.soc.model")));
        row(&mut r, "ABIs", p("ro.product.cpu.abilist"));
        row(&mut r, "Timezone", p("persist.sys.timezone"));
        row(&mut r, "Locale", p("persist.sys.locale"));
    }
    out.push(("Device".to_string(), r));

    // Kernel / OS
    let mut r = Rows::new();
    row(&mut r, "Kernel", dash(read("/proc/sys/kernel/osrelease")));
    row(&mut r, "/proc/version", dash(read("/proc/version")));
    row(&mut r, "Hostname", dash(read("/proc/sys/kernel/hostname")));
    let up = read("/proc/uptime").and_then(|s| s.split_whitespace().next().and_then(|n| n.parse::<f64>().ok()));
    row(&mut r, "System uptime", dash(up.map(|s| fmt_secs(s as u64))));
    row(&mut r, "Load average", dash(read("/proc/loadavg").map(|s| s.split_whitespace().take(3).collect::<Vec<_>>().join(" "))));
    out.push(("Kernel / OS".to_string(), r));

    // SoC / CPU
    let mut r = Rows::new();
    row(&mut r, "CPU cores", std::thread::available_parallelism().map(|n| n.get().to_string()).unwrap_or_else(|_| "—".to_string()));
    if let Some(info) = read("/proc/cpuinfo") {
        let pick = |key: &str| {
            info.lines()
                .find(|l| l.to_lowercase().starts_with(key))
                .and_then(|l| l.split(':').nth(1))
                .map(|v| v.trim().to_string())
        };
        row(&mut r, "CPU model", dash(pick("model name").or_else(|| pick("hardware")).or_else(|| pick("processor"))));
        row(&mut r, "Features", dash(pick("features").or_else(|| pick("flags")).map(|f| {
            let words: Vec<&str> = f.split_whitespace().collect();
            if words.len() > 24 { format!("{} … ({} flags)", words[..24].join(" "), words.len()) } else { f.clone() }
        })));
    }
    let khz = |p: &str| read(p).and_then(|s| s.parse::<u64>().ok());
    row(&mut r, "cpu0 cur freq", dash(khz("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").map(|k| format!("{} MHz", k / 1000))));
    row(&mut r, "cpu0 max freq", dash(khz("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").map(|k| format!("{} MHz", k / 1000))));
    row(&mut r, "Governor", dash(read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")));
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
    row(&mut r, "Max freq (any core)", dash(max_all.map(|k| format!("{} MHz", k / 1000))));
    out.push(("SoC / CPU".to_string(), r));

    // Memory
    let mut r = Rows::new();
    match read("/proc/meminfo") {
        Some(m) => {
            let kb = |k: &str| dash(meminfo_kb(&m, k).map(|v| fmt_bytes(v * 1024)));
            row(&mut r, "Total RAM", kb("MemTotal"));
            row(&mut r, "Available", kb("MemAvailable"));
            row(&mut r, "Free", kb("MemFree"));
            row(&mut r, "Cached", kb("Cached"));
            row(&mut r, "Swap total", kb("SwapTotal"));
            row(&mut r, "Swap free", kb("SwapFree"));
        }
        None => row(&mut r, "/proc/meminfo", "not readable"),
    }
    out.push(("Memory".to_string(), r));

    // Storage
    let mut r = Rows::new();
    disk_rows(&mut r, "Served root", &ctx.root.to_string_lossy());
    if let Ok(home) = std::env::var("HOME") {
        disk_rows(&mut r, "App data", &home);
    }
    disk_rows(&mut r, "/data", "/data");
    out.push(("Storage".to_string(), r));

    // Battery
    let mut r = Rows::new();
    let bat = "/sys/class/power_supply/battery";
    if std::path::Path::new(bat).is_dir() {
        let b = |f: &str| dash(read(&format!("{}/{}", bat, f)));
        row(&mut r, "Level", format!("{}%", b("capacity")));
        row(&mut r, "Status", b("status"));
        row(&mut r, "Health", b("health"));
        row(&mut r, "Technology", b("technology"));
        row(&mut r, "Temperature", dash(read(&format!("{}/temp", bat)).and_then(|t| t.parse::<i64>().ok()).map(|t| format!("{:.1} °C", t as f64 / 10.0))));
        row(&mut r, "Voltage", dash(read(&format!("{}/voltage_now", bat)).and_then(|t| t.parse::<i64>().ok()).map(|v| format!("{:.3} V", v as f64 / 1_000_000.0))));
        row(&mut r, "Current", dash(read(&format!("{}/current_now", bat)).and_then(|t| t.parse::<i64>().ok()).map(|c| format!("{} mA", c / 1000))));
        row(&mut r, "Cycle count", b("cycle_count"));
    } else {
        row(&mut r, "Battery", "no /sys/class/power_supply/battery on this host");
    }
    out.push(("Battery".to_string(), r));

    // Network
    let mut r = Rows::new();
    row(&mut r, "Firewall", "loopback only: 127.0.0.1, every other peer is refused with 403");
    let mut ifaces: Vec<String> = fs::read_dir("/sys/class/net")
        .map(|rd| rd.flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect())
        .unwrap_or_default();
    ifaces.sort();
    if ifaces.is_empty() {
        row(&mut r, "/sys/class/net", "not readable");
    }
    for i in ifaces {
        let base = format!("/sys/class/net/{}", i);
        let state = dash(read(&format!("{}/operstate", base)));
        let rx = read(&format!("{}/statistics/rx_bytes", base)).and_then(|s| s.parse::<u64>().ok()).map(fmt_bytes);
        let tx = read(&format!("{}/statistics/tx_bytes", base)).and_then(|s| s.parse::<u64>().ok()).map(fmt_bytes);
        row(&mut r, &i, format!("{} · rx {} · tx {} · mtu {}", state, dash(rx), dash(tx), dash(read(&format!("{}/mtu", base)))));
    }
    out.push(("Network".to_string(), r));

    // Time
    let mut r = Rows::new();
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    row(&mut r, "Epoch (s)", now.to_string());
    row(&mut r, "UTC", utc_string(now));
    row(&mut r, "TZ env", dash(std::env::var("TZ").ok()));
    out.push(("Time".to_string(), r));

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
    fn every_section_has_rows() {
        let ctx = Ctx {
            root: std::env::temp_dir(),
            port: 1,
            started: std::time::Instant::now(),
            binary: "t".to_string(),
        };
        let s = sections(&ctx);
        let titles: Vec<&str> = s.iter().map(|(t, _)| t.as_str()).collect();
        assert_eq!(titles, vec!["App", "Device", "Kernel / OS", "SoC / CPU", "Memory", "Storage", "Battery", "Network", "Time"]);
        for (t, rows) in &s {
            assert!(!rows.is_empty(), "section {} rendered no rows", t);
        }
    }
}
