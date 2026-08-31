#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    if std::env::args().skip(1).any(|arg| arg == "--hook") {
        if let Err(error) = codex_atlas_lib::record_codex_hook_event() {
            // Hook failures must never block a Codex turn. Keep diagnostics on
            // stderr for manual invocation while returning success to Codex.
            eprintln!("Codex Atlas hook failed: {error}");
        }
        return;
    }
    // Keep legacy hook cleanup usable from an already-built desktop binary.
    if std::env::args().skip(1).any(|arg| arg == "--install-hook") {
        match codex_atlas_lib::remove_codex_hook_now() {
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
