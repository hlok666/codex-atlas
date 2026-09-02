# Codex Atlas Android companion

This module is the native Android companion for the desktop Atlas app. It uses
Jetpack Compose for the companion screen and Glance for the home-screen CRT
widget. Both read the same `Atlas Mobile Bridge` JSON contract:

```json
{
  "updatedAtMs": 1710000000000,
  "sessionId": "thread-id",
  "title": "Refactor auth middleware",
  "folder": "api-gateway",
  "model": "gpt-5.6-sol",
  "state": "working",
  "lastOutput": "Running tool: ...",
  "canActivate": true,
  "canInputContinue": true,
  "balanceRemaining": 10.0,
  "balanceUnit": "USD",
  "balanceProvider": "Current Codex provider",
  "balanceCheckedAtMs": 1710000000000
}
```

Pairing profiles are stored as a device list. Each Windows, macOS or cloud
server Bridge keeps its own device id, route and token; use the Devices control
to switch the active profile. The native ColorOS card is interactive: tapping
the body opens the current conversation and the action controls send
authenticated POST requests:

- `POST /v1/sessions/{id}/activate`
- `POST /v1/sessions/{id}/input` with `{ "text": "继续" }`

The foreground sync service keeps the selected profile live while the app is in
the background. It holds an authenticated `/v1/events` SSE stream and asks for
the sequence-aware `/v1/sync` delta as soon as the desktop publishes a change;
the short long-poll remains as a recovery fallback when a proxy or tunnel does
not support a persistent stream. Every installed card refreshes from the same
cached snapshot.

The desktop app remains the authority for Codex process discovery, PowerShell
focus, `codex resume`, and terminal input. The Android client is deliberately a
thin native companion so it cannot fabricate a session state when the desktop
bridge is offline.

## Connection methods

Atlas exposes a LAN Bridge on port `15730` and generates a `codex-atlas://connect`
pairing link. The desktop settings page shows that link and its QR code. The
Android app accepts the link directly from a browser/deep-link, or the same URL
can be pasted into the pairing field. The client tries LAN first and falls back
to the configured fixed tunnel URL.

For a user-owned JD Cloud (or any Linux) server, use the desktop's **Install and
connect** action. It logs in once over SSH with the supplied Host/IP, port,
username and password, installs `cloudflared`, creates a systemd service for the
Cloudflare Tunnel token, installs a dedicated Atlas SSH key, and opens a reverse
forward from the server to the local Bridge. The password is never written to
the Atlas JSON settings; opting into persistence stores it in the OS credential
store.
