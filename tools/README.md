# Tools

| Tool | What it is |
|---|---|
| `validate-vectors/` | Checks every test vector against the JSON Schemas: `npm install && npm run validate` |
| `mock-receiver/` | .NET console app that acts like a screen: advertises over mDNS, shows the pairing code, simulates playback, answers pull-back |
| `mock-sender/` | .NET console app that acts like a phone: discovers, pairs, serves a file over HTTP with Range support, hands it off, remote-controls |
| `SwipeTo.Tools.Tests/` | Integration tests that run both mocks against each other on this machine, plus media server and `HttpRangeStream` tests |
| `make-sample-video/` | Windows only: renders a test MP4 (a new colour every 10 s) with Windows media APIs, so no video files live in the repo |

## Running the mocks against each other

Terminal 1 — the screen:

```
dotnet run --project tools/mock-receiver -- --name "Desk"
```

Terminal 2 — the phone, scripted (discover → pair → hello → handoff → pause/seek/play → pull-back):

```
dotnet run --project tools/mock-sender -- --script
```

It asks for the 6-digit code the receiver printed (or pass `--code`). Without
`--script` it hands off and then takes keys: space play/pause, ←/→ seek,
`a`/`e` aim start/end, `b` bring back, `h` hand off again, `q` quit.

In `--script` mode, `--aim-hold <ms>` aims (and ends the aim) before handing off and
`--step-delay <ms>` pauses before each remote-control step, so a real screen can be watched.

Use `--receiver ws://host:port/v1` on the sender to skip mDNS, and `--file
some.mp4` to hand off a real video (needed for the Windows receiver). Run either
with `--help` for every option.

State lives in `%LOCALAPPDATA%\SwipeTo\mock-receiver` and `...\mock-sender`
(receiver id, trusted senders as token hashes, the sender's id and tokens).
