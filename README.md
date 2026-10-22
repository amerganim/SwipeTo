# SwipeTo

Point your phone at a nearby screen and swipe up: the video you were watching continues there at the same second. Swipe down to bring it back.

SwipeTo is an open, platform-neutral **handoff protocol** plus reference senders (phones) and receivers (screens). It is a live handoff, not screen mirroring.

- Protocol spec: [`docs/protocol.md`](docs/protocol.md)
- Build plan: [`PLAN.md`](PLAN.md) · Project rules: [`CLAUDE.md`](CLAUDE.md)

## Branches

| Branch | Holds |
|---|---|
| `main` | Reviewed, merged work |
| `phase-N/*` | Platform-neutral phases (spec, shared libraries, tools) |
| `windows/*` | Windows receiver work |
| `android/*` | Android sender work |

Each phase lands through its own pull request.

## Status

| Phase | Goal | State |
|---|---|---|
| 0 | Repo + protocol spec | in review |
| 1 | Shared protocol libraries + mock tools | — |
| 2 | Windows receiver MVP | — |
| 3 | Android sender MVP | — |
| 4 | Gesture + pointing | — |
| 5 | Photos, prefetch, polish, demo | — |
| 5B | Third-party apps | — |
