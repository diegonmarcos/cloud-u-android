//! cloud-c3-webserver as a Tauri app: bind the server, then open the one
//! window on it. The window is built here rather than declared in
//! tauri.conf.json because Tauri creates declared windows BEFORE `setup`
//! runs, which would point the webview at a socket that is not listening yet.

use tauri::{WebviewUrl, WebviewWindowBuilder};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let port: u16 = env!("C3_WEBSERVER_PORT").parse()?;
            // A failed bind is logged, not fatal: the window still opens and
            // says "not available", which names the problem on the screen
            // instead of closing the app with no word.
            if let Err(e) = c3_webserver::start(port, env!("C3_WEBSERVER_SERVE_ROOT")) {
                eprintln!("c3-webserver did not start: {}", e);
            }
            let url: tauri::Url = format!("http://127.0.0.1:{}/", port).parse()?;
            let _window = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url)).build()?;
            #[cfg(target_os = "android")]
            request_storage(&_window);
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
