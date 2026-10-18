//! A host build of the app's server for the testers (screenshot at A37 density): the real `start`, the real
//! ports from build.json, a stub of the two Android hooks (the SuperApp bridge and the cloud-browser opener),
//! and a directory to serve. `cargo run --example demo -- <root>`; prints READY when listening.
use std::io::Write;

fn main() {
    let mut a = std::env::args().skip(1);
    let root = a.next().expect("usage: demo <root>");
    let sv = c3_webserver::serving::get();

    c3_webserver::diag::set_source(Box::new(|method, arg| match method {
        "about" => Ok((
            "application/json".to_string(),
            "{\"app\":{\"applicationId\":\"com.diegonmarcos.superapp\",\"versionName\":\"2.41.0\",\"versionCode\":24100,\"gitSha\":\"ebb271a\",\"buildType\":\"release\"},\
\"device\":{\"manufacturer\":\"samsung\",\"model\":\"SM-A376B\",\"android\":\"16\",\"sdk\":36},\
\"stack\":{\"languages\":{\"Kotlin\":71,\"Rust\":9,\"Java\":4},\"buildAvgSecs\":412,\"buildLastSecs\":398}}"
                .to_string(),
        )),
        "diagnose" => Ok((
            "application/json".to_string(),
            "{\"build\":\"ebb271a\",\"device\":{\"model\":\"SM-A376B\",\"oneUi\":\"8.0\",\"density\":2.8125,\"fontScale\":1.0},\
\"edge\":{\"switch_on\":true,\"overlay_permission\":true,\"service_running\":true,\"strip_width_dp\":18},\
\"configs_tiles\":{\"measured\":true,\"tile_w_dp\":44.5,\"tile_h_dp\":35.0}}"
                .to_string(),
        )),
        "logcat" => Ok((
            "text/plain".to_string(),
            format!("10-18 09:12:01.100  1234  1234 I SuperApp: bridge asked {}\n10-18 09:12:02.200  1234  1301 W FleetIpc: slow caller\n10-18 09:12:03.300  1234  1234 E Trace: sample error line\n", arg),
        )),
        _ => Err("unknown".to_string()),
    }));
    c3_webserver::diag::set_opener(Box::new(|url| {
        println!("OPENED {}", url);
        Ok(())
    }));

    c3_webserver::start(c3_webserver::Config { drive_port: sv.drive_port, diag_port: sv.diag_port, root })
        .expect("start");
    println!("READY {} {}", sv.drive_port, sv.diag_port);
    let _ = std::io::stdout().flush();
    loop {
        std::thread::sleep(std::time::Duration::from_secs(3600));
    }
}
