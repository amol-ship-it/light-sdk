# Instax Tool Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A LightOS tool that prints photos on a Fujifilm instax Link WIDE printer over BLE, developed against the system-app emulator with a Mac-side Bluetooth bridge.

**Architecture:** Pure-Kotlin Instax protocol core (`InstaxPacket`/`InstaxMessages`/`InstaxSession`) behind an `InstaxTransport` seam with two implementations: `TcpBridgeTransport` (dev — talks JSON-lines to `scripts/instax/bridge.py`, a Python/bleak relay on the host Mac) and `AndroidBleTransport` (compile-only this milestone). Photos come from `MediaStore` behind a `PhotoRepository` seam. Three Compose screens (Photos → Preview → Progress) following the repo's `LightScreen`/`LightViewModel` conventions.

**Tech Stack:** Kotlin/Compose (`:sdk:client`, `:sdk:ui`), kotlinx-serialization, JVM unit tests (`kotlin.test`), Python 3 + bleak + pytest for the bridge, byte fixtures cross-generated from javl/InstaxBLE.

**Spec:** `docs/superpowers/specs/2026-07-10-instax-tool-design.md` — read it first. Its "Constraints" and "Assumptions" sections are binding.

**Read before starting:** `tool/src/main/kotlin/com/thelightphone/sample/HomeScreen.kt` (screen/viewmodel conventions), `examples/weather/` (a complete tool), `plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt` (policy), and the fixture technique this plan reuses: `git show tesla-tool:scripts/tesla/vcp-fixtures/README.md` (that file lives on the `tesla-tool` branch, not this one).

**Build prerequisites:** GitHub Packages credentials already configured in `local.properties`. All Gradle commands run from the repo root `~/workspaces/light-sdk`. Python needs `bleak` and `pytest` (`pip install bleak pytest`).

**Protocol ground truth:** The byte fixtures generated in Task 3 from pinned javl/InstaxBLE are the source of truth. Where this plan's Kotlin constants or parser offsets disagree with generated fixtures, THE FIXTURES WIN — adjust the Kotlin, never the fixtures. (Tesla lesson: cross-implementation fixtures caught real protocol bugs that unit tests missed.)

---

## Chunk 1: Policy patch, tool scaffold, protocol fixtures

### Task 1: Fork-only permission policy patch

The fork must allow the three permissions the tool needs. This is the ONLY commit that touches SDK/plugin code; keep it isolated (spec: "Fork-only policy patch").

**Files:**
- Modify: `plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt` (the `ALLOWED_PERMISSIONS` set, around line 134)

- [ ] **Step 1.1: Add the three permissions**

In `LightToolPolicy.ALLOWED_PERMISSIONS`, append after `"android.permission.NFC",`:

```kotlin
        // FORK-ONLY (instax-tool): not in upstream policy. Needed for the
        // instax printing tool; see docs/superpowers/specs/2026-07-10-instax-tool-design.md
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.READ_MEDIA_IMAGES",
```

Also add implied features to `PERMISSION_IMPLIED_FEATURES` (same file, ~line 156) so manifest lint stays quiet, matching the existing pattern:

```kotlin
        "android.permission.BLUETOOTH_CONNECT" to listOf("android.hardware.bluetooth"),
        "android.permission.BLUETOOTH_SCAN" to listOf("android.hardware.bluetooth_le"),
```

- [ ] **Step 1.2: Run the plugin tests**

Run: `./gradlew :plugin:test`
Expected: PASS (the validation test does not pin the permission list contents; if any test asserts the exact set, update it in this same commit).

- [ ] **Step 1.3: Commit (isolated)**

```bash
git add plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt
git commit -m "FORK-ONLY: allow BLUETOOTH_CONNECT/SCAN + READ_MEDIA_IMAGES for instax tool"
```

### Task 2: Tool module scaffold

Replace the sample tool with the instax tool's skeleton. Keep `tool/build.gradle.kts` unchanged (it's the template all tools share).

**Files:**
- Modify: `tool/lighttool.toml`
- Delete: `tool/src/main/kotlin/com/thelightphone/sample/` (all three files)
- Create: `tool/src/main/kotlin/com/thelightphone/instax/ToolEntryPoint.kt`
- Create: `tool/src/main/kotlin/com/thelightphone/instax/ui/PhotosScreen.kt` (placeholder for now)

- [ ] **Step 2.1: Rewrite `tool/lighttool.toml`**

```toml
[tool]
id = "com.thelightphone.instax"
label = "Print"
versionCode = 1
versionName = "1.0"
permissions = [
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.INTERNET",
]
# change if you run this on an LP3!
# serverPackage = "com.lightos"
serverPackage = "com.thelightphone.sdk.emulator"
```

(`INTERNET` is required for `TcpBridgeTransport`'s socket to the host.)

- [ ] **Step 2.2: Create the entry point and placeholder screen**

`ToolEntryPoint.kt` — copy the sample's structure, package `com.thelightphone.instax`, log tag `"InstaxTool"`, delete the sample comments.

`ui/PhotosScreen.kt` — minimal `@InitialScreen class PhotosScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, PhotosViewModel>(...)` rendering a `LightText("Print")` heading inside the same `LightTheme`/background scaffold as the sample `HomeScreen.Content()`. `PhotosViewModel : LightViewModel<Unit>()` (empty for now) lives in the same `PhotosScreen.kt` file. Delete `com/thelightphone/sample/`.

- [ ] **Step 2.3: Build**

Run: `./gradlew :tool:assembleDebug`
Expected: BUILD SUCCESSFUL (plugin validates lighttool.toml — failure here means Task 1 is wrong).

- [ ] **Step 2.4: Commit**

```bash
git add -A tool/
git commit -m "instax: tool scaffold (lighttool.toml, entry point, placeholder screen)"
```

### Task 3: Protocol fixtures cross-generated from javl/InstaxBLE

**Files:**
- Create: `scripts/instax/gen_fixtures.py`
- Create: `scripts/instax/README.md`
- Create: `tool/src/test/resources/instax-fixtures/fixtures.json` (generated, committed)

- [ ] **Step 3.1: Pin the reference implementation**

```bash
git clone https://github.com/javl/InstaxBLE ~/workspaces/instax-reference
cd ~/workspaces/instax-reference && git rev-parse HEAD   # record this hash in scripts/instax/README.md
```

Read `InstaxBLE.py` + `Types.py` in the clone. Everything below (opcodes, packet layout, parser offsets) MUST be re-verified against this source; the values in this plan came from it but were transcribed by hand.

- [ ] **Step 3.2: Write `scripts/instax/gen_fixtures.py`**

A standalone script (imports the reference's packet-building code from `~/workspaces/instax-reference`, path injectable via `--reference-dir`; the script MUST `git checkout` the pinned hash in the reference dir before importing, so regeneration is reproducible) that emits `fixtures.json` with these cases, each as `{"name": ..., "hex": ..., "meaning": {...}}`.

Every REQUEST fixture's `meaning` MUST include `{"op1": ..., "op2": ..., "payload_hex": "..."}` — Task 4's encode tests rebuild each packet from exactly these fields and byte-compare. Also record the reference's WIDE chunk-size constant as a top-level `"wide_chunk_size"` key (Task 7 uses it as the fallback default; do not leave 900 implicit).

Cases:

1. `support_function_info_image_support` — request packet for InfoType IMAGE_SUPPORT_INFO (0)
2. `support_function_info_battery` — request for BATTERY_INFO (1)
3. `support_function_info_printer_function` — request for PRINTER_FUNCTION_INFO (2)
4. `print_start_1234_bytes` — PRINT_IMAGE_DOWNLOAD_START for image size 1234
5. `print_data_chunk0` / `print_data_chunk1` — DATA packets for a deterministic 1800-byte payload (`bytes(i % 251 for i in range(1800))`, chunk size 900): indices 0 and 1
6. `print_data_last_chunk_padded` — DATA packet for a 100-byte tail chunk, verifying zero-padding to chunk size
7. `print_end` — PRINT_IMAGE_DOWNLOAD_END
8. `print_image` — PRINT_IMAGE
9. `checksum_cases` — 5 arbitrary packets with their expected checksum bytes

RESPONSE-direction fixtures are MANDATORY (Chunk 2's decode and parser tests depend on them): construct printer→client packets with the reference's own packet builder using the response header `0x61 0x42`, mirroring how the reference parses real notifications — battery response (state + percent), printer-function response (prints remaining in the low 4 bits of the status byte + charging bit 7), image-support response (width 1260, height 840, big-endian at the documented offsets), and a plain ack for each print-flow request. Encode in `meaning` the exact byte offsets used, e.g. `{"battery_percent_offset": 9}` — the Kotlin parsers in Task 5 read their offsets from what you verify here.

- [ ] **Step 3.3: Generate and eyeball**

Run: `python3 scripts/instax/gen_fixtures.py --reference-dir ~/workspaces/instax-reference --out tool/src/test/resources/instax-fixtures/fixtures.json`
Expected: file exists, every `hex` value non-empty, request packets start with `4162`, response packets with `6142`, and each packet's last byte satisfies `(255 - (sum(rest) & 0xFF)) & 0xFF`.

- [ ] **Step 3.4: Write `scripts/instax/README.md`**

Document: pinned reference repo + commit hash, how to regenerate, the rule that fixtures are protocol ground truth, and the WIDE image contract (1260×840 JPEG ≤ 65,535 bytes).

- [ ] **Step 3.5: Commit**

```bash
git add scripts/instax/ tool/src/test/resources/instax-fixtures/
git commit -m "instax: protocol fixtures cross-generated from pinned javl/InstaxBLE"
```

---

## Chunk 2: Protocol core (pure Kotlin, zero Android imports)

Everything in `tool/src/main/kotlin/com/thelightphone/instax/protocol/` must compile without any `android.*` import — that is what keeps it JVM-testable and future-portable. Tests live in `tool/src/test/kotlin/com/thelightphone/instax/protocol/`.

### Task 4: `InstaxPacket` — framing + checksum codec

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/protocol/InstaxPacket.kt`
- Test: `tool/src/test/kotlin/com/thelightphone/instax/protocol/InstaxPacketTest.kt`
- Create (test util): `tool/src/test/kotlin/com/thelightphone/instax/protocol/Fixtures.kt`

- [ ] **Step 4.1: Write the failing tests**

`Fixtures.kt`: loads `instax-fixtures/fixtures.json` from test resources into `data class Fixture(name, hex, meaning: Map<String, JsonElement>)` using kotlinx-serialization; helper `fun fixture(name: String): ByteArray`.

`InstaxPacketTest.kt`:

```kotlin
class InstaxPacketTest {
    @Test fun `encodes every request fixture byte-for-byte`() {
        // for each request fixture: rebuild via InstaxPacket.encode(opcode, payload)
        // using the opcode+payload documented in the fixture's meaning; assertContentEquals
    }
    @Test fun `checksum matches fixture checksum_cases`() { /* ... */ }
    @Test fun `decodes response fixtures into opcode + payload`() {
        // InstaxPacket.decode on response-direction fixtures; assert opcode and payload slice
    }
    @Test fun `decode rejects bad checksum`() { /* flip last byte, expect null/failure */ }
    @Test fun `decode rejects wrong direction header`() { /* request header where response expected */ }
}
```

- [ ] **Step 4.2: Run tests, verify they fail**

Run: `./gradlew :tool:testDebugUnitTest --tests "com.thelightphone.instax.protocol.InstaxPacketTest"`
Expected: FAIL — `InstaxPacket` unresolved.

- [ ] **Step 4.3: Implement `InstaxPacket.kt`**

```kotlin
package com.thelightphone.instax.protocol

/** One Instax wire packet. Layout (both directions):
 *  header(2) | totalLength(2, BE) | opcode(2) | payload(n) | checksum(1)
 *  totalLength counts the whole packet including header and checksum.
 *  Client->printer header = 0x41 0x62 ("Ab"); printer->client = 0x61 0x42 ("aB").
 *  checksum = (255 - (sum of all preceding bytes and 255)) and 255  — verify vs fixtures. */
object InstaxPacket {
    const val HEADER_TO_PRINTER_0 = 0x41; const val HEADER_TO_PRINTER_1 = 0x62
    const val HEADER_FROM_PRINTER_0 = 0x61; const val HEADER_FROM_PRINTER_1 = 0x42

    data class Decoded(val opcode: Opcode, val payload: ByteArray)

    fun checksum(bytes: ByteArray, len: Int): Byte {
        var sum = 0
        for (i in 0 until len) sum += bytes[i].toInt() and 0xFF
        return ((255 - (sum and 0xFF)) and 0xFF).toByte()
    }

    fun encode(opcode: Opcode, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = 7 + payload.size
        val out = ByteArray(total)
        out[0] = HEADER_TO_PRINTER_0.toByte(); out[1] = HEADER_TO_PRINTER_1.toByte()
        out[2] = (total ushr 8).toByte(); out[3] = total.toByte()
        out[4] = opcode.op1.toByte(); out[5] = opcode.op2.toByte()
        payload.copyInto(out, 6)
        out[total - 1] = checksum(out, total - 1)
        return out
    }

    /** Returns null if header/length/checksum invalid. Expects printer->client direction. */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 7) return null
        if (bytes[0].toInt() and 0xFF != HEADER_FROM_PRINTER_0 ||
            bytes[1].toInt() and 0xFF != HEADER_FROM_PRINTER_1) return null
        val total = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (total != bytes.size) return null
        if (bytes[total - 1] != checksum(bytes, total - 1)) return null
        val opcode = Opcode.from(bytes[4].toInt() and 0xFF, bytes[5].toInt() and 0xFF) ?: return null
        return Decoded(opcode, bytes.copyOfRange(6, total - 1))
    }
}
```

Plus a minimal `Opcode` enum in its own file `Opcode.kt` from the start (Task 5 extends it in place — don't relocate later): just the opcodes the fixtures use, with the `from(op1, op2)` companion.

Test-dependency setup (do in this step, first task that needs it): `gradle/libs.versions.toml` has NO coroutines-test or serialization-json test aliases today. Add to `[libraries]`:

```toml
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
```

(check the existing kotlinx-coroutines entry for the actual version ref name and reuse it), and in `tool/build.gradle.kts` dependencies add `testImplementation(libs.kotlinx.coroutines.test)` and `testImplementation(libs.kotlinx.serialization.json)` (the serialization alias already exists; today it only reaches the test classpath transitively — make it explicit).

- [ ] **Step 4.4: Run tests until green**

Run: `./gradlew :tool:testDebugUnitTest --tests "com.thelightphone.instax.protocol.InstaxPacketTest"`
Expected: PASS. If a fixture disagrees with the layout above (length semantics, checksum seed), FIX THE KOTLIN to match the fixture and update the comment.

- [ ] **Step 4.5: Commit**

```bash
git add tool/src/main/kotlin/com/thelightphone/instax/protocol/ tool/src/test/
git commit -m "instax: packet codec verified against cross-generated fixtures"
```

### Task 5: `InstaxMessages` — typed requests and response parsers

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/protocol/Opcode.kt`
- Create: `tool/src/main/kotlin/com/thelightphone/instax/protocol/InstaxMessages.kt`
- Test: `tool/src/test/kotlin/com/thelightphone/instax/protocol/InstaxMessagesTest.kt`

- [ ] **Step 5.1: Failing tests** — for each request builder, byte-equality against its fixture; for each parser, parse the response fixture and assert the `meaning` values (battery percent, prints remaining, charging flag, width/height 1260×840).

- [ ] **Step 5.2: Implement**

`Opcode.kt` — enum with only what the tool uses (YAGNI), values verified against the pinned `Types.py`:

```kotlin
enum class Opcode(val op1: Int, val op2: Int) {
    SUPPORT_FUNCTION_INFO(0, 2),
    PRINT_IMAGE_DOWNLOAD_START(16, 0),
    PRINT_IMAGE_DOWNLOAD_DATA(16, 1),
    PRINT_IMAGE_DOWNLOAD_END(16, 2),
    PRINT_IMAGE_DOWNLOAD_CANCEL(16, 3),
    PRINT_IMAGE(16, 128);
    companion object { fun from(op1: Int, op2: Int): Opcode? = entries.find { it.op1 == op1 && it.op2 == op2 } }
}

enum class InfoType(val value: Int) { IMAGE_SUPPORT(0), BATTERY(1), PRINTER_FUNCTION(2) }
```

`InstaxMessages.kt` — request builders returning encoded packets: `infoQuery(type: InfoType)`, `printStart(imageSize: Int)` (4-byte BE size — verify field layout vs fixture), `printData(index: Int, chunk: ByteArray)` (4-byte BE index + chunk), `printEnd()`, `printImage()`, `printCancel()`. Response parsers returning typed results or null: `parseBattery(payload): BatteryInfo(percent, charging?)`, `parsePrinterFunction(payload): PrinterStatus(printsRemaining, charging, errorCode)`, `parseImageSupport(payload): ImageSupport(width, height, chunkSize?)`, and `isAckFor(decoded, requestOpcode): Boolean`. **All offsets come from the fixture `meaning` blocks written in Task 3 — do not trust this plan for offsets.**

`InstaxError` is defined here UNCONDITIONALLY (Tasks 7, 9, and 13 depend on it): `enum class InstaxError { NO_FILM, COVER_OPEN, BUSY, UNKNOWN }`. Map whatever error codes the pinned reference exposes; codes the reference doesn't document map to `UNKNOWN` and are marked G2-provisional in a comment.

- [ ] **Step 5.3: Green + commit**

Run: `./gradlew :tool:testDebugUnitTest --tests "com.thelightphone.instax.protocol.*"` → PASS.

```bash
git add tool/ && git commit -m "instax: typed messages + response parsers (fixture-verified)"
```

### Task 6: `InstaxTransport` seam + scripted fake for tests

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/transport/InstaxTransport.kt`
- Create (test util): `tool/src/test/kotlin/com/thelightphone/instax/protocol/ScriptedTransport.kt`

- [ ] **Step 6.1: Define the seam** (no test yet — it's an interface; the session tests in Task 7 exercise it)

```kotlin
package com.thelightphone.instax.transport

import kotlinx.coroutines.flow.Flow

/** Abstract byte-frame pipe to a printer. Implementations: TcpBridgeTransport (dev),
 *  AndroidBleTransport (real hardware; compile-only this milestone). */
interface InstaxTransport {
    /** Discover printers. Emits found devices until the coroutine is cancelled. */
    fun scan(): Flow<PrinterDevice>
    suspend fun connect(device: PrinterDevice)
    /** Send one whole protocol packet; transport handles any lower-level write splitting. */
    suspend fun send(packet: ByteArray)
    /** Whole notification frames from the printer. Completes/errors on disconnect. */
    val notifications: Flow<ByteArray>
    suspend fun close()
}

data class PrinterDevice(val name: String, val address: String)

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

- [ ] **Step 6.2: `ScriptedTransport` test util** — records `send()` calls; test scripts map "on packet with opcode X → emit these notification frames (or throw)". Support delayed/withheld acks (for timeout tests) and a `dropConnectionAfter(opcode)` mode (for the mid-transfer and post-PRINT_IMAGE disconnect tests).

- [ ] **Step 6.3: Commit**

```bash
git add tool/ && git commit -m "instax: transport seam + scripted test transport"
```

### Task 7: `InstaxSession` — the print state machine

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/protocol/InstaxSession.kt`
- Test: `tool/src/test/kotlin/com/thelightphone/instax/protocol/InstaxSessionTest.kt`

- [ ] **Step 7.1: Failing tests** — using `ScriptedTransport`:

1. `connect queries info and exposes PrinterInfo` (battery, printsRemaining, imageSupport)
2. `print streams start, N data chunks with correct indices, end, print_image` — assert exact packet sequence and per-chunk ack waits; deterministic 1800-byte "jpeg"
3. `print emits progress from 0 to transfer-complete then printing then done`
4. `chunk size comes from device info, not a constant` (script a non-default chunk size; assert chunking obeys it; default to the fixture-verified WIDE value when info omits it)
5. `cancelling the print flow's collecting coroutine before PRINT_IMAGE sends PRINT_IMAGE_DOWNLOAD_CANCEL and never sends PRINT_IMAGE` (cancellation surfaces as normal coroutine cancellation — there is NO `Cancelled` progress value; assert on the transport's recorded packets)
6. `disconnect during data phase fails with RetryableTransferError`
7. `disconnect after print_image fails with PrintTriggeredButUnconfirmed` (the spec's special case — distinct type so UI can require re-confirmation)
8. `ack timeout produces RetryableTransferError` (use kotlinx-coroutines-test virtual time; 10s per-ack timeout)
9. `printer error payload (e.g. no film) surfaces as typed InstaxError and aborts before PRINT_IMAGE`
10. `second print() while one is in flight fails immediately with IllegalStateException` (single-flight)

- [ ] **Step 7.2: Run tests, verify they fail**

Run: `./gradlew :tool:testDebugUnitTest --tests "com.thelightphone.instax.protocol.InstaxSessionTest"`
Expected: FAIL — `InstaxSession` unresolved.

- [ ] **Step 7.3: Implement**

`InstaxSession(transport, scope)` — suspend API, single-flight (one print at a time; reject concurrent calls):

- `suspend fun connect(device): PrinterInfo` — connect transport, send the three info queries, parse, cache `chunkSize`.
- `fun print(jpeg: ByteArray): Flow<PrintProgress>` — cold flow: START(size) → await ack → for each chunk: DATA(i, chunk) → await ack → emit `Transferring(i+1, total)` → END → ack → PRINT_IMAGE → emit `Printing` → await completion notification → emit `Done`. If the fixtures/reference show no completion notification, fall back to polling `infoQuery(PRINTER_FUNCTION)` every 2s for at most 60s, treating "printer idle again" as done and 60s as `PrintTriggeredButUnconfirmed`. Cancellation of the collecting coroutine before PRINT_IMAGE sends `printCancel()` (in a `NonCancellable` finally block) and propagates as normal coroutine cancellation — no dedicated progress value; cancellation after PRINT_IMAGE is ignored (print already triggered).
- Sealed results: `PrintProgress.{Transferring, Printing, Done}` + exceptions `RetryableTransferError`, `PrintTriggeredButUnconfirmed`, `PrinterReportedError(InstaxError)`.
- Every `await` wrapped in `withTimeout(10s)`.

- [ ] **Step 7.4: Green** — `./gradlew :tool:testDebugUnitTest --tests "com.thelightphone.instax.protocol.*"` → PASS. (The coroutines-test dependency was added in Task 4's Step 4.3; timeout tests use `runTest` virtual time.)

- [ ] **Step 7.5: Commit**

```bash
git add tool/ && git commit -m "instax: session state machine — chunked transfer, cancel, disconnect semantics"
```

---

## Chunk 3: Bridge and transports

### Task 8: Bridge wire protocol + `TcpBridgeTransport` (Kotlin side)

Wire protocol (both directions, newline-delimited JSON over one TCP connection to `10.0.2.2:47845`; binary as base64):

```
client -> bridge: {"cmd":"scan"} | {"cmd":"connect","address":"..."} | {"cmd":"send","data":"<b64>"} | {"cmd":"disconnect"}
bridge -> client: {"event":"device","name":"...","address":"..."} | {"event":"connected"}
                  | {"event":"notify","data":"<b64>"} | {"event":"disconnected"}
                  | {"event":"error","message":"..."}
```

Event contract (binding for BOTH the Kotlin transport tests and the Python bridge): `connected` is emitted exactly once, after the BLE connection AND notify subscription succeed (fake mode: after `connect` names the fake's address); `error` is emitted for any failed command (unknown address, BLE failure, malformed JSON line) and the bridge then closes the client socket; each BLE notification becomes exactly one `notify` event — the transport's `notifications` flow assumes one whole protocol frame per event (holds for this printer per the reference implementation; if G2 ever shows a split frame, reassembly belongs in the bridge, not the Kotlin transport — note this in a comment).

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/transport/TcpBridgeTransport.kt`
- Test: `tool/src/test/kotlin/com/thelightphone/instax/transport/TcpBridgeTransportTest.kt`

- [ ] **Step 8.1: Failing tests** — spin an in-process `ServerSocket` on port 0 acting as a scripted bridge (plain JVM, no Android): scan emits devices; connect handshake; `send` writes correct JSON with correct base64; `notify` events surface on `notifications`; server closing socket → `notifications` completes with `TransportException`; malformed JSON line → error surfaced, transport closed.

- [ ] **Step 8.2: Implement** — `TcpBridgeTransport(host: String = "10.0.2.2", port: Int = 47845)` using `java.net.Socket` on `Dispatchers.IO`, kotlinx-serialization for the envelope (`@Serializable data class BridgeMsg(cmd/event/…)` with nullable fields — one class, keep it dumb), a reader coroutine feeding a `MutableSharedFlow`. `send()` = one JSON line. No protocol knowledge: it never inspects packet bytes.

- [ ] **Step 8.3: Green + commit** — `./gradlew :tool:testDebugUnitTest --tests "com.thelightphone.instax.transport.*"` → PASS.

```bash
git add tool/ && git commit -m "instax: TCP bridge transport (JSON-lines to host Mac)"
```

### Task 9: `bridge.py` — real relay + fake printer

**Files:**
- Create: `scripts/instax/bridge.py`
- Create: `scripts/instax/fake_printer.py`
- Test: `scripts/instax/test_fake_printer.py` (pytest — protocol behavior)
- Test: `scripts/instax/test_bridge.py` (pytest-asyncio — the --fake TCP server end-to-end)

- [ ] **Step 9.1: Failing pytest for the fake printer** — `fake_printer.py` implements the printer side of the protocol IN PYTHON, reusing packet build/parse from the pinned reference where practical:

```python
class FakeInstaxPrinter:
    """Protocol-faithful Link WIDE simulator. Feed it client packets, it returns
    notification packets. State: idle -> receiving(size, chunks) -> printing -> idle.
    Info responses: battery 82%, 7 prints remaining, image support 1260x840."""
    def handle(self, packet: bytes) -> list[bytes]: ...
```

Tests: info queries get well-formed responses (parse them back with the reference code); full START/DATA×N/END/PRINT_IMAGE flow acks every packet and ends in `printing`; DATA with wrong index → error packet; bad checksum → error packet; PRINT_IMAGE with zero "film" left → no-film error packet.

- [ ] **Step 9.2: Implement `fake_printer.py`; pytest green.** Run: `python3 -m pytest scripts/instax/ -v`

- [ ] **Step 9.3: Implement `bridge.py`** — asyncio TCP server on `--port 47845`, one client at a time:
  - Real mode: `bleak` — `scan` → BleakScanner filtered to names starting `INSTAX-` (emit `device` events); `connect` → BleakClient connect + subscribe notify char `70954784-2d83-473d-9e5f-81e1d02d5273`, then emit `connected` (emit `error` if either step fails); notifications forward as `notify` events; `send` → write to `…4783` split into ≤182-byte writes; disconnect callback → `disconnected` event.
  - `--fake`: no Bluetooth; `scan` emits one device `{"name":"INSTAX-FAKE(IOS)","address":"FA:KE:00:00:00:01"}`; `connect` to that address emits `connected` (any other address → `error`); `send` routes through `FakeInstaxPrinter` (with `--film N` option, default 7, and ~200ms simulated ack latency; a print takes ~5s before the completion notification).
  - Both modes honor the event contract in Task 8. Log every frame direction + opcode bytes to stderr (dev visibility).

- [ ] **Step 9.4: pytest the --fake server end-to-end** — `test_bridge.py` (pytest-asyncio): start the server on port 0 in-process; connect a TCP client; scan → device event with the fake address; connect → connected; send a START packet → ack notify arrives; malformed JSON line → error event + socket closed. Run: `python3 -m pytest scripts/instax/ -v` → PASS. Then a manual smoke: `python3 scripts/instax/bridge.py --fake` in one terminal; `printf '{"cmd":"scan"}\n' | nc localhost 47845` → device line arrives. Document in `scripts/instax/README.md`.

- [ ] **Step 9.5: Commit**

```bash
git add scripts/instax/ && git commit -m "instax: Mac bridge (bleak relay) + protocol-faithful fake printer"
```

### Task 10: `AndroidBleTransport` (compile-only, thin)

Spec: "compile-only/best-effort THIS milestone… keep it thin, don't over-invest." No gate can exercise it; it exists so the LP3 path is real code, not vaporware.

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/transport/AndroidBleTransport.kt`

- [ ] **Step 10.1: Implement (~150 lines max)** — `android.bluetooth.le.BluetoothLeScanner` filtered on the service UUID for `scan()`; `BluetoothGatt` connect + MTU request (185); `send()` splits at 182 bytes and writes the `…4783` characteristic (`WRITE_TYPE_WITHOUT_RESPONSE`, matching the reference); notify subscription on `…4784` → `notifications` flow; callbacks bridged with `callbackFlow`. Annotate `@RequiresPermission` where lint demands. NO unit tests (untestable without hardware; document that in a file-header comment pointing at the spec).

- [ ] **Step 10.2: Build + commit** — `./gradlew :tool:assembleDebug` → SUCCESS.

```bash
git add tool/ && git commit -m "instax: AndroidBleTransport (compile-only until hardware access exists)"
```

---

## Chunk 4: Photos, imaging, UI, gates

### Task 11: `PhotoRepository` + MediaStore implementation

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/photos/PhotoRepository.kt`
- Create: `tool/src/main/kotlin/com/thelightphone/instax/photos/MediaStorePhotoRepository.kt`

- [ ] **Step 11.1: Define the seam**

```kotlin
package com.thelightphone.instax.photos

import android.graphics.Bitmap

data class Photo(val id: Long, val displayName: String, val takenAtMillis: Long)

/** Album-tool access on real hardware is unresolved (spec, open question 1).
 *  MediaStore is the emulator dev implementation and the bet for real hardware. */
interface PhotoRepository {
    suspend fun photos(): List<Photo>              // newest first
    suspend fun thumbnail(photo: Photo): Bitmap?   // small, for list rows
    suspend fun fullImage(photo: Photo): Bitmap?   // for prep + preview
}
```

(Android import here is fine — `photos/` is not `protocol/`.)

- [ ] **Step 11.2: Implement `MediaStorePhotoRepository(context)`** — `ContentResolver.query` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (`_ID`, `DISPLAY_NAME`, `DATE_TAKEN`, sort `DATE_TAKEN DESC`); `loadThumbnail(uri, Size(160, 107), null)`; full image via `ImageDecoder` with EXIF orientation applied (ImageDecoder does this automatically — note it so `PrintImagePrep` doesn't rotate twice). All on `Dispatchers.IO`, exceptions → empty list / null (UI shows empty state). No unit tests (thin ContentResolver wrapper; exercised at G1).

- [ ] **Step 11.3: Build + commit** — `./gradlew :tool:assembleDebug` → SUCCESS.

```bash
git add tool/ && git commit -m "instax: photo repository over MediaStore"
```

### Task 12: `PrintImagePrep` — crop + JPEG quality ladder

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/imaging/PrintImagePrep.kt`
- Test: `tool/src/test/kotlin/com/thelightphone/instax/imaging/QualityLadderTest.kt`

- [ ] **Step 12.1: Failing tests for the ladder (pure logic, stubbed encoder)**

```kotlin
class QualityLadderTest {
    // encodeUnderLimit(encoder, limit): walks quality 95,90,...,40; returns first result <= limit
    @Test fun `returns first quality whose output fits`() { /* stub: q -> ByteArray(q * 1000) , limit 65535 -> picks 65 */ }
    @Test fun `throws ImageTooLargeException when even minimum quality exceeds limit`() { /* stub always 100_000 */ }
    @Test fun `crop geometry - portrait rotates to landscape, center-crop to 1260x840`() {
        // pure function computeCrop(srcW, srcH): CropPlan(rotateDegrees, srcRect) — assert cases:
        // 4000x3000 (landscape) -> no rotate, crop to 3:2 (1260:840) centered
        // 3000x4000 (portrait)  -> rotate 90, then crop centered
        // already 1260x840      -> identity
    }
}
```

- [ ] **Step 12.2: Implement** — `computeCrop` and `encodeUnderLimit` are pure Kotlin (JVM-tested). `PrintImagePrep(photo: Bitmap): ByteArray` composes them with `android.graphics` (`Matrix` rotate, `Bitmap.createBitmap` crop, `createScaledBitmap` to 1260×840, `compress(JPEG, q, …)` as the encoder lambda). Limit constant `MAX_JPEG_BYTES = 65_535` lives here, single source.

- [ ] **Step 12.3: Green + commit** — `./gradlew :tool:testDebugUnitTest --tests "com.thelightphone.instax.imaging.*"` → PASS.

```bash
git add tool/ && git commit -m "instax: print image prep — rotate/crop geometry + JPEG quality ladder"
```

### Task 13: `ErrorCopy` + `PrintFlowViewModel`

**Files:**
- Create: `tool/src/main/kotlin/com/thelightphone/instax/ui/ErrorCopy.kt`
- Create: `tool/src/main/kotlin/com/thelightphone/instax/InstaxApp.kt` (service locator holding the long-lived state)
- Create: `tool/src/main/kotlin/com/thelightphone/instax/ui/PrintFlowViewModel.kt`
- Test: `tool/src/test/kotlin/com/thelightphone/instax/ui/ErrorCopyTest.kt`

- [ ] **Step 13.1: `ErrorCopy`** (Tesla pattern: one object mapping every failure to user copy — headline + detail + action):

| Case | Copy |
|---|---|
| Bridge unreachable | "Can't reach the print bridge" / "Start it on your computer: python3 scripts/instax/bridge.py" / Retry |
| No printer found | "No printer found" / "Turn the printer on and keep it close" / Retry |
| `RetryableTransferError` | "Connection lost" / "Nothing was printed. Try again." / Retry |
| `PrintTriggeredButUnconfirmed` | "Print may have started" / "Check the printer before retrying — a retry uses another sheet." / Retry (confirm) |
| `PrinterReportedError(NO_FILM)` | "Out of film" / "Load a new WIDE cartridge." / — |
| `PrinterReportedError(COVER_OPEN)` | "Film cover open" / "Close the cover." / — |
| Low battery (from status) | "Printer battery low" / "Charge the printer, then retry." / — |
| `ImageTooLargeException` | "Can't prepare this photo" / "It can't be compressed enough to print." / — |
| Permission denied | "Photos permission needed" / "Print needs access to your photos." / Grant |

`ErrorCopy` takes its own sealed input type `PrintFailure` (Bridge unreachable / NoPrinterFound / RetryableTransfer / PrintTriggeredUnconfirmed / Printer(InstaxError) / LowBattery / ImageTooLarge / PermissionDenied); the viewmodel maps raw failures (`TransportException` on connect → BridgeUnreachable, scan timeout → NoPrinterFound, session exceptions → their cases) into it. Test: exhaustive `when` over `PrintFailure` — compilation is the test; plus one assert per mapping that copy is non-blank and distinct. (`LowBattery` is copy-only this milestone — no screen triggers it yet; noted for a future status-line threshold.)

- [ ] **Step 13.2: `InstaxApp` + `PrintFlowViewModel`**

VIEWMODEL LIFETIME (this is the subtle part): each `LightScreen` is its own ViewModelStoreOwner, so per-screen `PrintFlowViewModel` instances DO NOT share state. All hot state lives in the `InstaxApp` singleton — it owns the `InstaxSession`, the `PhotoRepository`, and the long-lived flows: `printerStatus: StateFlow<PrinterStatus?>`, `printState: StateFlow<PrintUiState>` (`Idle/Preparing/Transferring(pct)/Printing/Done/Failed(PrintFailure)`), `photos: StateFlow<List<Photo>>`, `selectedPhoto: MutableStateFlow<Photo?>`, plus the operations `refreshPhotos()`, `connectIfNeeded()`, `startPrint(photo)`, `cancelPrint()`, `acknowledgeResult()` running in an app-scope (`SupervisorJob + Dispatchers.Default`). Per-screen `PrintFlowViewModel` is a thin façade exposing those flows/ops to its screen — Preview starting a print and navigating away must leave Progress observing the same in-flight `printState`.

`connectIfNeeded()`: `withTimeoutOrNull(15s) { transport.scan().firstOrNull() }` — null → `Failed(NoPrinterFound)` (spec rule: no spinner may hang); then connect + info queries with the session's own timeouts. `startPrint(photo)`: prep on `Dispatchers.Default` → `session.print` collect into `printState`. Transport choice: `TcpBridgeTransport()` always this milestone (comment: swap point for `AndroidBleTransport` on real hardware).

- [ ] **Step 13.3: Green + commit** — `./gradlew :tool:testDebugUnitTest --tests "com.thelightphone.instax.ui.*"` → PASS.

```bash
git add tool/ && git commit -m "instax: error copy catalog + print flow viewmodel"
```

### Task 14: The three screens

**Files:**
- Modify: `tool/src/main/kotlin/com/thelightphone/instax/ui/PhotosScreen.kt`
- Create: `tool/src/main/kotlin/com/thelightphone/instax/ui/PreviewScreen.kt`
- Create: `tool/src/main/kotlin/com/thelightphone/instax/ui/ProgressScreen.kt`

All screens follow the sample `HomeScreen.kt` scaffold exactly (`LightTheme(colors)`, background, 32.dp padding, `LightText` variants, `lightClickable`). Navigation: `navigateTo(::PreviewScreen)` per `LightScreen.kt:41`.

- [ ] **Step 14.1: `PhotosScreen`** (`@InitialScreen`) — heading "Print"; status line under it (`"Printer: 7 prints left · battery 82%"` when connected, `"Looking for printer…"` while connecting, error copy headline if failed); permission gate: on show, `checkPermission(READ_MEDIA_IMAGES)` (defined at `LightServiceConnection.kt:147`); if not granted show explanation + "Allow" row wired to `rememberPermissionRequestLauncher(READ_MEDIA_IMAGES)?.launch()` — NOTE the launcher is nullable (null while the activity/service isn't available; see the `permissionLauncher?.launch()` usage pattern in `LightClientUiUtils.kt`), re-check on resume; granted → `LazyColumn` of photos (thumbnail `Image` 64dp + name + date row, `lightClickable` → select + `navigateTo(::PreviewScreen)`); empty list → `"No photos yet."`.

- [ ] **Step 14.2: `PreviewScreen`** — the cropped preview: render `computeCrop` result applied to the full bitmap scaled to fit width (this is exactly what will print — reuse the prep path, not a separate approximation); under it `"Print — 7 sheets left"` action row and `"Back"`; tapping Print → `startPrint(photo)` + `navigateTo(::ProgressScreen)`. If prints-left is 0, the action row is replaced by the out-of-film copy.

- [ ] **Step 14.3: `ProgressScreen`** — renders `printState`: Preparing → "Preparing photo…"; Transferring → "Sending to printer… NN%"; Printing → "Printing…" (plus "don't cover the film exit"); Done → "Done — grab your photo." + Back; Failed → error copy headline/detail + action row (Retry / Retry-with-confirm for `PrintTriggeredButUnconfirmed` — second tap required, first tap flips the row to "Tap again to use another sheet"); Cancel row visible only during Preparing/Transferring, wired to `cancelPrint()`. Back is disabled during Transferring/Printing (no orphaned prints).

- [ ] **Step 14.4: Build + commit** — `./gradlew :tool:assembleDebug` → SUCCESS.

```bash
git add tool/ && git commit -m "instax: photos/preview/progress screens"
```

### Task 15: Gate G1 — full flow on emulator against fake bridge (zero film)

Follow `docs/system_app/README.md` for the emulator setup (same as the Tesla tool sessions: system-app LightOS emulator, "Allowed Tools = All Tools").

- [ ] **Step 15.1: Prepare** — start `python3 scripts/instax/bridge.py --fake`; boot emulator; push 3 test photos of varied geometry (landscape 4:3, portrait, square):
`adb push test1.jpg /sdcard/Pictures/ && adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/test1.jpg` (repeat; or use `adb shell content` / reboot to index).
- [ ] **Step 15.2: Install + launch tool** — `./gradlew :tool:installDebug`; open via LightOS home.
- [ ] **Step 15.3: Walk the checklist, screenshot each state:**
  1. First launch → permission explanation → Allow → grant flow → photos appear
  2. Status line shows fake printer (7 prints, battery 82%)
  3. Empty state: `adb shell rm /sdcard/Pictures/*.jpg` followed by the media-scanner broadcast for each removed file (MediaStore rows linger without a rescan) → "No photos yet." → re-push
  4. Portrait photo preview is rotated+cropped correctly (visual check)
  5. Print → progress reaches Done; fake bridge log shows START/DATA×N/END/PRINT_IMAGE with correct sizes
  6. Prints-left decrements to 6 on return to Photos
  7. Cancel mid-transfer → UI returns to Photos/Idle; bridge log shows PRINT_IMAGE_DOWNLOAD_CANCEL, no PRINT_IMAGE
  8. Kill bridge mid-transfer → "Connection lost" retryable error
  9. Restart bridge with `--film 0` → out-of-film copy on Preview
- [ ] **Step 15.4: Fix everything found (TDD for logic bugs: failing test first), commit fixes individually.**
- [ ] **Step 15.5: Commit gate evidence** — note results in `tool/README.md` draft or commit message: `git commit -m "instax: G1 passed — full fake-printer flow on emulator" --allow-empty` (if no code changed).

### Task 16: Gate G2 — one real print + README

**REQUIRES THE USER** (physical printer, film, Mac Bluetooth). Coordinate before burning film.

- [ ] **Step 16.1: Real bridge smoke (no film cost)** — printer on, `python3 scripts/instax/bridge.py` (real mode), emulator flow up to Preview: status line must show REAL battery + prints remaining. This alone validates scan/connect/info parsing against the real device.
- [ ] **Step 16.2: The print** — user confirms; print one photo; expected: progress → Done, photo ejects and develops. If the printer rejects (checksum/chunk-size/ack surprises): capture bridge stderr log, fix, re-run. Budget: ≤3 sheets.
- [ ] **Step 16.3: Record real-device facts** — update `scripts/instax/README.md` "verified against real hardware" section: actual chunk size, ack timing, completion-notification shape, any parser offset corrections (mirror the Tesla `vcp-fixtures/README.md` practice).
- [ ] **Step 16.4: Write `tool/README.md`** — what the tool is, dev loop (fake bridge), real-print loop, the fork-only policy patch, LP3 path status (AndroidBleTransport compile-only; Album access open question), G1/G2 results.
- [ ] **Step 16.5: Final commit + push**

```bash
git add -A && git commit -m "instax: G2 passed — real print on instax Link WIDE; README"
git push mine instax-tool   # SSH push URL if https fails: git@github.com:amol-ship-it/light-sdk.git
```
