# VCP fixture generator

Generates the byte-exact JSON fixtures in `tool/src/test/resources/vcp/` that the
pure-Kotlin Vehicle Command Protocol (VCP) port — Tasks 20–23 — is tested against.
The fixtures, together with this README, are the **authoritative specification** of
correct wire output: a Kotlin implementer reproduces bytes from the fixture fields
and the rules below, without reading the Go.

## Reference

- Upstream: `github.com/teslamotors/vehicle-command`
- Pinned: **v0.4.1** (`49977a18fd68567501d59e16a6c9e4a8b9348544`, in `pin.txt`)
- `fetch.sh` clones that commit into `upstream/` (gitignored — never committed).
- `go.mod` uses `replace github.com/teslamotors/vehicle-command => ./upstream`, so
  `pin.txt` + `fetch.sh` are the only source of the upstream version; the module
  proxy is never consulted.
- `main.go` deliberately lives under a `github.com/teslamotors/vehicle-command/...`
  module path so Go's `internal/` visibility rule lets it import
  `internal/authentication` (the real handshake/HMAC/GCM logic). Not one line of
  the vendored tree is modified.

## ⚠️ Major correction to the design plan: Fleet API signs with HMAC-SHA256, not AES-GCM

The spec (`2026-07-02-tesla-tool-design.md` §4.3) and plan describe commands as an
"AES-GCM over the action payload" envelope. **That is the BLE transport's scheme.**
This tool talks to the **Fleet API over HTTPS**, whose `signed_command` path uses
`AuthMethodHMAC` (`pkg/connector/inet.Connection.PreferredAuthMethod()` →
`AuthMethodHMAC`). Under HMAC:

- The command payload travels as **plaintext protobuf** (`RoutableMessage.protobuf_message_as_bytes`),
  **not** encrypted.
- Authenticity is a **HMAC-SHA256 tag** in `SignatureData.HMAC_PersonalizedData`.

So Tasks 21–23 implement **HMAC-SHA256 signing, not GCM encryption**. `keys.json`
still carries a standalone AES-GCM known-answer vector (`gcm_vector`) so the Kotlin
AES-GCM primitive can be unit-tested for completeness / future BLE use, but the
command-signing path (Task 23) never encrypts a payload.

## Command → domain routing (resolves the spec's open window question)

Only lock/unlock go to **VCSEC**; **everything else, including window vent/close,
goes to Infotainment (CarServer)**. This resolves the spec's open
"VCSEC-vs-Infotainment for windows" question: **windows use Infotainment /
`CarServer.VehicleAction.VehicleControlWindowAction`.**

| Command | Domain | Application payload (proto message) |
|---|---|---|
| lock / unlock | **VCSEC** | `VCSEC.UnsignedMessage{ RKEAction: RKE_ACTION_LOCK / _UNLOCK }` |
| charge start/stop | Infotainment | `CarServer.Action.VehicleAction.ChargingStartStopAction{ Start{} / Stop{} }` |
| set charge limit | Infotainment | `…ChargingSetLimitAction{ percent }` |
| set charge amps | Infotainment | `…SetChargingAmpsAction{ charging_amps }` |
| climate on/off | Infotainment | `…HvacAutoAction{ power_on }` |
| set temp | Infotainment | `…HvacTemperatureAdjustmentAction{ driver/passenger °C, level=TEMP_MAX }` |
| overheat off / no-A/C / A/C | Infotainment | `…SetCabinOverheatProtectionAction{ on, fan_only }` → off:(f,f) noAc:(t,t) ac:(t,f) |
| dog mode on/off | Infotainment | `…HvacClimateKeeperAction{ climate_keeper_action: Dog / Off }` |
| window vent/close | Infotainment | `…VehicleControlWindowAction{ Vent{} / Close{} }` |

## Session establishment (Task 21)

1. **ECDH**: `S = ECDH(client_private, vehicle_public)`; take the shared point's
   **X coordinate, big-endian, 32 bytes**.
2. **Session key**: `K = SHA1(S.x)[:16]` — SHA-1 of the 32-byte X, truncated to
   16 bytes. (Yes, SHA-1 and 16 bytes; that is what the protocol specifies.)
3. **Per-purpose HMAC subkeys**: `K_purpose = HMAC-SHA256(K, label)` where label is
   - `"authenticated command"` → command-auth key (Task 23 command tags)
   - `"session info"` → session-info-verification key (validating the handshake tag)

`keys.json` gives fixed client/vehicle keys (PKCS#8 + uncompressed `0x04‖X‖Y`
points), the ECDH X, `K`, the labels, and `gcm_vector` (an isolated
key/nonce/aad/plaintext → ciphertext/tag AES-GCM KAT).

Uncompressed EC points are `0x04 ‖ X(32) ‖ Y(32)` (65 bytes).

## HMAC command tag (Task 23 — the crux)

For each command the tag is:

```
tag = HMAC-SHA256( key = K_command , message = metadata ‖ plaintext_payload )
      where K_command = HMAC-SHA256(K, "authenticated command")
```

`metadata` is a tag-length-value byte string over the signing context, **tags in
strictly ascending numeric order**, each entry `[tag:1][len:1][value:len]`,
terminated by `TAG_END (255)`:

| Tag | # | Value |
|---|---|---|
| TAG_SIGNATURE_TYPE | 0 | `[SIGNATURE_TYPE_HMAC_PERSONALIZED = 8]` (1 byte — value 8, NOT 5; 5 is the GCM type) |
| TAG_DOMAIN | 1 | `[domain]` (1 byte: VCSEC=2, Infotainment=3) |
| TAG_PERSONALIZATION | 2 | VIN ASCII (17 bytes) |
| TAG_EPOCH | 3 | epoch (16 bytes, from the session-info response) |
| TAG_EXPIRES_AT | 4 | uint32 **big-endian** |
| TAG_COUNTER | 5 | uint32 **big-endian** |
| TAG_FLAGS | 7 | uint32 big-endian — **included only when flags ≠ 0** |
| TAG_END | 255 | (no length/value; single terminator byte) |

`commands.json` gives, per command, `metadata_b64` (this exact TLV, already
including the trailing `TAG_END`), `plaintext_action_b64` (the application proto
above), `epoch_b64`, `counter`, `expires_at`, `tag_b64` (the expected HMAC output),
and `routable_message_b64` (the full signed `RoutableMessage`). Task 23 must
reproduce `tag_b64` byte-for-byte as `HMAC-SHA256(K_command, metadata_bytes ‖
plaintext_action)`, and `routable_message_b64` as the assembled envelope.

- **flags**: `FLAG_ENCRYPT_RESPONSE` is set (value `1 << FLAG_ENCRYPT_RESPONSE`),
  so `TAG_FLAGS` appears in every fixture's metadata.
- **expires_at**: `session_clock_time + 5s` (`internal/dispatcher` `defaultExpiration`),
  as a session-relative uint32 second count. Fixtures use a fixed clock so this is
  stable.
- **counter**: increments per signed command within a session; fixtures use fixed
  per-entry values (see `counter`).

## RoutableMessage envelope (Task 22)

Built from `universal_message.proto`:
- `to_destination.domain` (the command's domain)
- `from_destination.routing_address` (16-byte client address; fixed in fixtures)
- `payload.protobuf_message_as_bytes` = the **plaintext** application proto
- `uuid` (16 bytes; fixed in fixtures)
- `flags` = `1 << FLAG_ENCRYPT_RESPONSE`
- `signature_data.signer_identity.public_key` = client uncompressed point
- `signature_data.HMAC_personalized_data` = `{ epoch, counter, expires_at, tag }`

Field numbers are transcribed from the vendored `.proto` files under
`upstream/pkg/protocol/protobuf/` — cite them per field when implementing Task 22.

## Session-info handshake (Tasks 22/23)

`session_info_request.json`: a `RoutableMessage` with a `SessionInfoRequest{ public_key }`
to each domain (VCSEC, Infotainment). `session_info_response.json`: a synthetic
vehicle reply built with the reference's own `authentication.Verifier`, plus the
parsed `SessionInfo` fields Kotlin must extract — `epoch_b64`, `clock_time`,
`counter`, `vehicle_public_b64`, `status`.

## Fault classification (Task 23)

`fault_response.json`: a `RoutableMessage` whose `signed_message_status.signed_message_fault`
is `MESSAGEFAULT_ERROR_INVALID_SIGNATURE`, plus the full `MessageFault_E` enum
(name → number) from `universal_message.proto`. The rule:
`isFault(msg) == (signed_message_status.signed_message_fault != MESSAGEFAULT_ERROR_NONE(0))`.

## Fixed test inputs (auditable & regenerable)

All committed in `main.go`; changing any changes every downstream fixture byte:

- client private scalar `3dd722a0…a997747`, vehicle private scalar `ad31dc0d…1d5c2c4d` (P-256, hex)
- VIN `5YJ3E1EA1PF000001` (test-only; the `TAG_PERSONALIZATION` value)
- challenge UUID `0102…0f10`, routing address `2c907bd7…04efde`, command UUID `58406580…b4fe9b99`
- `gcm_vector` nonce `d1e2a3f4b5c6a7b8c9d0e1f2`, its AAD/plaintext are hardcoded
- expiry window 5s; the session clock/counter are fixed by the synthetic handshake

**These are test-only keys — never enroll the corresponding public keys on a real
vehicle.**

## Regenerating

```
cd scripts/tesla/vcp-fixtures
./fetch.sh          # clones upstream@pin.txt into upstream/ (gitignored)
go run main.go      # rewrites tool/src/test/resources/vcp/*.json
```

**The committed JSON fixtures are the authoritative static artifact — treat them as
ground truth; do not regenerate expecting byte-identical output.** Regeneration is
NOT deterministic: the vendored `authentication.NewVerifier` randomizes the session
`epoch` via `crypto/rand` (`internal/authentication/verifier.go`) with no injection
seam, so each regen produces a different epoch and therefore different tags and
`routable_message_b64` for every command. That does not affect correctness — the
committed fixtures are internally consistent (every `tag_b64` verifies against its
own `epoch_b64`/`metadata_b64`/`plaintext_action_b64` per the rule above), and the
Kotlin port reads the committed files, never regenerates. If you add a NEW command
fixture, regenerate once and commit the whole new set together (all epochs rotate).

`main.go` cross-checks its independent stdlib ECDH/session derivation against the
vendored library's own `Session` (it encrypts with the reference and decrypts with
the from-scratch key), panicking if they ever diverge — so a green run is itself
evidence the documented derivation matches the reference.
