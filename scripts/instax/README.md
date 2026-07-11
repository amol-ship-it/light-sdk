# Instax tool — protocol reference & Mac bridge

Dev-side support for the instax printing tool in `tool/`. Nothing in this
directory ships in the APK.

## Protocol ground truth

The Kotlin protocol implementation is verified against byte fixtures in
`tool/src/test/resources/instax-fixtures/fixtures.json`, cross-generated from
the community reference implementation:

- **Reference:** https://github.com/javl/InstaxBLE
- **Pinned commit:** `804dca8cfaba5db48bc5f961aab106006e600303`

Where Kotlin code and fixtures disagree, THE FIXTURES WIN — fix the Kotlin.
(Lesson inherited from the Tesla tool's `vcp-fixtures`: cross-implementation
fixtures catch protocol bugs unit tests can't.)

Request fixtures are produced by the reference's own `create_packet` /
`print_image` code. Response fixtures are constructed locally (the reference
has no response builder) and validated by driving the reference's
`parse_printer_response` on them and asserting the parsed state.

### Regenerating

```bash
git clone https://github.com/javl/InstaxBLE ~/workspaces/instax-reference
python3 gen_fixtures.py --reference-dir ~/workspaces/instax-reference \
    --out ../../tool/src/test/resources/instax-fixtures/fixtures.json
```

`gen_fixtures.py` checks out the pinned commit in the reference dir before
importing, so regeneration is reproducible.

## Protocol summary (instax Link WIDE)

- BLE GATT. Service `70954782-2d83-473d-9e5f-81e1d02d5273`, write char `…4783`,
  notify char `…4784`. Advertises as `INSTAX-xxxxxxxx(IOS)` (the `(ANDROID)`
  identity is Bluetooth Classic RFCOMM — unused here).
- Packet: header (`41 62` client→printer, `61 42` printer→client) +
  2-byte BE total length (7 + payload) + 2-byte opcode + payload +
  1-byte checksum `(255 - (sum & 255)) & 255`.
- Print flow: `PRINT_IMAGE_DOWNLOAD_START` (payload `02 00 00 00` + 4-byte BE
  image size) → `PRINT_IMAGE_DOWNLOAD_DATA` × N (4-byte BE chunk index +
  900-byte chunk, last chunk zero-padded) → `PRINT_IMAGE_DOWNLOAD_END` →
  `PRINT_IMAGE`. The printer acks each packet by echoing its opcode; the next
  packet is sent only after the ack.
- Image contract: 1260×840 JPEG, ≤ 65,535 bytes. Chunk size 900 comes from the
  IMAGE_SUPPORT info response (1260×840 → wide settings), not a constant.
- Info queries: `SUPPORT_FUNCTION_INFO (0,2)` with a 1-byte InfoType payload —
  IMAGE_SUPPORT(0): width/height BE at packet[8:12]; BATTERY(1): state,
  percent at packet[8:10]; PRINTER_FUNCTION(2): packet[8] low 4 bits =
  prints remaining, bit 7 = charging.
- BLE writes are split at 182 bytes (the transport/bridge handles this).

### Verified against real hardware

_Not yet — G2 pending. Record actual ack timing, ack payload shapes, and any
offset corrections here after the first real print._

## Mac bridge (`bridge.py`)

See the tool README (`tool/README.md`) and the plan
(`docs/superpowers/plans/2026-07-10-instax-tool.md`) for the bridge wire
protocol. Quick start:

```bash
pip install bleak pytest pytest-asyncio
python3 bridge.py --fake        # simulated printer, zero film (default dev loop)
python3 bridge.py               # real printer over BLE
python3 -m pytest . -v          # bridge + fake printer tests
```
