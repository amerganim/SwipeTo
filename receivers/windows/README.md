# SwipeTo receiver for Windows

A tray app that lets phones hand video and photos to this PC. WinUI 3 (Windows App SDK 1.8),
unpackaged and self-contained: it runs from its build folder, no installer or runtime needed.
Windows 10 1809+ / Windows 11, x64 or ARM64.

It is a thin shell around `shared/dotnet/SwipeTo.Net`'s `ReceiverHost` (WebSocket server, mDNS,
pairing, sessions). This project adds what is Windows-specific:

| Piece | File |
|---|---|
| Tray icon and menu, host lifecycle, single instance | `App.xaml.cs`, `Program.cs` |
| Full-screen player (MediaPlayer + MediaPlayerElement, photos) | `Ui/PlayerWindow.cs`, `Services/WinUiPlayback.cs` |
| Pairing code window | `Ui/PairingWindow.cs` |
| Aim glow + QR marker (layered, click-through Win32 window) | `Ui/AimOverlay.cs` |
| First run / settings: name, firewall rule, start with Windows | `Ui/FirstRunWindow.cs`, `Services/FirewallRule.cs`, `Services/ReceiverSettings.cs` |
| Daily log files | `Services/FileLogger.cs` |

## Build and run

```
dotnet build receivers/windows/SwipeTo.Receiver.Windows.sln
```

```
receivers\windows\SwipeTo.Receiver.Windows\bin\x64\Debug\net8.0-windows10.0.19041.0\win-x64\SwipeTo.Receiver.exe
```

The first launch shows a short setup window; after that SwipeTo lives in the notification area
(click the "S" icon for settings, right-click for the menu).

Data lives in `%LOCALAPPDATA%\SwipeTo\Receiver`: `settings.json` (receiver id, name),
`trusted-senders.json` (sender ids and SHA-256 hashes of their tokens), `logs\receiver-yyyyMMdd.log`.

Command-line switches exist for testing only: `--data-dir <dir>`, `--port <n>`, `--name <text>`,
`--pair-code <6 digits>`, `--background` (used by the start-with-Windows entry).

## Try it with the mock sender

Make a test video once (3 minutes, a new colour every 10 s, so seeks are visible):

```
dotnet run --project tools/make-sample-video -- %LOCALAPPDATA%\SwipeTo\sample\swipeto-sample.mp4
```

With the receiver running:

```
dotnet run --project tools/mock-sender -- --script --file %LOCALAPPDATA%\SwipeTo\sample\swipeto-sample.mp4 --duration 180000 --aim-hold 3000 --step-delay 2000
```

Type the code the receiver shows. Drop `--script` to drive it by hand (space, arrows, `a`/`e`, `b`).

## Automated end-to-end tests

`SwipeTo.Receiver.Windows.Tests` launches the built exe with its own data directory and drives it
with the mock sender: pairing over mDNS, handoff of a real MP4, pause/seek/play, pull-back, aim
show/hide, start time and position, sender disconnect, and closing the player on the screen.
They take over the primary display for ~20 s, so they are opt-in:

```
set SWIPETO_E2E=1
dotnet test receivers/windows/SwipeTo.Receiver.Windows.Tests
```

## How playback starts fast

The Windows HTTP media source buffers about two seconds before it plays. The receiver instead opens
the sender's URL as an `HttpRangeStream` (`SwipeTo.Net`), a seekable stream that fetches 512 KiB
blocks with Range requests and reads ahead, and hands that to `MediaSource.CreateFromStream`. It
also warms up the media pipeline at start (plays a one-second clip, muted) and keeps the player
window around between handoffs. Measured on the dev laptop: first frame 0.54–0.69 s after
`handoff`, at the requested position ±90 ms.

## Manual test checklist

These need a person and real hardware:

- [ ] **Firewall.** Fresh machine: first run → "Allow on private networks…" → UAC prompt →
      status turns to "Phones on your home Wi-Fi can reach this PC". Confirm the rule
      `SwipeTo Receiver` (inbound, private profile only) in Windows Defender Firewall.
      Without the rule, Windows may show its own "allow access" prompt on first listen — choose
      *Private networks* only.
- [ ] **From another device.** Run the mock sender (or the phone app) on a second PC on the same
      Wi-Fi: it discovers the receiver, pairs, and plays.
- [ ] **Full screen.** The player covers the whole primary display including the taskbar, stays
      above other windows, the ✕ button and Esc both close it, and the phone is told (`ended`).
      Alt+F4 does the same.
- [ ] **Multi-monitor.** Playback and the aim glow appear on the *primary* display only. Check
      with the primary on the left and on the right, and with different scale factors per display.
- [ ] **Aim glow + marker.** The blue edge glow and the QR card are visible over a full-screen
      app or game, do not take focus or block clicks, and disappear on `aim_end`, on handoff, and
      after 10 s without a new `aim_start`. The QR reads as `swipeto:<receiverId>:<nonce>` with a
      phone camera.
- [ ] **Tray.** Icon present (may be in the overflow ∧); menu shows name and status
      ("ready" / "connected to …"); Settings… opens; renaming re-advertises the new name; Quit exits.
- [ ] **Start with Windows.** Tick it, sign out and in: SwipeTo starts silently in the tray.
- [ ] **Audio.** Real phone videos with sound play audio through the PC's default output.
- [ ] **Sleep/resume and Wi-Fi changes.** After resume or reconnecting Wi-Fi, a phone can find and
      use the receiver again (mDNS re-announces; senders reconnect).

## Known limits (later phases)

- Primary display only; choosing a display is a setting for Phase 5.
- The QR nonce is fresh per `aim_start` but does not rotate yet, and is not verified (Phase 4).
- Trusted-device list with revoke, and onboarding polish, come in Phase 5.
