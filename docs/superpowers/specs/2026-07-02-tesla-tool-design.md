# Tesla Tool for LightOS — Design Spec

**Date:** 2026-07-02
**Status:** Approved by owner (pending spec review)
**Target:** Light Phone III running LightOS, built with light-sdk (`:tool` module)
**Vehicle:** 2023 Tesla Model 3 (signed-command vehicle), single vehicle, NA region

## 1. Purpose

A LightOS tool that lets the owner check their Tesla at a glance and issue a small set of deliberate commands — lock/unlock, charging control, climate control, window venting — directly against Tesla's official Fleet API. No companion server, no third-party aggregator, no background activity. Phone-as-key (BLE) is explicitly out of scope because the light-sdk blocks Bluetooth (no `BLUETOOTH_*` permissions on the allow-list; `getSystemService()` banned); a separate upstream feature request to Light will be filed and is not part of this project.

## 2. Constraints (from light-sdk)

- Dependencies limited to the plugin allow-list: ktor/okhttp, kotlinx-serialization, kotlinx-coroutines, Compose, DataStore, Room, WorkManager, etc. **No TDLib-style native libs, no protobuf runtime, no reflection.**
- No `Context`/`Intent`/`startActivity`/`getSystemService`/`contentResolver`/WebView. UI is Compose via `LightScreen`/`LightViewModel`; navigation via `navigateTo`.
- Metadata in `lighttool.toml`. Permissions requested: `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA` (QR scanning during setup only).
- Tool must be open-sourceable (Light requires community tools be open source to sign/distribute).

## 3. Scope

### In scope (v1)
- **Dashboard:** battery %, estimated range, charging state, plugged-in state, interior temperature, locked/unlocked, last-updated timestamp.
- **Commands:**
  - Lock / unlock
  - Start / stop charging
  - Charge limit % (50–100) and charging amps
  - Climate on/off + target temperature
  - Cabin overheat protection (Off / No-AC / AC)
  - Keep-climate "dog mode" on/off
  - Vent / close windows
- **Setup flow:** one-time QR-based credential handoff from the owner's computer; vehicle selection; virtual-key enrollment verification.
- Purely foreground. No background jobs, no notifications, no polling.

### Out of scope (v1)
- Phone-as-key / BLE (blocked by SDK), audio/video anything, multi-vehicle UI (setup picks exactly one; re-run setup to change), seat/steering-wheel heaters, defrost, scheduled charging/departure, location/map display, background charge-complete notifications (possible v2 via `LightWork`).

## 4. Architecture

Single tool in the `:tool` module of the owner's light-sdk fork. Kotlin, Compose, MVVM (`LightScreen` + `LightViewModel`). Four internal layers, each a package with a narrow interface; UI depends only on `vehicle`.

```
ui  →  vehicle (VehicleRepository)  →  fleet (REST)  →  Tesla Fleet API
                     ↓
               vcp (signing)
                     ↓
               auth (tokens/keys, DataStore)
```

### 4.1 `auth`
- `CredentialStore`: persists the setup payload — OAuth refresh token, client id, region, P-256 private key (PKCS#8, base64), selected vehicle id/VIN — in the tool DataStore.
- `TokenManager`: exchanges refresh token → access token lazily (on first use or 401). Fleet API rotates refresh tokens; the new refresh token is persisted **before** the new access token is used. Exposes `suspend fun bearer(): String`.
- Failure mode: refresh rejected → emits `AuthState.Relink`; UI routes to Setup.

### 4.2 `fleet`
- `FleetClient` (ktor + kotlinx-serialization, JSON): `listVehicles()`, `vehicleData(id)`, `wakeUp(id)`, `signedCommand(id, envelopeBase64)`. Regional base URL from `CredentialStore`. Retries: none beyond one automatic token refresh on 401; all other failures surface typed errors (`Asleep`, `Network`, `RateLimited`, `Http(code)`).
- Knows nothing about protobuf, signing, or UI.

### 4.3 `vcp` — Vehicle Command Protocol port
Pure-Kotlin port of the signing layer from Tesla's open-source Go reference (`teslamotors/vehicle-command`). No third-party deps; crypto via JDK/AndroidOpenSSL providers (P-256 ECDH `KeyAgreement`, `AES/GCM/NoPadding`, SHA-256).

- `Proto`: hand-written encoder/decoder for the minimal protobuf message set: `RoutableMessage`, session-info request/response, `VCSEC` action messages (lock/unlock), `CarServer.Action` messages (charging, climate, overheat, dog mode, charge limit/amps, window vent/close). Varint + length-delimited wire format only — small, fully unit-tested. **Resolved:** only lock/unlock route through VCSEC (`VCSEC.UnsignedMessage{ RKEAction }`); window vent/close routes through Infotainment (`CarServer.VehicleAction.VehicleControlWindowAction`), same as the other commands in this list. See `scripts/tesla/vcp-fixtures/README.md` (checked against the vendored `teslamotors/vehicle-command` v0.4.1 reference).
- `SessionManager`: per-domain (VCSEC, Infotainment) session state — vehicle public key, shared secret via ECDH, epoch, clock offset, anti-replay counter. Handshake on first command; cached in memory; re-handshake once automatically on session faults (`MESSAGE_FAULT` family), then surface error.
- `CommandSigner`: builds the authenticated envelope (metadata per reference implementation) and returns base64 for `FleetClient.signedCommand`.

  > **Correction:** the paragraph above originally described this as an AES-GCM encrypted envelope. That's the BLE transport's scheme. The Fleet API path (which is what this tool uses) signs with **HMAC-SHA256 over the plaintext protobuf payload** (`AuthMethodHMAC`) — the command is never encrypted, only tagged. See `scripts/tesla/vcp-fixtures/README.md` for the resolved details (derived from the vendored `teslamotors/vehicle-command` reference).
- Design rule: `Proto` and `CommandSigner` are pure functions of inputs (no I/O) so they can be verified against committed test vectors.

### 4.4 `vehicle`
- `VehicleRepository` — the only interface the UI sees:
  - `val state: StateFlow<VehicleUiState>` where `VehicleUiState = Loading | Ready(VehicleState, updatedAt, stale: Boolean) | Asleep(cached, updatedAt) | Error(kind, cached?)`
  - `suspend fun refresh()`
  - `suspend fun wake()` — explicit, never implicit on open
  - Command methods: `lock()`, `unlock()`, `startCharging()`, `stopCharging()`, `setChargeLimit(pct)`, `setChargeAmps(a)`, `climateOn()`, `climateOff()`, `setTemp(c)`, `setOverheatProtection(mode)`, `setDogMode(on)`, `ventWindows()`, `closeWindows()`
- Command orchestration: if last-known state is asleep → `wakeUp` + poll the lightweight vehicle-status endpoint (vehicle list entry `state` field, not `vehicle_data`) every 3 s up to 30 s (progress reported) → sign via `vcp` → send → on success, one `vehicle_data` refresh. Polling the cheap endpoint keeps the wake path inside the API budget invariant (§7).
- Caching: last good `VehicleState` serialized to DataStore; loaded synchronously on start so the dashboard renders instantly with a `stale` flag and timestamp.
- `FakeVehicleRepository` implements the same interface for emulator/UI development and tests.

## 5. One-time setup

Owner-side ceremony, documented in the tool README, automated by `scripts/setup/`:

1. **Developer registration (computer, once):** create Tesla developer account + app registration (client id/secret).
2. **Key + domain (computer, once):** `keygen` script generates the P-256 keypair; owner publishes the public key at `https://<domain>/.well-known/appspecific/com.tesla.3p.public-key.pem` (GitHub Pages); `register` script calls Tesla's partner-registration endpoint.
3. **Login (computer, once):** `login` script runs the OAuth authorization-code+PKCE flow via the owner's browser with a localhost redirect, exchanges the code, and renders a **terminal QR code** containing JSON: `{v: 1, refresh_token, client_id, client_secret?, region, private_key, domain?}`. `client_secret` is required only if token refresh for the registered app demands it (Tesla confidential clients); the on-device refresh path otherwise uses `client_id` alone, and the setup validator accepts either shape. `domain` is optional and, when present, is the owner's key-hosting domain from §5.2 — the tool uses it to build the `tesla.com/_ak/<domain>` enrollment URL for the "Verify key" flow; if omitted, "Verify key" can detect non-enrollment but can't offer the enrollment link. Because the payload (refresh token ≈1 KB + PKCS#8 key) approaches practical QR density limits, the script deflate-compresses + base64url-encodes the JSON, and falls back to a numbered multi-part QR sequence (`part i/n` header, scanned in any order) if a single code would exceed reliable scan density.
4. **Phone:** Setup screen scans the QR (`LightQrCodeScanner`), validates payload version/fields, persists via `CredentialStore`, calls `listVehicles()`, owner picks the Model 3.
5. **Virtual key enrollment (once):** owner opens `https://tesla.com/_ak/<domain>` with the official Tesla app on their existing smartphone and approves. Setup screen has a **"Verify key"** action that sends a benign signed command (session-info handshake) and reports enrolled / not-enrolled.

Security posture, stated plainly: the private key and refresh token transit one QR on the owner's own screen and rest in the tool's app-private DataStore on a single-user device. Acceptable for a personal tool; importing the key into hardware keystore is a v2 hardening candidate. The setup QR should be regenerable; scripts never write secrets to disk unencrypted except the local key file the owner explicitly created.

## 6. Screens

All screens use `sdk:ui` components (`LightText`, `LightBarButton`, `LightScrollView`, `LightGrid`, theme tokens); monochrome; no imagery.

1. **HomeScreen** (`@InitialScreen`): dashboard block (battery %, range, charge state, plug state, interior temp, lock state, updated-at + stale/asleep badge) → actions: Lock/Unlock, Climate on/off, Vent/Close windows, Start/Stop charging (shown when plugged in), Refresh, Wake (shown when asleep). Nav buttons → Charge, Climate. First run (no credentials) routes to Setup.
2. **ChargeScreen:** limit stepper (50–100, step 5), amps stepper (5–max reported by vehicle), start/stop.
3. **ClimateScreen:** on/off, target temp stepper (0.5 °C steps, HVAC range from vehicle data), overheat protection selector (Off / No-AC / AC), dog mode toggle with explanatory caption.
4. **SetupScreen:** QR scan → vehicle pick → "Verify key" check with guidance if not enrolled. Reachable later via a "Re-link" affordance when auth fails.

Every command control carries its own in-flight indicator and disables while pending; no global spinner. Errors render inline beneath the control that caused them.

## 7. Data flow and API budget

- Open tool → cached state renders instantly → exactly one `vehicle_data` call → update + persist. No automatic re-polling; refresh is manual.
- Commands: (wake if needed) → sign → `signed_command` → one targeted `vehicle_data` refresh.
- The tool never auto-wakes the vehicle for display purposes.
- Expected steady-state usage (a few opens + a handful of commands daily) stays well inside the Fleet API free monthly credit; this is a stated design invariant — any future feature that adds polling must be justified against it.

## 8. Error handling

| Condition | Detection | UI behavior |
|---|---|---|
| Vehicle asleep | Fleet 408 / `asleep` state | Cached data + "Asleep" badge + Wake button; commands offer wake-then-send with progress |
| Wake timeout | 30 s poll exhausted | "Car didn't wake" + retry |
| No connectivity | ktor IO errors | Cached data + timestamp + "offline" notice |
| Auth expired/revoked | Refresh grant rejected | "Re-link account" → SetupScreen |
| Key not enrolled | VCP handshake whoami/permission fault | Points at enrollment step with the `_ak` URL |
| Session fault | `MESSAGE_FAULT` family in response | One silent re-handshake + retry, then inline error |
| Command rejected by vehicle | Failure reason in signed-command response protobuf (e.g., can't vent windows while driving, can't stop charging in current state) | Inline message translating the vehicle's reason to plain language; no retry |
| Rate limited | HTTP 429 | Inline "Tesla is rate-limiting, try later"; no auto-retry |

No raw HTTP/protocol errors are ever shown; every failure names the next user action.

## 9. Testing

- **`vcp` test vectors (cornerstone):** run Tesla's Go reference offline to capture known-good handshake transcripts and signed envelopes for every command in scope; commit as fixtures; assert byte-equality from the Kotlin port. Protocol correctness is established without a car.
- `Proto` round-trip tests (encode→decode identity) plus decoding of captured real responses.
- `TokenManager`/`FleetClient` tests over a fake HTTP engine (ktor MockEngine if allow-listed transitively; otherwise a hand-rolled fake `FleetClient` at the repository seam).
- ViewModel tests with `kotlinx-coroutines-test` against `FakeVehicleRepository`.
- Manual E2E checklist against the real Model 3, run sparingly (wakes cost credits/battery): setup, each command, asleep-wake path, auth-revocation recovery.
- `./gradlew check` green at every merge (repo convention).

## 10. Milestones

1. **M1 — Skeleton:** tool metadata, screens with `FakeVehicleRepository`, full UX walkable on emulator.
2. **M2 — Fleet plumbing:** auth, token refresh, `vehicle_data` dashboard live against the real account (read-only).
3. **M3 — VCP:** proto codec + session + signer, test-vector suite green.
4. **M4 — Commands end-to-end** on the real vehicle; error-path hardening.
5. **M5 — Setup polish:** scripts, README ceremony walkthrough, "Verify key" flow.

## 11. Risks

- **VCP port defects** — mitigated by byte-exact test vectors from the Go reference (M3 gate).
- **Tesla protocol/pricing drift** — pinned to the published reference; minimal call budget; version field in the setup QR payload allows evolving the handoff format.
- **ktor transitive surface** — if a needed ktor artifact isn't resolvable under the allow-list, fall back to okhttp (also allow-listed); the `fleet` interface hides the choice.
- **Enrollment friction** — requires the official Tesla app once; documented clearly in setup.
