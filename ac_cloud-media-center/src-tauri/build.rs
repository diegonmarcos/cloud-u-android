// Nothing of the fleet's is baked at build time: the pages and their data are
// static files under ../web, which tauri.conf.json::build.frontendDist bundles.
fn main() {
    tauri_build::build()
}
