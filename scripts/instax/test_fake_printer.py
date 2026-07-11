"""Protocol behavior tests for FakeInstaxPrinter, cross-checked with the
pinned javl/InstaxBLE reference where available (see conftest-less import:
tests only need the local module)."""
import pytest

from fake_printer import (
    FakeInstaxPrinter,
    HEADER_FROM_PRINTER,
    HEADER_TO_PRINTER,
    OP_PRINT_DATA,
    OP_PRINT_END,
    OP_PRINT_IMAGE,
    OP_PRINT_START,
    OP_SUPPORT_FUNCTION_INFO,
    INFO_BATTERY,
    INFO_IMAGE_SUPPORT,
    INFO_PRINTER_FUNCTION,
    WIDE_CHUNK_SIZE,
    build_packet,
    checksum,
    parse_packet,
)


def req(op, payload=b""):
    return build_packet(HEADER_TO_PRINTER, op, payload)


def parse_response(packet: bytes):
    assert packet[:2] == HEADER_FROM_PRINTER
    assert (sum(packet) & 255) == 255, "bad checksum"
    return (packet[4], packet[5]), packet[6:-1]


def start_payload(size: int) -> bytes:
    return b"\x02\x00\x00\x00" + size.to_bytes(4, "big")


def data_payload(index: int, chunk: bytes) -> bytes:
    padded = chunk + bytes(WIDE_CHUNK_SIZE - len(chunk))
    return index.to_bytes(4, "big") + padded


@pytest.fixture
def printer():
    return FakeInstaxPrinter()


def test_info_queries_get_wellformed_responses(printer):
    op, payload = parse_response(printer.handle(req(OP_SUPPORT_FUNCTION_INFO, bytes([INFO_IMAGE_SUPPORT])))[0])
    assert op == OP_SUPPORT_FUNCTION_INFO
    assert payload[1] == INFO_IMAGE_SUPPORT
    assert int.from_bytes(payload[2:4], "big") == 1260
    assert int.from_bytes(payload[4:6], "big") == 840

    op, payload = parse_response(printer.handle(req(OP_SUPPORT_FUNCTION_INFO, bytes([INFO_BATTERY])))[0])
    assert payload[1] == INFO_BATTERY
    assert payload[3] == 82

    op, payload = parse_response(printer.handle(req(OP_SUPPORT_FUNCTION_INFO, bytes([INFO_PRINTER_FUNCTION])))[0])
    assert payload[1] == INFO_PRINTER_FUNCTION
    assert payload[2] & 15 == 7
    assert payload[2] & 0x80  # charging


def full_print(printer, image: bytes):
    responses = [printer.handle(req(OP_PRINT_START, start_payload(len(image))))]
    chunks = [image[i:i + WIDE_CHUNK_SIZE] for i in range(0, len(image), WIDE_CHUNK_SIZE)]
    for i, chunk in enumerate(chunks):
        responses.append(printer.handle(req(OP_PRINT_DATA, data_payload(i, chunk))))
    responses.append(printer.handle(req(OP_PRINT_END)))
    responses.append(printer.handle(req(OP_PRINT_IMAGE)))
    return responses


def test_full_print_flow_acks_everything_and_prints(printer):
    image = bytes(i % 251 for i in range(1800))
    responses = full_print(printer, image)
    for r in responses:
        op, payload = parse_response(r[0])
        assert payload == b"\x00", f"expected ok ack, got {payload.hex()} for {op}"
    assert printer.film == 6
    assert printer.printed_images == [image]
    assert printer.state == "idle"


def test_wrong_chunk_index_is_rejected(printer):
    printer.handle(req(OP_PRINT_START, start_payload(1800)))
    r = printer.handle(req(OP_PRINT_DATA, data_payload(1, b"x" * 900)))  # should be 0
    _, payload = parse_response(r[0])
    assert payload != b"\x00"
    assert printer.state == "idle"


def test_bad_checksum_is_ignored(printer):
    packet = bytearray(req(OP_SUPPORT_FUNCTION_INFO, bytes([INFO_BATTERY])))
    packet[-1] ^= 0xFF
    assert printer.handle(bytes(packet)) == []


def test_print_image_with_no_film_is_rejected():
    printer = FakeInstaxPrinter(film=0)
    image = bytes(100)
    printer.handle(req(OP_PRINT_START, start_payload(len(image))))
    printer.handle(req(OP_PRINT_DATA, data_payload(0, image)))
    printer.handle(req(OP_PRINT_END))
    r = printer.handle(req(OP_PRINT_IMAGE))
    _, payload = parse_response(r[0])
    assert payload != b"\x00"
    assert printer.printed_images == []


def test_end_before_all_data_is_rejected(printer):
    printer.handle(req(OP_PRINT_START, start_payload(1800)))
    printer.handle(req(OP_PRINT_DATA, data_payload(0, b"a" * 900)))
    r = printer.handle(req(OP_PRINT_END))
    _, payload = parse_response(r[0])
    assert payload != b"\x00"


def test_oversized_start_is_rejected(printer):
    r = printer.handle(req(OP_PRINT_START, start_payload(70000)))
    _, payload = parse_response(r[0])
    assert payload != b"\x00"


def test_parse_packet_roundtrip():
    packet = req(OP_PRINT_START, start_payload(1234))
    op, payload = parse_packet(packet)
    assert op == OP_PRINT_START
    assert payload == start_payload(1234)
    assert checksum(packet[:-1]) == packet[-1]
