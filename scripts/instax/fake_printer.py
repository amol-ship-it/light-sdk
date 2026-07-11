"""Protocol-faithful instax Link WIDE simulator.

Feed it client packets, it returns the notification packets a real printer
would send. Used by bridge.py --fake so the full tool UI flow runs with zero
film and zero hardware. Protocol per scripts/instax/README.md; byte layouts
match the fixtures cross-generated from javl/InstaxBLE.
"""
from __future__ import annotations

from dataclasses import dataclass, field

HEADER_TO_PRINTER = b"\x41\x62"
HEADER_FROM_PRINTER = b"\x61\x42"

OP_SUPPORT_FUNCTION_INFO = (0, 2)
OP_PRINT_START = (16, 0)
OP_PRINT_DATA = (16, 1)
OP_PRINT_END = (16, 2)
OP_PRINT_CANCEL = (16, 3)
OP_PRINT_IMAGE = (16, 128)

INFO_IMAGE_SUPPORT = 0
INFO_BATTERY = 1
INFO_PRINTER_FUNCTION = 2

WIDE_WIDTH, WIDE_HEIGHT = 1260, 840
WIDE_CHUNK_SIZE = 900
MAX_JPEG_BYTES = 65535


def checksum(data: bytes) -> int:
    return (255 - (sum(data) & 255)) & 255


def build_packet(header: bytes, op: tuple[int, int], payload: bytes = b"") -> bytes:
    total = 7 + len(payload)
    packet = header + total.to_bytes(2, "big") + bytes(op) + payload
    return packet + bytes([checksum(packet)])


def parse_packet(packet: bytes) -> tuple[tuple[int, int], bytes] | None:
    """Validate a client->printer packet; return (op, payload) or None."""
    if len(packet) < 7 or packet[:2] != HEADER_TO_PRINTER:
        return None
    total = int.from_bytes(packet[2:4], "big")
    if total != len(packet) or (sum(packet) & 255) != 255:
        return None
    return (packet[4], packet[5]), packet[6:-1]


@dataclass
class FakeInstaxPrinter:
    """State machine: idle -> receiving(size, chunks) -> printing -> idle."""

    film: int = 7
    battery_percent: int = 82
    charging: bool = True

    state: str = field(default="idle", init=False)
    expected_size: int = field(default=0, init=False)
    received: bytearray = field(default_factory=bytearray, init=False)
    next_chunk: int = field(default=0, init=False)
    printed_images: list[bytes] = field(default_factory=list, init=False)

    def _info_response(self, info_type: int, data: bytes) -> bytes:
        # payload = [status=0, infoType echo, ...data]
        return build_packet(HEADER_FROM_PRINTER, OP_SUPPORT_FUNCTION_INFO,
                            bytes([0, info_type]) + data)

    def _ack(self, op: tuple[int, int]) -> bytes:
        return build_packet(HEADER_FROM_PRINTER, op, b"\x00")

    def _error(self, op: tuple[int, int]) -> bytes:
        # Non-zero status byte in the echoed-opcode response = rejection.
        return build_packet(HEADER_FROM_PRINTER, op, b"\x01")

    def handle(self, packet: bytes) -> list[bytes]:
        parsed = parse_packet(packet)
        if parsed is None:
            # A real printer stays silent on garbage; the bridge logs it.
            return []
        op, payload = parsed

        if op == OP_SUPPORT_FUNCTION_INFO:
            info_type = payload[0] if payload else -1
            if info_type == INFO_IMAGE_SUPPORT:
                data = WIDE_WIDTH.to_bytes(2, "big") + WIDE_HEIGHT.to_bytes(2, "big")
                return [self._info_response(INFO_IMAGE_SUPPORT, data)]
            if info_type == INFO_BATTERY:
                return [self._info_response(INFO_BATTERY, bytes([2, self.battery_percent]))]
            if info_type == INFO_PRINTER_FUNCTION:
                status = (self.film & 15) | (0x80 if self.charging else 0)
                return [self._info_response(INFO_PRINTER_FUNCTION, bytes([status]))]
            return []

        if op == OP_PRINT_START:
            if len(payload) != 8 or payload[:4] != b"\x02\x00\x00\x00":
                return [self._error(op)]
            self.expected_size = int.from_bytes(payload[4:8], "big")
            if self.expected_size == 0 or self.expected_size > MAX_JPEG_BYTES:
                return [self._error(op)]
            self.state = "receiving"
            self.received = bytearray()
            self.next_chunk = 0
            return [self._ack(op)]

        if op == OP_PRINT_DATA:
            if self.state != "receiving" or len(payload) < 4:
                return [self._error(op)]
            index = int.from_bytes(payload[:4], "big")
            if index != self.next_chunk:
                self.state = "idle"
                return [self._error(op)]
            self.next_chunk += 1
            self.received.extend(payload[4:])
            return [self._ack(op)]

        if op == OP_PRINT_END:
            if self.state != "receiving" or len(self.received) < self.expected_size:
                self.state = "idle"
                return [self._error(op)]
            self.state = "ready"
            return [self._ack(op)]

        if op == OP_PRINT_CANCEL:
            self.state = "idle"
            self.received = bytearray()
            return [self._ack(op)]

        if op == OP_PRINT_IMAGE:
            if self.state != "ready":
                return [self._error(op)]
            if self.film <= 0:
                self.state = "idle"
                return [self._error(op)]
            self.film -= 1
            self.printed_images.append(bytes(self.received[: self.expected_size]))
            self.state = "idle"
            return [self._ack(op)]

        return []
