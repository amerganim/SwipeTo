# SwipeTo — phase-by-phase build plan

How to use this file with Claude Code:
1. Put `CLAUDE.md` and `PLAN.md` at the root of a new, **private** repo.
2. Open Claude Code in that repo **on your Windows laptop** (the Windows receiver needs Windows to build and run).
3. For each phase, paste the "Prompt for Claude Code" block. Review, test, commit, then move to the next phase.
4. Android parts: have Android Studio + an Android phone with USB debugging enabled, on the same Wi-Fi as the laptop.

Phase overview:

| Phase | Goal | Platforms |
|---|---|---|
| 0 | Repo + protocol spec | docs |
| 1 | Shared protocol libraries + mock tools | .NET, Kotlin |
| 2 | Windows receiver MVP | Windows |
| 3 | Android sender MVP (button-triggered handoff) | Android |
| 4 | Gesture + pointing (the signature experience) | Android, Windows |
| 5 | Photos, pre-buffering, polish, demo | Android, Windows |
| 5B | Third-party apps: YouTube, Spotify, others | Android, Windows |
| 6 | Tizen TV receiver (emulator) | Tizen |
| 7 | iOS sender | iOS |
| 8 | Android TV + macOS receivers | Android TV, macOS |

Phases 0–5B = the proof of concept you can demo and pitch. Phases 6–8 = going global.

---

## Phase 0 — Repository and protocol spec

**Goal:** A written, versioned protocol that every platform will implement.

**Protocol v1 summary (to be written out fully in `docs/protocol.md`):**

- **Discovery:** Receivers advertise mDNS service `_swipeto._tcp.local.` with TXT records: `id` (stable UUID), `name`, `platform` (`windows|tizen|androidtv|macos`), `ver` (`1`), `caps` (`video,image`), `port`.
- **Transport:** WebSocket at `ws://<host>:<port>/v1`. All messages are JSON: `{ "type": "...", "id": "<uuid>", "ts": <unix ms>, ...payload }`.
- **Pairing (first time only):** `pair_request` → receiver shows a 6-digit code → user types it on the phone → `pair_confirm {code}` → `pair_result {ok, trustToken}`. Phone stores `trustToken` per receiver `id`.
- **Session:** `hello {senderId, senderName, trustToken, protocolVersion}` → `hello_ack {receiverId, accepted, reason?}`.
- **Targeting:** `aim_start {}` (phone raised toward this receiver) → receiver shows edge glow + marker; `aim_end {}` clears it. `aim_start` may include `prefetch` (see Phase 5).
- **Handoff:** `handoff { media: {kind: "video"|"image", url, mime, title, durationMs?}, positionMs, playing }` → `handoff_ack {accepted, reason?}`. `url` points to the phone's local HTTP media server: `http://<phoneIp>:<port>/m/<sessionToken>/<fileId>` and must support HTTP Range.
- **Remote control (phone → receiver):** `control {action: "play"|"pause"|"seek"|"stop", positionMs?}`.
- **State (receiver → phone, every 1 s and on change):** `state {positionMs, playing, ended}`.
- **Pull-back:** `pullback_request {}` (phone) → `pullback {positionMs, playing}` (receiver stops playback and returns position).
- **Keepalive:** `ping` / `pong` every 5 s; drop session after 15 s silence.
- **Errors:** `error {code, message}` with codes: `unsupported_media`, `not_paired`, `bad_message`, `busy`, `version_mismatch`.

**Tasks**
- Create the repo layout from `CLAUDE.md`.
- Write `docs/protocol.md` (full spec, sequence diagrams in Mermaid).
- Write JSON Schemas for every message in `docs/schemas/`.
- Write valid and invalid example messages in `docs/test-vectors/` (`valid/*.json`, `invalid/*.json`).
- Add `.gitignore` for .NET, Android, and macOS/iOS.

**Acceptance criteria**
- Every message type in the spec has a schema and at least one valid and one invalid test vector.
- A script validates all test vectors against the schemas and passes.

**Prompt for Claude Code**
```
Read CLAUDE.md and PLAN.md. Do Phase 0 only.
Create the repository layout, write docs/protocol.md using the Protocol v1 summary in PLAN.md
(expand it into a complete spec with Mermaid sequence diagrams for pairing, handoff, control,
and pull-back), write JSON Schemas for every message type in docs/schemas, and create valid and
invalid test vectors in docs/test-vectors. Add a small script (Node or Python) that validates all
test vectors against the schemas, and run it. Stop when Phase 0 acceptance criteria pass and
summarize what you created.
```

---

## Phase 1 — Shared protocol libraries and mock tools

**Goal:** One protocol implementation per language, plus fake phone and fake screen tools so each side can be built independently.

**Tasks**
- `shared/dotnet/SwipeTo.Protocol`: message models, JSON serialization (camelCase), a `ProtocolSession` state machine (unpaired → paired → session → handoff active), validation, version check.
- `shared/kotlin/protocol`: the same models with kotlinx.serialization and the same state machine.
- Both libraries: unit tests that load every file in `docs/test-vectors` (valid ones must parse and round-trip; invalid ones must be rejected).
- `tools/mock-sender` (.NET console): discovers receivers via mDNS, pairs, sends a handoff for a sample MP4 that it serves itself over HTTP with Range support, sends control commands from the keyboard, prints state messages.
- `tools/mock-receiver` (.NET console): advertises via mDNS, accepts pairing (prints code), accepts handoff, simulates playback position, sends state, answers pull-back.

**Acceptance criteria**
- `dotnet test` and the Kotlin module's tests pass, including all test vectors.
- mock-sender and mock-receiver complete a full scripted flow against each other on one machine: discover → pair → hello → handoff → control (pause/seek/play) → pull-back.

**Prompt for Claude Code**
```
Do Phase 1 from PLAN.md. Implement the .NET protocol library and the Kotlin protocol module with
identical models and session state machines, both tested against docs/test-vectors. Then build
tools/mock-sender and tools/mock-receiver as .NET console apps. Write an automated integration
test that runs both mock tools against each other on localhost and checks the full flow
(discover, pair, hello, handoff, control, pull-back). Run all tests. Summarize results and any
spec ambiguities you had to resolve (update docs/protocol.md if so).
```

---

## Phase 2 — Windows receiver MVP

**Goal:** A real Windows app that a phone (or mock-sender) can hand video to.

**Tasks**
- WinUI 3 app `SwipeTo.Receiver.Windows`, using `SwipeTo.Protocol`.
- Runs in the system tray; starts with Windows (optional setting).
- Advertises via mDNS; runs the WebSocket server; handles first-run Windows Firewall rule for private networks.
- Pairing window showing the 6-digit code; stores trusted senders.
- On `handoff`: opens a full-screen player window (MediaPlayerElement), seeks to `positionMs`, plays/pauses as requested, sends `handoff_ack` then periodic `state`.
- Handles `control`, `pullback_request`, `aim_start`/`aim_end` (for now: show/hide a thin glowing border around the screen edge, and a small on-screen marker in a corner — a QR code encoding `swipeto:<receiverId>:<rotatingNonce>`).
- Esc or a close button ends playback and notifies the sender.
- Logs to a file for debugging.

**Acceptance criteria**
- Using `tools/mock-sender` from the same laptop: pair, hand off the sample video, playback starts within ~1 s at the requested position (±300 ms), pause/seek/play work, pull-back returns the correct position.
- `aim_start` shows the glow and marker; `aim_end` hides them.
- App survives sender disconnect and accepts a new session.

**Prompt for Claude Code**
```
Do Phase 2 from PLAN.md: build the Windows receiver as a WinUI 3 tray app using the shared
SwipeTo.Protocol library. Implement mDNS advertising, the WebSocket server, pairing UI,
full-screen playback with MediaPlayerElement, control/state/pull-back handling, and the
aim_start/aim_end edge glow plus QR marker. Verify it end-to-end with tools/mock-sender and add
an automated test where possible. Give me a short manual test checklist for the parts you cannot
verify yourself (firewall prompt, full-screen behaviour, multi-monitor).
```

---

## Phase 3 — Android sender MVP

**Goal:** The phone app plays a local video and hands it to the laptop with a button (gesture comes in Phase 4).

**Tasks**
- Kotlin + Jetpack Compose app using `shared/kotlin/protocol`.
- Pick a local video from the gallery (Photo Picker); play it with Media3 ExoPlayer.
- Discover receivers with NsdManager; list them; pair with the 6-digit code; store trust tokens.
- Local media server (NanoHTTPD) with HTTP Range support, per-session random token in the URL, serves only handed-off files, stops when the session ends.
- "Send to screen" button → `handoff` with current position → on `handoff_ack`, pause local playback and switch the UI into remote-control mode (play/pause/seek bar driven by receiver `state`).
- "Bring back" button → `pullback_request` → resume local playback at returned position.
- Handle Wi-Fi changes and receiver disconnects gracefully.
- Test against `tools/mock-receiver` first, then against the Windows receiver.

**Acceptance criteria**
- Phone and laptop on the same Wi-Fi: discover, pair, hand off a local video; the laptop continues within ±500 ms of the phone's position; remote controls work; bring-back resumes on the phone at the right position.
- Seeking on the laptop works (proves Range support).

**Prompt for Claude Code**
```
Do Phase 3 from PLAN.md: build the Android sender app in Kotlin with Jetpack Compose and Media3,
using the shared Kotlin protocol module. Implement discovery (NsdManager), pairing, the local
NanoHTTPD media server with Range support and per-session tokens, button-triggered handoff,
remote-control mode, and bring-back. Add unit tests for the media server (Range requests, token
checks) and the session logic. Test against tools/mock-receiver where possible. Then give me
step-by-step instructions to run it on my phone against the Windows receiver, plus a manual test
checklist.
```

---

## Phase 4 — Gesture and pointing (the signature experience)

**Goal:** Raise the phone toward the screen, the screen glows, swipe up on the video, and it moves.

**Tasks**
- **Raise-to-aim detection:** use accelerometer + gyroscope (game rotation vector) to detect the phone moving from "held for viewing" to "raised and pointing forward". Tune thresholds; add a debug screen that shows live sensor values and detection state.
- **Target selection, in order of preference:**
  1. **Camera marker:** while aiming, briefly run CameraX + ML Kit barcode scanning on the rear camera; if a receiver's QR marker is seen, that receiver is the target. (Rotating nonce prevents replay from a photo.)
  2. **Fallback:** if only one paired receiver is online, target it; if several, the most recently used.
  - Design a `TargetResolver` interface so BLE and UWB resolvers can be added later.
- Send `aim_start` to the chosen receiver as soon as aim is detected (screen glows), `aim_end` when aim ends without a swipe.
- **Swipe-up gesture** on the video surface (velocity + distance threshold) while aiming → handoff with a short "fly-up" animation of the video on the phone. **Swipe-down** while in remote mode → bring back.
- Haptic feedback on target lock and on successful handoff.
- Settings: sensitivity, enable/disable camera targeting, show debug overlay.

**Acceptance criteria**
- With the laptop in front of the user: raising the phone makes the laptop glow within ~300 ms; swipe-up hands off reliably (≥ 9/10 attempts); normal swiping while watching does not trigger handoff (≤ 1 false trigger in 20 casual swipes).
- With two receivers (laptop + mock-receiver running elsewhere), the camera marker selects the one being pointed at.

**Prompt for Claude Code**
```
Do Phase 4 from PLAN.md. Add raise-to-aim detection with a sensor debug screen, a TargetResolver
abstraction with a camera-marker resolver (CameraX + ML Kit reading the receiver's rotating QR
marker) and a fallback resolver, aim_start/aim_end messaging, swipe-up handoff with a fly-up
animation, swipe-down bring-back, haptics, and sensitivity settings. Also update the Windows
receiver so the QR marker nonce rotates every 2 seconds and is verified on aim_start. Keep the
detection thresholds in one config file. Give me a tuning guide and a manual test checklist,
since sensors and camera can only be verified on a real phone.
```

---

## Phase 5 — Photos, instant start, polish, demo

**Goal:** Make it feel instant and complete enough to demo and pitch.

**Tasks**
- **Images:** hand off a photo; receiver shows it full screen; swipe left/right on the phone moves through the album on the receiver.
- **Prefetch on aim (instant start):** `aim_start` includes `prefetch {url, positionMs}`; the receiver starts buffering at that position *before* the swipe completes, so playback begins with no visible delay. Measure and log time from swipe to first frame, with and without prefetch.
- Connection resilience: auto-reconnect, clear error messages, "receiver busy" handling.
- Receiver settings: device name, auto-start, trusted devices list (revoke).
- First-run onboarding on both apps.
- Record the demo: screen-recording script + checklist for a clean demo video.

**Acceptance criteria**
- Swipe-to-first-frame median under 500 ms with prefetch (log both numbers).
- Photos and albums work.
- A non-technical person can install both apps and complete a handoff using only the onboarding.

**Prompt for Claude Code**
```
Do Phase 5 from PLAN.md: image and album handoff, prefetch-on-aim with timing measurements
(log swipe-to-first-frame with and without prefetch), reconnection and error handling, receiver
settings with trusted-device management, and first-run onboarding on both apps. Update
docs/protocol.md, schemas, and test vectors for the prefetch field and image navigation.
Finish with a demo script I can follow to record a clean demo video.
```

> **Before showing this to anyone outside:** talk to a patent attorney about filing (e.g., a US provisional) covering the camera-marker targeting and prefetch-on-aim mechanisms. Keep the repo private.

---

## Phase 5B — Third-party apps: YouTube, Spotify and others

**Goal:** Point-and-swipe works while the user is in YouTube, Spotify, or another media app, not only in SwipeTo's own player.

**Core principle — hand off the *content reference*, never the stream.** SwipeTo never downloads, re-streams, or extracts media from another service. The phone sends *which content* and *what position*; the receiver opens that service's **own official app, website, or embed player** at that position. This respects the services' terms, DRM, ads, and subscriptions.

**How it works on Android**
1. **Know what's playing:** with the user's permission (Notification access), SwipeTo reads the system media sessions via `MediaSessionManager.getActiveSessions()` with a `NotificationListenerService`. This gives the playing app's package name, title/artist, playback state, position, and transport controls — but generally *not* a reliable content ID.
2. **Get the content ID**, per service, in order of preference:
   - From the media session's metadata/`mediaId` if the app exposes it (e.g. Spotify track URIs — verify on device).
   - From a **share link**: the user taps Share → SwipeTo in the app once; SwipeTo parses the URL (`youtu.be/<id>`, `open.spotify.com/track/<id>`) and combines it with the session's live position.
   - Never via Accessibility-service screen scraping (fragile and against Play Store policy for this use).
3. **Gesture while another app is open:** a foreground service keeps raise-to-aim detection running while a supported app is playing. On aim, the target screen glows and a small floating "swipe up" bubble appears over the app (`SYSTEM_ALERT_WINDOW`, with user consent). Swipe up on the bubble = handoff.
4. **After handoff:** SwipeTo pauses the phone app through its media session's transport controls, and the phone becomes a remote for the receiver.

**Protocol addition:** `media.kind = "app"` with
```json
{ "kind": "app", "service": "youtube", "contentId": "dQw4w9WgXcQ",
  "contentUrl": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "title": "...", "positionMs": 462000, "playing": true }
```
`handoff_ack` may return `reason: "service_unavailable"` (e.g. the receiver has no way to play that service). Receivers advertise supported services in the mDNS TXT `svcs` record (e.g. `svcs=youtube,spotify,web`).

**Service adapters (one per service, behind a common `ServiceAdapter` interface on each side)**

| Service | Phone gets ID from | Receiver opens it with | Position | Notes |
|---|---|---|---|---|
| YouTube | Share link (`youtu.be/<id>`), position from media session | Windows: official YouTube IFrame embed player in a full-screen window, or `youtube.com/watch?v=<id>&t=<sec>s` in the browser. TV: YouTube TV app via deep link / DIAL with `v` and `t` | Yes, to the second | Some videos disable embedding → fall back to browser. Ads and Premium handled by YouTube itself. |
| Spotify | Media session `mediaId` or share link | Preferred: Spotify Web API **Transfer Playback** to the receiver's Spotify Connect device (Spotify app on Windows/TV must be running and logged in) | Yes (Spotify carries position) | Requires user Spotify login (OAuth) and **Spotify Premium**. Spotify's developer access is restricted: development-mode apps need a Premium owner and have small user caps; extended (public) quota is limited to organizations. Fine for the PoC; a public release needs a Spotify partnership. Fallback: open `spotify:track:<id>` on the receiver (starts from the beginning). |
| Generic web video (any site with a URL) | Share link | Open URL in browser full screen | Best effort (`#t=` / `?t=` where supported) | |
| Netflix, Prime Video, Disney+, etc. | Share link (title-level only) | Open the service's own app/site on the receiver to that title | Usually **no** (no public API for position) | DRM content can only play in the service's own app. Treat as "open this title there". Full resume needs partnership. |
| Other music apps | Media session metadata | Search/open in the same service's app or site if a deep link exists | Varies | Adapter-by-adapter. |

**iOS note (Phase 7):** iOS does not let apps read other apps' playback sessions. iPhone users can still use **Share → Send to screen** from YouTube/Spotify (content ID from the link; position not available from YouTube, but available from Spotify through the Web API).

**Tasks**
- Notification-access onboarding screen explaining exactly what is read and why.
- `ActiveMediaWatcher` (Android) using `MediaSessionManager` + `NotificationListenerService`; debug screen showing all fields each app exposes (package, metadata keys, `mediaId`, position) so we can see on the real phone what YouTube/Spotify provide.
- Share-target activity that accepts links and maps them to services.
- Floating swipe bubble over other apps + foreground aim detection, with battery-friendly behaviour (only active while a supported app is playing).
- Adapters: `YouTubeAdapter`, `SpotifyAdapter` (OAuth PKCE + Transfer Playback + device matching by name), `WebUrlAdapter`, `TitleOnlyAdapter` (Netflix-style).
- Windows receiver: YouTube full-screen embed window (official IFrame Player API, with browser fallback), Spotify Connect device name reporting, browser launch for web/title-only links. Advertise `svcs`.
- Update protocol spec, schemas, test vectors, mock tools.

**Acceptance criteria**
- Watching a YouTube video on the phone at 7:42 → raise, swipe up on the bubble → the laptop plays the same video from ~7:42; the YouTube app on the phone pauses; swipe down → YouTube on the phone continues near the laptop's position (seek via share-link re-open if the session can't seek).
- Spotify (Premium test account) playing on the phone → swipe up → the laptop's Spotify app continues the same track at the same position; the phone becomes a remote.
- A Netflix share link opens the right title on the laptop.
- Nothing is downloaded or re-streamed from any third-party service.

**Prompt for Claude Code**
```
Do Phase 5B from PLAN.md. Start with the Android ActiveMediaWatcher and its debug screen, and
stop so I can run it on my phone with YouTube and Spotify playing and report back which fields
each app exposes. Then implement the share-target flow, the floating swipe bubble with foreground
aim detection, the ServiceAdapter interface with YouTube, Spotify (OAuth PKCE + Transfer
Playback), web-URL and title-only adapters, and the matching Windows receiver support (YouTube
official IFrame embed with browser fallback, Spotify Connect device matching, browser launch).
Never download or re-stream third-party media. Update the protocol spec, schemas, test vectors
and mock tools for media.kind = "app" and the svcs TXT record.
```

---

## Phase 6 — Samsung Tizen TV receiver (emulator first)

**Goal:** Same experience on a Samsung TV.

**Tasks**
- Install Tizen Studio + TV Extension; create a TV emulator instance.
- Decide implementation:
  - **Tizen .NET (C#)** reusing `SwipeTo.Protocol`, or
  - **Tizen web app (JS)** with a small JS port of the protocol (faster in the emulator; some TV-specific .NET multimedia APIs are not supported in the emulator).
- Because the emulator sits behind the laptop's virtual network, add an optional **relay mode**: a tiny relay on the laptop that both the phone and the emulator connect to. Real TVs connect directly.
- Implement discovery, pairing, playback (AVPlay or HTML5 video), state, control, pull-back, edge glow + marker overlay.
- Later: test on a real Samsung TV via Developer Mode.

**Prompt for Claude Code**
```
Do Phase 6 from PLAN.md. First, compare Tizen .NET vs Tizen web for this receiver given the TV
emulator's API limitations, and recommend one. Then implement the Tizen TV receiver with the
same behaviour as the Windows receiver, plus an optional relay mode (small .NET relay in /tools)
for the emulator's network setup. Give me step-by-step instructions to run it in the Tizen TV
emulator and, later, on a real TV in Developer Mode.
```

---

## Phase 7 — iOS sender

**Goal:** iPhone users can hand off too, within iOS limits.

**Tasks**
- Swift/SwiftUI app; Swift protocol package passing the shared test vectors.
- In-app player for videos/photos picked from the library; Bonjour discovery; local HTTP media server with Range support.
- Raise-to-aim with Core Motion; camera-marker targeting with Vision/AVFoundation.
- **Share extension** "Send to screen" so users can hand off from Photos and other apps that share media.
- Optional: Nearby Interaction (UWB) resolver for receivers that support it.

**Prompt for Claude Code**
```
Do Phase 7 from PLAN.md: build the iOS sender in Swift/SwiftUI with a Swift protocol package
that passes docs/test-vectors, in-app playback, Bonjour discovery, a Range-capable local media
server, Core Motion raise-to-aim, Vision-based marker targeting, and a "Send to screen" share
extension. Note clearly any iOS restrictions that limit the experience compared to Android.
```

---

## Phase 8 — More receivers

- **Android TV / Google TV:** Kotlin receiver reusing `shared/kotlin/protocol`.
- **macOS:** Swift receiver reusing the Swift protocol package.
- Optional: **BLE resolver** (receivers advertise a BLE beacon; phone uses signal strength + compass heading) and a **UWB resolver** where hardware allows.

**Prompt for Claude Code**
```
Do Phase 8 from PLAN.md: implement the Android TV receiver (Kotlin, reusing the shared protocol
module) and the macOS receiver (Swift), both matching the Windows receiver's behaviour. Then add
a BLE-based TargetResolver on Android with receivers advertising BLE beacons.
```

---

## Definition of done for the proof of concept (end of Phase 5)
- Android phone → Windows laptop: raise, glow, swipe up, continues at the same second; swipe down returns it.
- Works for videos and photos, with pairing and basic security.
- Measured, logged latency numbers.
- A recorded demo video and a patent attorney consultation before any public showing.
