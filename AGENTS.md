# Codex Atlas Delivery Rules

- Every user-visible optimization, bug fix, or behavior change must increment the affected app version before handoff.
- Desktop releases must keep `package.json`, `package-lock.json`, `src-tauri/Cargo.toml`, `src-tauri/Cargo.lock`, and `src-tauri/tauri.conf.json` on the same version.
- After a desktop version change, run `npm run tauri:build` and report the generated NSIS installer path. Do not treat `npm run build` alone as a release build.
- Android changes must increment both `versionName` and `versionCode`, then build a signed APK suitable for in-app update testing.
- Run the relevant tests and `git diff --check` before handing off a new build.
- Publishing GitHub commits, tags, or releases still requires the user to request publication.
