# SwipeTo sender for Android

Play a video on the phone, **raise the phone toward a screen** (it glows), **swipe up** on the video,
and the screen continues at the same second. The phone becomes a remote; **swipe down** (or *Bring
back to phone*) resumes it locally where the screen was. The **Send** buttons still work for a
quick handoff without gestures.

Phase 4 (gestures and pointing) is described in [`AIM_TUNING.md`](AIM_TUNING.md).

Kotlin, Jetpack Compose, Media3 ExoPlayer, NsdManager, OkHttp WebSocket, NanoHTTPD. Min SDK 26,
target 36. The protocol models, codec and state machine come from `shared/kotlin/protocol`, built
from source as a Gradle composite build.

| Piece | File |
|---|---|
| Discovery of `_swipeto._tcp` (API 34+ service-info callbacks, queued resolves before that, multicast lock) | `discovery/ReceiverDiscovery.kt` |
| WebSocket session: state machine both ways, `replyTo` matching, ping/pong, 15 s silence timeout | `net/SenderConnection.kt` |
| Local media server: `/m/<token>/<fileId>`, Range (206/416), HEAD, plain 404s, token rotates per session | `media/LocalMediaServer.kt`, `media/RangeRequest.kt` |
| Picked videos served with random access from `content://` | `media/ContentMediaFile.kt` |
| Connect → pair if needed → hello → publish → handoff → remote → bring back; recovery when the screen or Wi-Fi goes away | `handoff/HandoffCoordinator.kt` |
| Phone id and per-receiver trust tokens | `data/TrustStore.kt` |
| Player, discovery, Wi-Fi changes | `MainViewModel.kt` |
| UI: player, screens list, pairing dialog, remote control | `ui/SwipeToScreen.kt` |
| Raise-to-aim thresholds and presets (one file) | `aim/AimConfig.kt` |
| Raise detection from camera elevation; swipe classification | `aim/RaiseToAimDetector.kt`, `aim/SwipeClassifier.kt`, `aim/OrientationSensor.kt` |
| Target choice: camera QR marker (CameraX + ML Kit), else fallback | `aim/TargetResolver.kt`, `aim/CameraMarkerScanner.kt` |
| Aim flow: `aim_start` to all paired screens, lock via `aim_ack`, `aim_end` to the rest | `aim/AimController.kt` |
| Greeted connections kept ready while the player is in front | `aim/StandbyLinks.kt` |
| Swipe gestures, fly-up, aim banner, settings sheet, sensor debug panel, haptics | `ui/AimUi.kt`, `ui/SwipeToScreen.kt` |

## Build

Needs a JDK 17+; Android Studio's bundled JBR works.

```
cd senders/android
```

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
```

```
gradlew :app:assembleDebug
```

Create `local.properties` with `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk` if Android Studio
hasn't already. Open `senders/android` in Android Studio to run it from there instead.

## Tests

```
gradlew :app:testDebugUnitTest
```

JVM tests, no device needed (30 tests):

- `RangeRequestTest`, `LocalMediaServerTest`: Range parsing; real HTTP against the server (200/206/416/HEAD, wrong token, unknown file, revocation after the session ends).
- `SenderConnectionTest`: against `FakeReceiver`, a scripted receiver on OkHttp's MockWebServer that runs the real protocol state machine. Covers pairing with retries and cancel, `hello` refusals, handoff, control, pull-back, ping/pong, the silence timeout, and drops.
- `HandoffCoordinatorTest`: the whole button flow with a fake player. Covers position and play state at handoff, local pause, remote control, bring-back, token reuse and re-pairing, busy and unreachable screens, viewer close on the screen, and continuing on the phone when the screen vanishes.
- `MockReceiverInteropTest` (opt-in): the same flow against the real .NET mock receiver, Kotlin talking to C# over a socket:

  ```
  dotnet run --project tools/mock-receiver -- --port 48650 --pair-code 314159 --no-mdns
  ```

  ```
  set SWIPETO_MOCK_RECEIVER=ws://127.0.0.1:48650/v1
  set SWIPETO_MOCK_CODE=314159
  gradlew :app:testDebugUnitTest
  ```

## Run it on your phone against the Windows receiver

1. Phone and PC on the **same Wi-Fi** (not a guest network that isolates clients).
2. On the PC, build and start the Windows receiver (branch `windows/*`, see `receivers/windows/README.md`).
   On first run, use **Allow on private networks…** and make sure the Wi-Fi is set to *Private* in
   Windows network settings.
3. On the phone, enable **Developer options → USB debugging**, connect USB, accept the prompt.
4. Install and start:

   ```
   gradlew :app:installDebug
   ```

   ```
   adb shell am start -n dev.swipeto.sender/.MainActivity
   ```

5. Tap **Pick a video** and choose any video from the gallery. It plays on the phone.
6. The PC appears under **Screens nearby** (name from the receiver's settings). Tap **Send**.
7. First time only: the PC shows a 6-digit code; type it on the phone and tap **Pair**.
8. The PC goes full screen at the same position and the phone shows the remote. Use play/pause,
   ±10 s and the slider. **Bring back to phone** continues on the phone where the PC was.

No PC handy? `dotnet run --project tools/mock-receiver` on any machine on the Wi-Fi works as a
pretend screen (it prints the code and the positions it would play).

## Manual test checklist

These need a real phone, a real network and a person watching:

- [ ] Discovery: the PC appears within a few seconds; a second receiver (mock receiver on another
      machine) appears too; quitting the receiver removes it after a while.
- [ ] Pairing: wrong code shows "didn't match"; three wrong codes end pairing; the right code pairs
      and the row shows *Paired*. The next send skips pairing.
- [ ] Handoff accuracy: the PC continues within **±500 ms** of the phone's position (film a clock
      video, or compare the PC's position with the phone's at the moment of Send).
- [ ] Seeking on the PC works anywhere in a long phone video (proves Range support over Wi-Fi).
- [ ] Remote: play/pause/±10 s/slider act on the PC within ~200 ms; the slider follows the PC.
- [ ] Bring back: resumes on the phone at the PC's position, playing if it was playing.
- [ ] Close on the PC (Esc/✕): the phone shows "stopped playing" and is back to local, paused.
- [ ] Wi-Fi off on the phone mid-playback: within ~15 s the phone continues locally from about
      where the PC was. Wi-Fi back on: the screens list refreshes.
- [ ] Rotate the phone during local and remote playback: nothing restarts.
- [ ] Large phone videos (4K HEVC, long recordings) open and seek on the PC.

## Known limits (later phases)

- Video only, picked inside the app; photos and albums are Phase 5.
- The app must stay open while the screen plays (no foreground service yet); Phase 5B adds one.
- Trust tokens are kept in app-private storage, not the Android Keystore.
- Cleartext `ws://`/`http://` on the LAN is allowed app-wide (`res/xml/network_security_config.xml`),
  because receiver addresses are only known at run time; protocol v1 has no transport encryption.
