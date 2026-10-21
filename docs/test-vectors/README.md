# Test vectors

Example messages every protocol library must handle (see `../protocol.md`, section 8).

- `valid/<type>[-<case>].json` — must parse and round-trip.
- `invalid/<type>-<case>.json` — must be rejected. `envelope-*` files break rules shared by every type.

Validate them against the schemas with `tools/validate-vectors` (`npm install && npm run validate`).
