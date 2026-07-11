"""End-to-end tests of the --fake TCP server (bridge.py) via a raw TCP client."""
import asyncio
import base64
import json

import pytest

from bridge import FAKE_ADDRESS, FAKE_NAME, FakeBackend, serve
from fake_printer import (
    HEADER_TO_PRINTER,
    OP_PRINT_START,
    OP_SUPPORT_FUNCTION_INFO,
    INFO_BATTERY,
    build_packet,
)


class BridgeClient:
    def __init__(self, reader, writer):
        self.reader = reader
        self.writer = writer

    async def send(self, obj: dict) -> None:
        self.writer.write((json.dumps(obj) + "\n").encode())
        await self.writer.drain()

    async def recv(self, timeout: float = 5.0) -> dict | None:
        line = await asyncio.wait_for(self.reader.readline(), timeout)
        return json.loads(line) if line else None

    def close(self) -> None:
        self.writer.close()


@pytest.fixture
async def bridge():
    fake = FakeBackend(film=7, ack_latency=0.0, print_delay=0.0)
    server = await serve("127.0.0.1", 0, lambda: fake)
    port = server.sockets[0].getsockname()[1]
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    client = BridgeClient(reader, writer)
    yield client
    client.close()
    server.close()
    await server.wait_closed()


@pytest.mark.asyncio
async def test_scan_returns_fake_device(bridge):
    await bridge.send({"cmd": "scan"})
    msg = await bridge.recv()
    assert msg == {"event": "device", "name": FAKE_NAME, "address": FAKE_ADDRESS}


@pytest.mark.asyncio
async def test_connect_then_send_start_gets_ack(bridge):
    await bridge.send({"cmd": "connect", "address": FAKE_ADDRESS})
    assert (await bridge.recv())["event"] == "connected"

    start = build_packet(HEADER_TO_PRINTER, OP_PRINT_START,
                         b"\x02\x00\x00\x00" + (1800).to_bytes(4, "big"))
    await bridge.send({"cmd": "send", "data": base64.b64encode(start).decode()})
    msg = await bridge.recv()
    assert msg["event"] == "notify"
    ack = base64.b64decode(msg["data"])
    assert (ack[4], ack[5]) == OP_PRINT_START
    assert ack[6:-1] == b"\x00"  # ok status


@pytest.mark.asyncio
async def test_info_query_roundtrip(bridge):
    await bridge.send({"cmd": "connect", "address": FAKE_ADDRESS})
    await bridge.recv()
    query = build_packet(HEADER_TO_PRINTER, OP_SUPPORT_FUNCTION_INFO, bytes([INFO_BATTERY]))
    await bridge.send({"cmd": "send", "data": base64.b64encode(query).decode()})
    msg = await bridge.recv()
    payload = base64.b64decode(msg["data"])[6:-1]
    assert payload[1] == INFO_BATTERY
    assert payload[3] == 82


@pytest.mark.asyncio
async def test_connect_to_unknown_address_errors_and_closes(bridge):
    await bridge.send({"cmd": "connect", "address": "AA:BB:CC:DD:EE:FF"})
    msg = await bridge.recv()
    assert msg["event"] == "error"
    assert await bridge.recv() is None  # socket closed after error


@pytest.mark.asyncio
async def test_malformed_json_errors_and_closes(bridge):
    bridge.writer.write(b"this is not json\n")
    await bridge.writer.drain()
    msg = await bridge.recv()
    assert msg["event"] == "error"
    assert await bridge.recv() is None
