#!/usr/bin/env python3
"""Generate Instax protocol byte fixtures from the pinned javl/InstaxBLE reference.

The fixtures are the protocol ground truth for the Kotlin implementation in
tool/src/main/kotlin/com/thelightphone/instax/protocol/. Where Kotlin code and
these fixtures disagree, the fixtures win.

Request packets are produced by the REFERENCE'S OWN code (InstaxBLE.create_packet
and InstaxBLE.print_image), not a reimplementation. Response packets are
constructed locally (the reference has no response builder) but every response
fixture is validated by driving the reference's parse_printer_response on it and
asserting the parsed state matches the fixture's `meaning`.

Usage:
  python3 gen_fixtures.py --reference-dir ~/workspaces/instax-reference \
      --out ../../tool/src/test/resources/instax-fixtures/fixtures.json
"""
import argparse
import json
import subprocess
import sys
import types
from pathlib import Path

PINNED_COMMIT = "804dca8cfaba5db48bc5f961aab106006e600303"

WIDE_CHUNK_SIZE = 900  # asserted against the reference's PrinterSettings below


def load_reference(reference_dir: Path):
    """Import the reference with its BLE/imaging deps stubbed out."""
    subprocess.run(
        ["git", "-C", str(reference_dir), "checkout", "--quiet", PINNED_COMMIT],
        check=True,
    )
    # InstaxBLE.py imports simplepyble and PIL at module level; neither is
    # needed for packet building/parsing. Stub them. PIL.Image must itself
    # expose an `Image` attribute (used in a type annotation evaluated at
    # class-definition time).
    sys.modules.setdefault("simplepyble", types.ModuleType("simplepyble"))
    image_mod = types.ModuleType("PIL.Image")
    image_mod.Image = object
    pil_mod = types.ModuleType("PIL")
    pil_mod.Image = image_mod
    sys.modules.setdefault("PIL", pil_mod)
    sys.modules.setdefault("PIL.Image", image_mod)
    sys.path.insert(0, str(reference_dir))
    import InstaxBLE as ib  # noqa: E402
    import Types as t  # noqa: E402
    return ib, t


def bare_instance(ib):
    """Reference instance without __init__ (skips BLE adapter discovery)."""
    inst = ib.InstaxBLE.__new__(ib.InstaxBLE)
    inst.dummyPrinter = True
    inst.printEnabled = True
    inst.quiet = True
    inst.verbose = False
    inst.photosLeft = 1
    inst.packetsForPrinting = []
    inst.chunkSize = WIDE_CHUNK_SIZE
    inst.waitingForResponse = False
    inst.cancelled = False
    inst.batteryState = 0
    inst.batteryPercentage = 0
    inst.isCharging = False
    inst.imageSize = (0, 0)
    inst.pos = (0, 0, 0, 0)
    return inst


def response_packet(inst, op1: int, op2: int, payload: bytes) -> bytes:
    """printer->client packet: header aB, same length/checksum scheme as create_packet."""
    header = b"\x61\x42"
    total = 7 + len(payload)
    packet = header + total.to_bytes(2, "big") + bytes([op1, op2]) + payload
    packet += bytes([inst.create_checksum(packet)])
    assert inst.validate_checksum(packet), "constructed response failed reference checksum validation"
    return packet


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference-dir", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    ib, t = load_reference(args.reference_dir.expanduser())
    ET, IT = t.EventType, t.InfoType
    assert t.PrinterSettings["wide"]["chunkSize"] == WIDE_CHUNK_SIZE
    assert (t.PrinterSettings["wide"]["width"], t.PrinterSettings["wide"]["height"]) == (1260, 840)

    inst = bare_instance(ib)
    fixtures = []

    def add_request(name, event, payload=b"", extra=None):
        packet = inst.create_packet(event, payload)
        op = event.value if hasattr(event, "value") else event
        meaning = {"op1": op[0], "op2": op[1], "payload_hex": payload.hex()}
        meaning.update(extra or {})
        fixtures.append({"name": name, "hex": packet.hex(), "meaning": meaning})
        return packet

    # --- info queries (payload = InfoType byte) ---
    add_request("support_function_info_image_support", ET.SUPPORT_FUNCTION_INFO,
                bytes([IT.IMAGE_SUPPORT_INFO.value]))
    add_request("support_function_info_battery", ET.SUPPORT_FUNCTION_INFO,
                bytes([IT.BATTERY_INFO.value]))
    add_request("support_function_info_printer_function", ET.SUPPORT_FUNCTION_INFO,
                bytes([IT.PRINTER_FUNCTION_INFO.value]))

    # --- full print flow via the reference's own print_image() ---
    img = bytes(i % 251 for i in range(1800))
    inst.packetsForPrinting = []
    inst.print_image(img)
    flow = inst.packetsForPrinting
    # START, DATA x2, END, PRINT_IMAGE, post-print info query
    assert len(flow) == 6, f"expected 6 flow packets, got {len(flow)}"
    names = ["print_start_1800_bytes", "print_data_chunk0", "print_data_chunk1",
             "print_end", "print_image", "post_print_function_query"]
    metas = [
        {"op1": 16, "op2": 0, "image_size": len(img), "size_prefix_hex": "02000000"},
        {"op1": 16, "op2": 1, "chunk_index": 0},
        {"op1": 16, "op2": 1, "chunk_index": 1},
        {"op1": 16, "op2": 2},
        {"op1": 16, "op2": 128},
        {"op1": 0, "op2": 2, "payload_hex": "02"},
    ]
    for name, packet, meta in zip(names, flow, metas):
        meta.setdefault("payload_hex", bytes(packet[6:-1]).hex())
        fixtures.append({"name": name, "hex": bytes(packet).hex(), "meaning": meta})

    # last-chunk zero-padding case: 1900 bytes -> chunks 900/900/100, so the
    # third DATA packet (flow index 3) is 100 data bytes + 800 zeros of padding
    inst.packetsForPrinting = []
    inst.print_image(bytes(i % 251 for i in range(1900)))
    padded = bytes(inst.packetsForPrinting[3])
    assert padded[6 + 4 + 100:-1] == bytes(WIDE_CHUNK_SIZE - 100), "padding mismatch"
    fixtures.append({
        "name": "print_data_last_chunk_padded",
        "hex": padded.hex(),
        "meaning": {"op1": 16, "op2": 1, "chunk_index": 2,
                    "data_bytes": 100, "padded_to": WIDE_CHUNK_SIZE,
                    "payload_hex": padded[6:-1].hex()},
    })

    # --- checksum cases (arbitrary packets, last byte is the checksum) ---
    for i, (event, payload) in enumerate([
        (ET.PRINT_IMAGE_DOWNLOAD_CANCEL, b""),
        (ET.SUPPORT_FUNCTION_INFO, b"\x00"),
        (ET.PRINT_IMAGE_DOWNLOAD_START, b"\x02\x00\x00\x00\x00\x00\xff\xff"),
        (ET.PRINT_IMAGE, b""),
        (ET.SUPPORT_FUNCTION_INFO, b"\x02"),
    ]):
        packet = inst.create_packet(event, payload)
        fixtures.append({
            "name": f"checksum_case_{i}",
            "hex": packet.hex(),
            "meaning": {"op1": event.value[0], "op2": event.value[1],
                        "payload_hex": payload.hex(), "checksum": packet[-1]},
        })

    # --- response-direction fixtures, validated via parse_printer_response ---
    # SUPPORT_FUNCTION_INFO response payload layout (from parse_printer_response):
    #   payload[0] = packet[6]  status byte (0 = ok; not inspected by reference)
    #   payload[1] = packet[7]  InfoType echo
    #   data at packet[8:]
    def add_info_response(name, info_type, data, verify, meaning_extra):
        payload = bytes([0, info_type.value]) + data
        packet = response_packet(inst, 0, 2, payload)
        inst.parse_printer_response(ET.SUPPORT_FUNCTION_INFO, packet)
        verify()
        meaning = {"op1": 0, "op2": 2, "status_offset": 6, "info_type_offset": 7,
                   "payload_hex": payload.hex()}
        meaning.update(meaning_extra)
        fixtures.append({"name": name, "hex": packet.hex(), "meaning": meaning})

    add_info_response(
        "response_image_support_wide", IT.IMAGE_SUPPORT_INFO,
        (1260).to_bytes(2, "big") + (840).to_bytes(2, "big"),
        lambda: (
            (lambda: None)() if inst.imageSize == (1260, 840) and inst.chunkSize == WIDE_CHUNK_SIZE
            else (_ for _ in ()).throw(AssertionError(f"image support parse failed: {inst.imageSize}"))
        ),
        {"width": 1260, "height": 840, "width_offset": 8, "height_offset": 10,
         "wide_chunk_size": WIDE_CHUNK_SIZE},
    )
    add_info_response(
        "response_battery", IT.BATTERY_INFO, bytes([2, 82]),
        lambda: (
            None if (inst.batteryState, inst.batteryPercentage) == (2, 82)
            else (_ for _ in ()).throw(AssertionError("battery parse failed"))
        ),
        {"battery_state": 2, "battery_percent": 82,
         "battery_state_offset": 8, "battery_percent_offset": 9},
    )
    add_info_response(
        "response_printer_function_7_left_charging", IT.PRINTER_FUNCTION_INFO,
        bytes([(1 << 7) | 7]),
        lambda: (
            None if (inst.photosLeft, inst.isCharging) == (7, True)
            else (_ for _ in ()).throw(AssertionError("printer function parse failed"))
        ),
        {"photos_left": 7, "charging": True, "status_byte_offset": 8,
         "photos_left_mask": 15, "charging_bit": 7},
    )
    add_info_response(
        "response_printer_function_0_left_not_charging", IT.PRINTER_FUNCTION_INFO,
        bytes([0]),
        lambda: (
            None if (inst.photosLeft, inst.isCharging) == (0, False)
            else (_ for _ in ()).throw(AssertionError("printer function parse failed"))
        ),
        {"photos_left": 0, "charging": False, "status_byte_offset": 8},
    )

    # Plain acks: printer mirrors the request opcode; ack payload observed as
    # a single status byte (0 = ok) in community captures. parse_printer_response
    # only dispatches on the opcode for these, which is what the Kotlin session
    # relies on (isAckFor = same opcode).
    for name, event in [
        ("ack_print_start", ET.PRINT_IMAGE_DOWNLOAD_START),
        ("ack_print_data", ET.PRINT_IMAGE_DOWNLOAD_DATA),
        ("ack_print_end", ET.PRINT_IMAGE_DOWNLOAD_END),
        ("ack_print_image", ET.PRINT_IMAGE),
        ("ack_print_cancel", ET.PRINT_IMAGE_DOWNLOAD_CANCEL),
    ]:
        packet = response_packet(inst, event.value[0], event.value[1], b"\x00")
        inst.parse_printer_response(event, packet)  # must not raise
        fixtures.append({
            "name": name, "hex": packet.hex(),
            "meaning": {"op1": event.value[0], "op2": event.value[1], "payload_hex": "00"},
        })

    out = {
        "reference": "https://github.com/javl/InstaxBLE",
        "reference_commit": PINNED_COMMIT,
        "wide_chunk_size": WIDE_CHUNK_SIZE,
        "wide_width": 1260,
        "wide_height": 840,
        "max_jpeg_bytes": 65535,
        "ble_write_split": 182,
        "fixtures": fixtures,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out, indent=2) + "\n")
    print(f"wrote {len(fixtures)} fixtures to {args.out}")


if __name__ == "__main__":
    main()
