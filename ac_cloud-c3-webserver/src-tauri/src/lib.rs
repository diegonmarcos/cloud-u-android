//! cloud-c3-webserver as a Tauri app: bind the server, then open the one
//! window on it. The window is built here rather than declared in
//! tauri.conf.json because Tauri creates declared windows BEFORE `setup`
//! runs, which would point the webview at a socket that is not listening yet.

#[cfg(target_os = "android")]
mod bridge;

use tauri::{WebviewUrl, WebviewWindowBuilder};

/// Bind the servers (the drive, the diagnostics page and the private DNS). Idempotent: the activity and the
/// keep-alive service both call it, whichever comes first binds. A failed bind is logged, not fatal.
fn start_servers() -> Result<u16, Box<dyn std::error::Error>> {
    let port: u16 = env!("C3_WEBSERVER_PORT").parse()?;
    let cfg = c3_webserver::Config {
        drive_port: port,
        diag_port: env!("C3_WEBSERVER_DIAG_PORT").parse()?,
        root: env!("C3_WEBSERVER_SERVE_ROOT").to_string(),
    };
    if let Err(e) = c3_webserver::start(cfg) {
        eprintln!("c3-webserver did not start: {}", e);
    }
    Ok(port)
}

/// The keep-alive foreground service (KeepAliveService.kt, started at boot, after an update, by the browser and by
/// the activity) calls this once it is in the foreground: the servers then live as long as the service, not the UI.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudwebserver_KeepAliveService_nativeStart(
    _env: jni::JNIEnv,
    _service: jni::objects::JObject,
) -> jni::sys::jboolean {
    start_servers().is_ok() as jni::sys::jboolean
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            // A failed bind is logged, not fatal: the window still opens and
            // says "not available", which names the problem on the screen
            // instead of closing the app with no word.
            let port = start_servers()?;
            // The app opens on its shell (five tabs); the file view at / is one of its pages (the Files tab).
            let url: tauri::Url = format!("http://127.0.0.1:{}/__app__/", port).parse()?;
            let _window = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url)).build()?;
            #[cfg(target_os = "android")]
            {
                request_storage(&_window);
                bridge::install(&_window);
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("cloud-c3-webserver: the Tauri runtime failed to start");
}

/// build.json::runtime.serve_root is shared storage: at targetSdk 28 one
/// READ_EXTERNAL_STORAGE grant covers it. Asked on the activity Tauri hands
/// over, from Rust; a phone that already granted it answers without a dialog.
#[cfg(target_os = "android")]
fn request_storage(window: &tauri::WebviewWindow) {
    let _ = window.with_webview(|webview| {
        webview.jni_handle().exec(|env, activity, _webview| {
            let asked = (|| -> jni::errors::Result<()> {
                let perm = env.new_string("android.permission.READ_EXTERNAL_STORAGE")?;
                let perms = env.new_object_array(1, "java/lang/String", &perm)?;
                env.call_method(
                    activity,
                    "requestPermissions",
                    "([Ljava/lang/String;I)V",
                    &[jni::objects::JValue::Object(&perms), jni::objects::JValue::Int(1)],
                )?;
                Ok(())
            })();
            if let Err(e) = asked {
                // A pending Java exception would abort the next JNI call Tauri makes.
                let _ = env.exception_clear();
                eprintln!("cloud-c3-webserver: storage permission request failed: {}", e);
            }
        });
    });
}
