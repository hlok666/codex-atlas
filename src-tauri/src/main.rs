#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // Keep hook installation usable from an already-built desktop binary. The
    // command performs the same atomic, backed-up install as the Runtime page
    // without opening a second Tauri window.
    if std::env::args().skip(1).any(|arg| arg == "--install-hook") {
        match codex_atlas_lib::install_codex_hook_now() {
            Ok(status) => {
                println!(
                    "Codex Atlas hook installed: {} (enabled={})",
                    status.hooks_path, status.enabled
                );
            }
            Err(error) => {
                eprintln!("Codex Atlas hook installation failed: {error}");
                std::process::exit(1);
            }
        }
        return;
    }
    codex_atlas_lib::run()
}
