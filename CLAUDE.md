# SwipeTo — project context for Claude Code

## What this project is
SwipeTo lets a user who is watching a video (or viewing photos) on their phone **point the phone at a nearby screen and swipe up** to hand the media off to that screen. The screen continues playback **at the same position**. Swiping down pulls it back to the phone.

It is designed as an **open, platform-neutral handoff protocol**:
- **Senders** (phones): Android (Kotlin) first, iOS (Swift) later.
- **Receivers** (big screens): Windows (C#/.NET) first, then Tizen TV (.NET or web), Android TV, macOS.

This is a **live handoff**, not screen mirroring: the receiver plays the media itself, and the phone becomes a remote.

## Repository layout
```
/docs
  protocol.md          # Protocol v1 spec — the source of truth
  schemas/             # JSON Schema for every message type
  test-vectors/        # Example messages used by tests on every platform
/shared
  dotnet/SwipeTo.Protocol/        # C# protocol library (Windows + Tizen .NET)
  dotnet/SwipeTo.Protocol.Tests/
  kotlin/protocol/                # Kotlin protocol module (Android)
/receivers
  windows/SwipeTo.Receiver.Windows/   # WinUI 3 receiver app
  tizen/                              # later phase
/senders
  android/                            # Kotlin app
  ios/                                # later phase
/tools
  mock-sender/        # .NET CLI that acts like a phone, for testing receivers
  mock-receiver/      # .NET CLI that acts like a screen, for testing senders
```

## Tech stack
- **Shared protocol (.NET):** .NET 8, System.Text.Json, xUnit.
- **Windows receiver:** WinUI 3 (Windows App SDK), MediaPlayerElement, Makaretu.Dns.Multicast (mDNS), built-in WebSocket server (Kestrel minimal API), `BluetoothLEAdvertisementPublisher` (later phase).
- **Android sender:** Kotlin, Jetpack Compose, Media3 ExoPlayer, NsdManager (mDNS), OkHttp WebSocket, NanoHTTPD (local media server with HTTP Range support), CameraX + ML Kit barcode scanning (marker targeting), SensorManager (raise-to-aim), kotlinx.serialization, JUnit.
- Min Android SDK 26. Windows 10 1809+ / Windows 11.

## Ground rules
1. **`docs/protocol.md` is the contract.** Any protocol change: update the spec, the JSON schemas, the test vectors, and *both* protocol libraries in the same change.
2. Message types and field names must be identical across all platforms. Serialize with camelCase JSON.
3. Every protocol library must pass the shared test vectors in `/docs/test-vectors`.
4. Receivers and senders must be testable **without the other side**: use `/tools/mock-sender` and `/tools/mock-receiver`.
5. Local network only. Never route media through any cloud service.
5a. Third-party apps (YouTube, Spotify, Netflix, etc.): hand off only a **content reference + position**, and let the receiver open the service's own official app, website, or embed player. Never download, extract, re-stream, or strip ads from third-party media, and never use Accessibility-service screen scraping.
6. Security: devices must pair once (6-digit code shown on receiver) before handoff is accepted. The phone's media server only serves files that were explicitly handed off, behind an unguessable per-session token.
7. Keep platform-specific code out of the shared protocol libraries.
8. Write tests alongside code. Run the tests before declaring a task done.
9. Things Claude Code cannot verify on its own (physical phone, camera, sensors, real network between devices): write a short manual test checklist in the PR/summary and say so explicitly.

## Open source
The repository is public: SwipeTo is an open handoff protocol. Never commit secrets or personal data: API keys,
trust tokens, pairing codes, signing keys, device serial numbers, people's or devices' names from test logs, Wi-Fi
names or addresses. Test data uses generated values; receivers keep user secrets (like a YouTube API key)
encrypted in their own settings, never in the repository.

## The plan
See `PLAN.md` for phases, tasks, and acceptance criteria. Work one phase at a time. Do not start a phase until the previous phase's acceptance criteria pass.
