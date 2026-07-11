# Instax Tool — Design

**Date:** 2026-07-10
**Status:** Approved by owner (Amol), pending spec review
**Goal:** A LightOS tool for the Light Phone III that prints photos on a Fujifilm instax Link WIDE printer over Bluetooth LE.

## Scope

Minimal print flow only: browse photos, preview one, confirm, print, watch progress. Printer status (battery, prints remaining) is shown where it informs the decision to print. No cropping UI, no image adjustments, no multi-copy, no print history.

**Deployment target:** the system-app LightOS emulator now ("personal" use); structured so the tool is submittable to Light's official build pipeline unchanged if/when Light's permission policy allows it. The only fork-local change is the permission allow-list patch (below).

## Constraints (verified 2026-07-10)

1. **Bluetooth is not available to community tools.** `LightToolPolicy.ALLOWED_PERMISSIONS` (plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt) contains no `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`. With minSdk 33 these runtime permissions are mandatory for BLE. The new `builder/` pipeline enforces the same policy server-side.
2. **No photo-read access.** `READ_MEDIA_IMAGES` is not in the allow-list, and the SDK exposes no API to read the LightOS Album tool's photos (`LightServiceMethod` has no photo methods; `LightFileShare` is tool→LightOS only; the only Album trace in the SDK is the `SAVE_TO_ALBUM` icon).
3. **The Android emulator cannot reach a real BLE peripheral.** Emulator Bluetooth is virtual (netsim/rootcanal). Bridging to a physical adapter (Bumble + USB HCI dongle) is experimental and poorly supported on macOS.
4. **Film is expensive** (~$1.30/sheet of instax WIDE). The default dev loop must consume zero film.

## Printer protocol (from community reverse engineering)

Sources: javl/InstaxBLE (Python, tested with Link WIDE), paorin/InstaxLink, dgwilson/ESP32-Instax-Bridge (protocol-faithful printer simulator — evidence the protocol is fully mapped).

- **BLE GATT.** Service `70954782-2d83-473d-9e5f-81e1d02d5273`; write characteristic `…4783`; notify characteristic `…4784`. Devices advertise as `INSTAX-…(IOS)` for the BLE interface. (The printer also exposes Bluetooth Classic RFCOMM via its `(ANDROID)` identity; we use BLE — better community coverage.)
- **Packet format:** header `0x41 0x62` (client→printer), 2-byte big-endian length, 2-byte opcode, payload, 1-byte checksum `(255 - (sum & 255)) & 255`. Writes longer than 182 bytes are split at the BLE layer.
- **Print flow:** `PRINT_IMAGE_DOWNLOAD_START` (image byte count) → `PRINT_IMAGE_DOWNLOAD_DATA` × N (each with 4-byte big-endian chunk index) → `PRINT_IMAGE_DOWNLOAD_END` → `PRINT_IMAGE`. Nothing irreversible happens before `PRINT_IMAGE`.
- **Image requirements (WIDE):** 1260×840 JPEG, ≤ 65,535 bytes.
- **Status:** device-info queries return supported image size, battery state/percentage, prints remaining (lower 4 bits of a status byte), charging bit. Chunk size for `DATA` packets is taken from the device-info response at runtime, not hardcoded; fixtures cross-check the expected WIDE value.

## Architecture

```
tool/ (Kotlin, Compose, MVVM — same conventions as the weather example and Tesla tool)
├── protocol/   pure Kotlin, zero Android imports
│   ├── InstaxPacket    encode/decode + checksum
│   ├── Opcodes         opcode + payload definitions
│   └── InstaxSession   state machine over InstaxTransport:
│                       connect → query info → print(jpeg) with progress events
├── transport/
│   ├── InstaxTransport         interface: connect / write / notifications / close
│   ├── TcpBridgeTransport      dev: JSON-lines framing to Mac bridge (10.0.2.2)
│   └── AndroidBleTransport     real: android.bluetooth, scan by service UUID;
│                               compile-only/best-effort THIS milestone (no gate can
│                               exercise it); keep it thin, don't over-invest
├── photos/
│   ├── PhotoRepository             interface (list newest-first, load)
│   └── MediaStorePhotoRepository   reads device images
├── imaging/
│   └── PrintImagePrep   EXIF rotation, portrait→landscape auto-rotate,
│                        center-crop to 1260×840, JPEG quality ladder to ≤ 65,535 B
│                        (thin android.graphics wrapper; everything else JVM-testable)
└── ui/     three screens (sdk-ui components: LightScrollView, LightBottomBar, …)
    ├── Photos    photo list + printer status line (battery, prints left);
    │             empty state when no photos ("No photos yet")
    ├── Preview   cropped preview, "Print — N sheets left" confirm (deliberate friction)
    └── Progress  transferring % → printing → done/error; always reaches a terminal
                  state; Cancel available during transfer (safe — abort before
                  PRINT_IMAGE discards the upload, no film used)
```

The transport seam is the same pattern as the Tesla tool's `CommandExecutor`: all protocol bytes are composed in Kotlin regardless of transport, so the dev loop exercises the code that will eventually run on the phone.

### Fork-only policy patch

One clearly-labeled commit adds `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `READ_MEDIA_IMAGES` to `ALLOWED_PERMISSIONS`. It stays isolated (never mixed into tool commits) so the tool itself remains upstream-clean. `lighttool.toml` declares those three permissions.

These are runtime permissions on API 33+: the tool requests them through the SDK's mechanism (`LightServiceMethod.GetPermission` / `RequestPermissionComponent`, handled by `LightSdkPermissionActivity` on the emulator). If a permission is denied, the affected screen shows a plain explanation and a re-request affordance instead of an empty or broken state (Photos screen for `READ_MEDIA_IMAGES`; connect flow for the Bluetooth pair — the latter only matters on the `AndroidBleTransport` path).

### Mac bridge (`scripts/instax/bridge.py`)

Python + bleak. **Dumb pipe by design** — relays opaque frames between a TCP socket and the printer's GATT characteristics; composes no protocol bytes itself. Commands: `scan` (list `INSTAX-*` devices), `connect <id>`, then raw frame relay. `--fake` mode implements a protocol-faithful simulated printer (info responses, ack sequencing, print delays) and is the default dev loop: full UI flow, zero film, zero hardware. Never shipped in the APK.

## Data flow (print)

Pick photo → `PrintImagePrep` → `InstaxSession.print()` streams chunks and emits progress → notify frames drive the Progress screen → terminal state (Done / actionable error). A failure before `PRINT_IMAGE` aborts cleanly; retry restarts the transfer from scratch (transfers are idempotent).

## Error handling

Consolidated `ErrorCopy`-style catalog (pattern proven in the Tesla tool). Cases: bridge unreachable ("Bridge not running — start scripts/instax/bridge.py"), no printer found, connection lost mid-transfer (safe to retry), printer-reported conditions (no film, cover open, low battery), image that cannot be encoded under the size cap. No spinner may hang: every async path times out into an error state.

One case is special: connection lost **after `PRINT_IMAGE` was sent**. The print may or may not be happening; a blind retry can waste a sheet. The error copy for this state explicitly says the print was already triggered and tells the user to check the printer before retrying — retry from this state requires a second confirmation.

## Testing & gates

- **Unit (JVM):** packet codec against byte fixtures cross-generated from the community Python implementation (the Tesla `vcp-fixtures` technique — it caught real protocol bugs); `InstaxSession` against a scripted fake transport (happy path, mid-transfer disconnect, printer-error notifies); quality-ladder logic with a stubbed encoder.
- **Gate G1 (zero film):** on the system-app emulator against `--fake` bridge: browse adb-pushed photos, preview, print, progress reaches Done.
- **Gate G2 (one sheet):** real print on the physical Link WIDE via the bridge. Verifies checksum, chunking, chunk size, and status parsing against reality. (Tesla lesson: fixtures cannot catch response-shape surprises; the real-device gate is mandatory before calling the protocol done.)

## Assumptions & open questions

1. **Album access on real hardware is unsolved.** The Album tool's storage backend is unknowable from the SDK. `MediaStorePhotoRepository` is correct for the emulator (adb-pushed photos) and doubles as the real one only if the Album is MediaStore-backed. Likely real-device path: a `DashboardPhotoRepository` fetching the user's synced photos from Light's web dashboard over HTTPS (unofficial API, no new permissions) — out of scope now, drop-in later via the `PhotoRepository` seam.
2. **WIDE chunk size** is read from device info at runtime; fixture cross-check plus G2 confirm.
3. **Portrait photos auto-rotate to landscape** (full-bleed center-crop, no pillarboxing).
4. **Upstream asks** worth filing: `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` in the allow-list; an Album read/photo-picker API.

## Out of scope

Bluetooth Classic RFCOMM transport, netsim/Bumble HCI passthrough, cropping/adjustment UI, multi-copy, print history, mini/square Link models (protocol core keeps model-specific constants isolated, but only WIDE is wired up).
