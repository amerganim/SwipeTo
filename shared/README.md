# SwipeTo shared libraries

| Path | Language | What |
|---|---|---|
| `dotnet/SwipeTo.Protocol` | C# / .NET 8 | Message records, strict JSON codec, `ProtocolSession` state machine. No platform or network code. |
| `dotnet/SwipeTo.Protocol.Tests` | xUnit | Test vectors, codec and state-machine tests |
| `dotnet/SwipeTo.Net` | C# / .NET 8 | mDNS advertise/browse, WebSocket `MessageChannel` with keepalive, `ReceiverHost` (pairing, sessions, playback control behind `IPlaybackTarget`/`IReceiverUi`), `SenderClient`, Range-capable `MediaServer` |
| `kotlin/protocol` | Kotlin / JVM 17 | Same models, codec and state machine for Android (kotlinx.serialization JSON) |

Both protocol libraries load every file in [`docs/test-vectors`](../docs/test-vectors):
valid ones must parse and round-trip, invalid ones must be rejected. A shared
test checks both emit byte-identical JSON for the same message.

```
dotnet test
```

```
cd shared/kotlin && ./gradlew test
```

The Kotlin build needs a JDK 17+ (Android Studio's bundled JBR works:
set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr`).
