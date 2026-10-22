# SwipeTo Protocol v1

Status: **draft v1** · This document is the contract between every SwipeTo sender
(phone) and receiver (big screen). Any change to it must update, in the same
change, the JSON Schemas in [`schemas/`](schemas/), the test vectors in
[`test-vectors/`](test-vectors/), and every protocol library.

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## 1. Overview

SwipeTo is a **live handoff**, not screen mirroring. The phone tells a nearby
screen *what* to play and *from where*; the screen fetches the media itself and
plays it. The phone then acts as a remote, and can pull the media back.

| Role | Examples | Does |
|---|---|---|
| **Sender** | Android phone, iPhone | discovers receivers, pairs, aims, hands off, remote-controls, pulls back; serves its own media over local HTTP |
| **Receiver** | Windows PC, Tizen TV, Android TV, Mac | advertises itself, shows the pairing code, plays media, reports state |

Everything stays on the local network. Media MUST NOT be routed through any
cloud service.

## 2. Discovery (mDNS / DNS-SD)

Receivers advertise the service type **`_swipeto._tcp.local.`**. The instance
name SHOULD be the human-readable receiver name.

TXT record keys:

| Key | Value | Example |
|---|---|---|
| `id` | Stable receiver UUID (lowercase), generated once per install | `0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21` |
| `name` | Display name, ≤ 64 UTF-8 bytes | `Living room PC` |
| `platform` | `windows` \| `tizen` \| `androidtv` \| `macos` | `windows` |
| `ver` | Highest protocol version spoken, decimal | `1` |
| `caps` | Comma-separated capabilities | `video,image` |
| `svcs` | Optional. Services whose content the receiver can open (`kind: "app"`, section 5.4) | `youtube` |
| `port` | WebSocket port, decimal (same as SRV port) | `48620` |

Senders MUST identify receivers by `id`, never by name or IP address: the IP
may change between sessions. Senders SHOULD ignore receivers whose `ver` does
not include a version they speak.

## 3. Transport

- WebSocket at **`ws://<host>:<port>/v1`**. One WebSocket = one session.
- Every message is a single UTF-8 **text** frame containing one JSON object.
  Binary frames MUST be answered with `error {code: "bad_message"}`.
- A frame larger than **64 KiB** MAY be rejected with `bad_message` and the
  connection closed.
- JSON field names are **camelCase**. Message `type` values are **snake_case**.

### 3.1 Envelope

Every message has these fields:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `type` | string | yes | Message type (section 5) |
| `id` | UUID string | yes | Unique per message; senders SHOULD use UUID v4, lowercase |
| `ts` | integer | yes | Sender's clock, Unix milliseconds. Informational only — never used to compute playback position |
| `replyTo` | UUID string | no | On a response, the `id` of the message it answers |

v1 is **strict**: a message with a field that is not defined for its type is
invalid. Integers (`ts`, `positionMs`, `durationMs`) MUST be JSON integers, not
strings or fractions.

### 3.2 Invalid messages

On a message that does not parse, has an unknown `type`, or fails its schema,
the receiving side MUST reply `error {code: "bad_message"}` (with `replyTo` if
the `id` could be read) and MUST NOT change state. It MAY close the connection
after three consecutive bad messages.

## 4. Session state machine

```mermaid
stateDiagram-v2
    [*] --> Connected: WebSocket open
    Connected --> Pairing: pair_request
    Pairing --> Connected: pair_result ok=false (expired / too many attempts)
    Pairing --> Paired: pair_result ok=true
    Connected --> Session: hello (valid trustToken) / hello_ack accepted
    Paired --> Session: hello / hello_ack accepted
    Session --> HandoffActive: handoff / handoff_ack accepted
    HandoffActive --> Session: pullback_request / pullback
    HandoffActive --> Session: playback closed on receiver (state ended=true)
    Session --> [*]: close or 15 s silence
    HandoffActive --> [*]: close or 15 s silence
```

Messages allowed in each state (anything else → `error`). A response is sent
in the state its request arrived in; the transition happens as the response is
sent and received, so `hello_ack` belongs to *Connected*/*Paired* and moves both
sides to *Session*.

| State | Sender may send | Receiver may send |
|---|---|---|
| Connected | `pair_request`, `hello` | `hello_ack` |
| Pairing | `pair_request`, `pair_confirm` | `pair_result` |
| Paired | `hello` | `hello_ack` |
| Session | `aim_start`, `aim_end`, `prepare`, `handoff` | `aim_ack`, `handoff_ack`, `state` |
| HandoffActive | `aim_start`, `aim_end`, `handoff`, `control`, `pullback_request` | `aim_ack`, `handoff_ack`, `state`, `pullback` |
| *any* | `ping`, `pong`, `error` | `ping`, `pong`, `error` |

`state` is allowed in *Session* so a final `state {ended: true}` that crosses a
`pullback_request` on the wire is not an error.

Transitions:

| Event | From → To |
|---|---|
| `pair_request` | Connected / Pairing / Paired → Pairing |
| `pair_result ok=true` | Pairing → Paired |
| `pair_result reason=code_expired / too_many_attempts` | Pairing → Connected |
| `pair_result reason=wrong_code` | stays in Pairing |
| `hello_ack accepted=true` | Connected / Paired → Session |
| `handoff_ack accepted=true` | Session / HandoffActive → HandoffActive |
| `pullback` | HandoffActive → Session |
| `state ended=true` | HandoffActive → Session |

A sender message that needs a session (e.g. `handoff`) received before a
successful `hello` MUST be answered with `error {code: "not_paired"}`.

## 5. Messages

Schemas for every message live in [`schemas/`](schemas/);
[`schemas/message.schema.json`](schemas/message.schema.json) validates any
message by dispatching on `type`.

### 5.1 Pairing (first time only)

| Message | Direction | Payload |
|---|---|---|
| `pair_request` | S → R | `senderId` (UUID, stable per phone install), `senderName` (1–64 chars) |
| `pair_confirm` | S → R | `code`: exactly 6 ASCII digits, as a string (leading zeros matter) |
| `pair_result` | R → S | `ok`; if `ok` then `trustToken`, else `reason` ∈ `wrong_code`, `code_expired`, `too_many_attempts`, `not_pairing` |

Receiver rules:

- On `pair_request` the receiver generates a uniformly random 6-digit code
  with a CSPRNG and shows it on screen. The code is valid for **60 s** and for
  **3** attempts. A new `pair_request` replaces any code in progress.
- `wrong_code` keeps the code valid (attempts permitting); `code_expired` and
  `too_many_attempts` end pairing and return to *Connected*. `pair_confirm`
  outside *Pairing* is a state error (`error {code: "bad_message"}`);
  `not_pairing` is for a receiver that is in *Pairing* but has dropped its code
  (for example, the user dismissed it on screen).
- A repeated `pair_request` inside the rate-limit window keeps the code already
  on screen instead of issuing a new one.
- `trustToken` is at least **128 bits** from a CSPRNG, encoded base64url without
  padding (22–128 chars). The receiver stores it against `senderId` and
  `senderName`; the sender stores it against the receiver `id`.
- Re-pairing an already-trusted `senderId` replaces its token.

```mermaid
sequenceDiagram
    participant P as Phone (sender)
    participant S as Screen (receiver)
    P->>S: pair_request {senderId, senderName}
    Note over S: show 6-digit code (60 s, 3 tries)
    Note over P: user types the code
    P->>S: pair_confirm {code}
    alt code matches
        S-->>P: pair_result {ok: true, trustToken}
        Note over P: store trustToken for receiver id
    else wrong code
        S-->>P: pair_result {ok: false, reason: "wrong_code"}
    end
```

### 5.2 Session

| Message | Direction | Payload |
|---|---|---|
| `hello` | S → R | `senderId`, `senderName`, `trustToken`, `protocolVersion` (integer ≥ 1) |
| `hello_ack` | R → S | `receiverId`, `accepted`; if not accepted, `reason` (error code) |

The receiver refuses with:

- `version_mismatch` if `protocolVersion` is not one it speaks (v1 receivers: `1`);
- `not_paired` if `trustToken` is unknown, revoked, or belongs to another `senderId`;
- `busy` if another sender currently has a handoff active and the receiver does
  not allow takeover (v1 receivers SHOULD allow only one active sender).

After a refused `hello_ack` the receiver MAY close the connection. Comparison
of trust tokens MUST be constant-time.

### 5.3 Targeting

| Message | Direction | Payload |
|---|---|---|
| `aim_start` | S → R | optional `nonce`: a marker nonce the phone's camera read; optional `prefetch` (below) |
| `aim_ack` | R → S | `locked` (boolean); sent only in reply to an `aim_start` that carries a `nonce` |
| `aim_end` | S → R | none |

`aim_start` without a `nonce` means the phone is raised and *may* be pointing at
this receiver. The receiver shows a thin glowing border around the screen edge
and a **marker**: a QR code encoding `swipeto:<receiverId>:<nonce>`.

**Marker rotation.** While the marker is shown the receiver replaces `nonce`
every **2 s** with fresh random data (at least 96 bits, base64url, 16–64
characters) and redraws the QR. A photo of an old marker is therefore useless a
few seconds later.

**Lock.** When the phone's camera reads a marker it sends `aim_start` again,
now with that `nonce`. The receiver answers `aim_ack {locked: true}` (with
`replyTo`) if the nonce is the current one or the one just before it (so a
read that crosses a rotation still counts), and `locked: false` otherwise. A
locked receiver SHOULD show the glow in its *locked* style. Locking is an
assurance, not a gate: a `handoff` is accepted with or without it (a phone
without a camera, or with camera targeting turned off, chooses its target
another way).

A phone may send `aim_start` to several paired receivers at once (every screen
nearby glows and shows its marker), read one marker, lock that receiver, and
send `aim_end` to the others.

**Prefetch (instant start).** Once a phone has chosen its target it MAY add
`prefetch {media, positionMs}` to `aim_start` (`media` exactly as in `handoff`,
section 5.4): what it is about to hand off and from where. The receiver SHOULD
start opening that media at that position, paused and unseen, so that the
`handoff` which follows a swipe plays with no visible delay. It is only a hint:

- the phone MUST still send a `handoff`; a receiver MUST NOT play prefetched
  media without one;
- a phone repeating `aim_start` with the same `media.url` and a newer
  `positionMs` asks the receiver to keep the prefetch near that position;
- a receiver discards the prefetch on `aim_end`, on a `handoff` of a different
  `url`, on session end, and after the 10 s aim timeout;
- the URL is served by the phone exactly as for a `handoff` (same token rules).

A receiver with nothing to gain (or not enough memory) may ignore `prefetch`.

**Prepare (before any aim).** Over Wi-Fi a receiver can need a few seconds to
buffer enough of a video to start, longer than the moment between raising the
phone and swiping. So while the user watches, a phone MAY send `prepare` to
**one** paired receiver it expects to hand off to (for example the last one
used), in *Session*:

| Message | Direction | Payload |
|---|---|---|
| `prepare` | S → R | `media` (as in `handoff`), `positionMs`: what is playing on the phone and where |

The receiver SHOULD open that media hidden and muted and keep it playing in
step with `positionMs`, without any glow or other visible sign, so that a
later `handoff` of the same `url` only has to show it. Rules:

- the phone repeats `prepare` about every **4 s** while it keeps playing that
  media; the receiver drops what it prepared **10 s** after the last one, on a
  `prepare` or `handoff` of a different `url`, on `aim_end` or the aim timeout
  only if no `prepare` arrived within those 10 s, and on session end;
- it is a hint like `prefetch`: the receiver MUST NOT show or play prepared
  media audibly without a `handoff`;
- a phone SHOULD only prepare on a receiver the user has handed off to before,
  SHOULD let the user turn preparing off, and MUST NOT send `prepare` over a
  metered connection (it costs the receiver roughly the media's bitrate);
- the URL is served exactly as for a `handoff`.

`aim_end` hides glow and marker. A receiver MUST also hide them on `handoff`,
on session end, and after **10 s** without a new `aim_start`.

```mermaid
sequenceDiagram
    participant P as Phone
    participant A as Screen A
    participant B as Screen B
    Note over P: raise detected
    P->>A: aim_start
    P->>B: aim_start
    Note over A,B: glow + QR swipeto:<id>:<nonce>, nonce rotates every 2 s
    Note over P: camera reads A's marker
    P->>A: aim_start {nonce}
    A-->>P: aim_ack {locked: true}
    P->>B: aim_end
    Note over A: locked glow
    Note over P: swipe up
    P->>A: handoff {...}
```

### 5.4 Handoff

| Message | Direction | Payload |
|---|---|---|
| `handoff` | S → R | `media`, `positionMs`, `playing`; optional `transition` |
| `handoff_ack` | R → S | `accepted`; if not accepted, `reason` |

`media`:

| Field | Type | Required | Notes |
|---|---|---|---|
| `kind` | `video` \| `image` | yes | |
| `url` | `http://` or `https://` URL | yes | Max 2048 chars |
| `mime` | `video/*` or `image/*` | yes | e.g. `video/mp4` |
| `title` | string ≤ 200 | yes | May be empty |
| `durationMs` | integer ≥ 0 | no | Known duration, for the remote UI |

`positionMs` is where playback must continue (0 for images). `playing` says
whether to play or stay paused at that position.

**Overlap.** With `playing: true`, a sender SHOULD keep playing locally until
the receiver reports `state {playing: true, buffering: false}` (section 5.5),
so the viewer never sees nor hears a gap. Because the phone kept playing
meanwhile, a receiver that needed time to load SHOULD start at `positionMs`
plus the time since the `handoff` arrived.

The sender's media `url` has the form
`http://<phoneIp>:<port>/m/<sessionToken>/<fileId>`. The sender's media server:

- MUST support HTTP `Range` requests (`206 Partial Content`, `Accept-Ranges: bytes`);
- MUST serve only files explicitly handed off in the current session;
- MUST require `sessionToken` (≥ 128 bits, base64url) and answer `404` for any
  unknown token or file id — without revealing which part was wrong;
- MUST stop serving when the session ends.

**App content.** Media from another app's service (YouTube...) is never
streamed by the phone: SwipeTo hands off *which content and where*, and the
receiver opens it with that service's own official player. `media` is then:

| Field | Type | Required | Notes |
|---|---|---|---|
| `kind` | `app` | yes | |
| `service` | `[a-z0-9-]{1,32}` | yes | e.g. `youtube`; one the receiver lists in `svcs` |
| `title` | string ≤ 200 | yes | As the phone's app shows it |
| `channel` | string ≤ 200 | no | Channel or artist, to find the content by title |
| `contentId` | `[A-Za-z0-9_-]{1,128}` | no | The service's own id (YouTube video id), when the phone knows it |
| `contentUrl` | `https://` URL ≤ 2048 | no | A link to the content, when the phone knows it |
| `durationMs` | integer ≥ 0 | no | |

`url` and `mime` are absent: there is no file. With neither `contentId` nor
`contentUrl` the receiver identifies the content from `title` and `channel`
(for YouTube: its search API, accepting only an exact title match on that
channel) and MUST refuse rather than guess: `handoff_ack {accepted: false,
reason: "service_unavailable"}` when it cannot open the service or find exactly
that content. `positionMs`, `playing`, `control`, `state` and `pullback` work as
for a video. `prepare` and `prefetch` carry files only.

Optional `transition` ∈ `next`, `previous`: the media follows on from what the
receiver is showing, in that direction (a photo album, see below). Receivers
MAY use it to slide the new media in from the matching side; it never changes
what is played.

Receiver rules: it replies `handoff_ack` **before** media starts loading, with
`unsupported_media` if it cannot play the `mime`/`kind`. A new `handoff` during
*HandoffActive* replaces the current media. If the media later fails to load,
the receiver sends `error {code: "unsupported_media"}` and returns to *Session*.

**Same media again.** A `handoff` whose `media.url` is what the receiver is
already playing (for example a phone that lost its connection, reconnected and
handed off again at its best estimate of the position) MUST NOT reload it and
MUST NOT seek: the viewer is watching the receiver, whose position is the true
one (it may have stalled while the phone's network was down, which the phone
cannot know). It only applies `playing`, then reports `state` as usual.

**Albums.** Moving through photos on the screen is a sequence of `handoff`s of
`kind: "image"` during *HandoffActive*, each with `transition` `next` or
`previous`. The phone owns the album; the receiver only ever shows one item.

```mermaid
sequenceDiagram
    participant P as Phone
    participant S as Screen
    participant M as Phone media server
    P->>S: hello {senderId, trustToken, protocolVersion: 1}
    S-->>P: hello_ack {accepted: true}
    P->>S: aim_start
    Note over S: edge glow + QR marker
    P->>S: handoff {media, positionMs: 462000, playing: true}
    S-->>P: handoff_ack {accepted: true}
    Note over P: pause local playback, enter remote mode
    S->>M: GET /m/<token>/<file> Range: bytes=...
    M-->>S: 206 Partial Content
    Note over S: seek to 462000, play
    loop every 1 s and on change
        S-->>P: state {positionMs, playing, ended}
    end
```

### 5.5 Remote control and state

| Message | Direction | Payload |
|---|---|---|
| `control` | S → R | `action` ∈ `play`, `pause`, `seek`, `stop`; `positionMs` required for `seek`, ignored otherwise |
| `state` | R → S | `positionMs`, `playing`, `ended`; optional `buffering` |

- `stop` ends playback on the receiver and closes the player; the receiver
  sends a final `state {ended: true}` and returns to *Session*.
- The receiver sends `state` **every 1 s** while *HandoffActive* and
  immediately after any change (play, pause, seek completed, ended, media
  closed by the user on the receiver).
- When the user closes playback on the receiver (Esc / close button), the
  receiver sends `state {ended: true, playing: false}` and returns to *Session*.
- `control` outside *HandoffActive* gets `error {code: "bad_message"}`.
- `playing` is the receiver's intent; `buffering: true` says it is not yet
  showing frames (still opening, or waiting for data). Absent means `false`.
  The first `state` with `playing: true` and no `buffering` after a `handoff`
  is the moment the viewer sees the video on the screen.
- `handoff_ack` is sent before the media loads, so the first `state` after it is
  the signal that playback has started. A receiver MAY ignore `control` that
  arrives before that first `state`; senders SHOULD wait for it.

```mermaid
sequenceDiagram
    participant P as Phone (remote mode)
    participant S as Screen
    P->>S: control {action: "pause"}
    S-->>P: state {positionMs: 480120, playing: false, ended: false}
    P->>S: control {action: "seek", positionMs: 600000}
    S-->>P: state {positionMs: 600000, playing: false, ended: false}
    P->>S: control {action: "play"}
    S-->>P: state {positionMs: 600010, playing: true, ended: false}
```

### 5.6 Pull-back

| Message | Direction | Payload |
|---|---|---|
| `pullback_request` | S → R | none |
| `pullback` | R → S | `positionMs`, `playing` (state just before stopping) |

The receiver stops and closes playback, replies `pullback` (with `replyTo`), and
returns to *Session*. The sender resumes locally at `positionMs`, playing if
`playing`. `pullback_request` outside *HandoffActive* gets
`error {code: "bad_message"}`.

```mermaid
sequenceDiagram
    participant P as Phone
    participant S as Screen
    P->>S: pullback_request
    Note over S: stop playback, close player
    S-->>P: pullback {positionMs: 470250, playing: true}
    Note over P: resume local playback at 470250
```

### 5.7 Keepalive

`ping` / `pong` carry no payload; `pong` sets `replyTo`. Each side sends `ping`
every **5 s** when it has sent nothing else in that time. Either side drops the
session after **15 s** without receiving any message. A receiver that drops a
session during *HandoffActive* keeps playing locally for the viewer but stops
sending state; a new session from the same sender MAY take it over with a new
`handoff`.

### 5.8 Errors

`error {code, message}`; `message` is human-readable (≤ 500 chars) and MUST
NOT contain secrets. Codes:

| Code | When |
|---|---|
| `unsupported_media` | Receiver cannot play the media kind/type, or it failed to load |
| `not_paired` | Session message before a successful `hello`, or bad trust token |
| `bad_message` | Unparseable, unknown type, schema violation, or wrong state |
| `busy` | Another sender holds the receiver |
| `version_mismatch` | Unsupported `protocolVersion` |

The same codes are used as `reason` in `hello_ack` and `handoff_ack`.

## 6. Security

- **Pair once.** A receiver accepts `hello` only with a trust token it issued.
  Tokens are revocable per sender from the receiver's settings.
- **Codes are short-lived.** 6 digits, 60 s, 3 attempts; a receiver SHOULD
  rate-limit `pair_request` to one per 5 s per connection.
- **Media is scoped.** The phone's media server serves only handed-off files,
  behind an unguessable per-session token, and stops with the session.
- **Local network only.** v1 uses plain `ws://` and `http://` on the LAN; the
  trust token protects session admission, not confidentiality. Transport
  encryption is a future-version item.
- Receivers MUST NOT log trust tokens, pairing codes, or full media URLs.

## 7. Versioning

`protocolVersion` in `hello` and `ver` in the TXT record are integers. A
breaking change bumps the version; adding fields is also a change to v1's
strict schemas and therefore requires updating schemas, vectors and every
library together (CLAUDE.md, ground rule 1).

## 8. Conformance

Every protocol library MUST:

1. parse and round-trip every file in `test-vectors/valid/` (re-serialized JSON
   is semantically equal to the input);
2. reject every file in `test-vectors/invalid/`.

Run the reference check with:

```
cd tools/validate-vectors && npm install && npm run validate
```
