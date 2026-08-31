# Mobile Sync Protocol

Codex Atlas uses a durable timeline cursor for the Android companion. The
existing `cursorMs` field remains for older clients, but new clients should use
`syncEpoch`, `after` and `nextSeq`.

## Sync request

```text
GET /v1/sync?since=<legacy-ms>&epoch=<syncEpoch>&after=<last-seq>&wait=20000
```

`wait` is a long-poll timeout. The bridge returns early when a new session
state or timeline event is observed.

## Device identity

Pairing links include `deviceId`, `deviceName` and `deviceKind` (`windows`,
`macos` or `linux`). Android stores these values with the LAN/tunnel route and
token as one device profile, so multiple computers and servers can be active at
the same time. Selecting a profile changes the bridge endpoint and its own
persisted timeline cursor.

## Sync response

```json
{
  "cursorMs": 1710000000000,
  "syncEpoch": "atlas-...",
  "nextSeq": 42,
  "reset": false,
  "gap": false,
  "sessions": [],
  "events": []
}
```

`seq` is monotonic within an epoch and is persisted under `CODEX_HOME`.
Messages include `seqStart`, `seqEnd` and `sourceSeqRanges` so a projected
conversation can retain its source position. `turnId`, `callId`,
`toolStatus`, `toolDetail` and approval fields are optional structured data;
clients must continue to render the plain `text` fallback.

## Recovery

- `reset=true` means the stored epoch no longer describes this timeline. Drop
  the local projection, set the local epoch to the response epoch, and fetch
  `/v1/sessions/<id>/messages` without an `after` parameter.
- `gap=true` means the requested sequence is outside the retained window (or
  the client is ahead of the bridge). Fetch the complete session messages and
  set the cursor to `nextSeq`.
- A normal response should merge messages by `id`; tool updates should merge
  by `callId` when present so a running tool remains one projected row.

The bridge may expose a future live stream for latency, but the HTTP timeline
response remains the correctness source. A reconnect always uses this
protocol to close gaps before reporting the client as synchronized.

## Dictation chunks

Voice input can submit ordered transcript chunks to:

```text
POST /v1/sessions/<id>/dictation
{"seq": 7, "text": "继续检查", "final": true, "clientMessageId": "..."}
```

The bridge acknowledges each contiguous chunk with `ackSeq`. A chunk whose
sequence skips ahead is rejected with the last acknowledged sequence so the
client can retry in order. Only a chunk marked `final` is submitted to Codex;
partial chunks are acknowledged without creating user messages. Repeated
chunks are idempotent and return `deduplicated=true`.
