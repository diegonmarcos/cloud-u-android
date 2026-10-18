//! The static binary: `cloud-webserver <port> [root]`, the same command line as the Linux
//! `my-webserver` (root defaults to $HOME). Serves until killed.
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let port: u16 = args.get(1).and_then(|p| p.parse().ok()).unwrap_or(8000);
    let root = args.get(2).cloned().or_else(|| std::env::var("HOME").ok()).unwrap_or_else(|| ".".to_string());
    let cfg = cloud_webserver::Config { port, root, extra_hosts: Vec::new() };
    if let Err(e) = cloud_webserver::start(cfg) {
        eprintln!("cloud-webserver did not start: {}", e);
        std::process::exit(1);
    }
    loop {
        std::thread::park();
    }
}
