#!/usr/bin/env python3
"""Mac-side Bluetooth bridge for the instax tool.

Dumb pipe by design: relays opaque protocol frames between a TCP client (the
tool running in the Android emulator, reaching the host via 10.0.2.2) and the
printer's GATT characteristics. Composes no protocol bytes itself; all protocol
logic lives in the tool (real mode) or fake_printer.py (--fake).

Wire protocol (newline-delimited JSON, binary as base64), one client at a time:

  client -> bridge: {"cmd":"scan"} | {"cmd":"connect","address":...}
                    | {"cmd":"send","data":"<b64>"} | {"cmd":"disconnect"}
  bridge -> client: {"event":"device","name":...,"address":...} | {"event":"connected"}
                    | {"event":"notify","data":"<b64>"} | {"event":"disconnected"}
                    | {"event":"error","message":...}

Event contract: `connected` exactly once, after BLE connect + notify
subscription succeed; `error` for any failed command (then the socket closes);
one BLE notification = one `notify` event.

Usage:
  python3 bridge.py --fake [--film 7]     # simulated printer, zero film
  python3 bridge.py                       # real printer via bleak
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import sys

SERVICE_UUID = "70954782-2d83-473d-9e5f-81e1d02d5273"
WRITE_CHAR_UUID = "70954783-2d83-473d-9e5f-81e1d02d5273"
NOTIFY_CHAR_UUID = "70954784-2d83-473d-9e5f-81e1d02d5273"
BLE_WRITE_SPLIT = 182

FAKE_NAME = "INSTAX-FAKE(IOS)"
FAKE_ADDRESS = "FA:KE:00:00:00:01"


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def opcode_of(frame: bytes) -> str:
    return f"({frame[4]},{frame[5]})" if len(frame) >= 6 else "short-frame"


class Backend:
    """One printer connection. Implemented by FakeBackend and BleBackend."""

    async def scan(self, emit) -> None: ...
    async def connect(self, address: str, emit) -> None: ...
    async def send(self, frame: bytes, emit) -> None: ...
    async def disconnect(self) -> None: ...


class FakeBackend(Backend):
    def __init__(self, film: int, ack_latency: float = 0.2, print_delay: float = 5.0):
        from fake_printer import FakeInstaxPrinter, OP_PRINT_IMAGE
        self.printer = FakeInstaxPrinter(film=film)
        self.ack_latency = ack_latency
        self.print_delay = print_delay
        self.print_image_op = OP_PRINT_IMAGE
        self.connected = False

    async def scan(self, emit) -> None:
        await emit({"event": "device", "name": FAKE_NAME, "address": FAKE_ADDRESS})

    async def connect(self, address: str, emit) -> None:
        if address != FAKE_ADDRESS:
            raise RuntimeError(f"unknown address {address}")
        self.connected = True
        await emit({"event": "connected"})

    async def send(self, frame: bytes, emit) -> None:
        if not self.connected:
            raise RuntimeError("not connected")
        is_print = len(frame) >= 6 and (frame[4], frame[5]) == self.print_image_op
        await asyncio.sleep(self.ack_latency)
        if is_print and self.printer.state == "ready" and self.printer.film > 0:
            log(f"fake: printing... ({self.print_delay}s)")
            await asyncio.sleep(self.print_delay)
        for response in self.printer.handle(frame):
            await emit({"event": "notify", "data": base64.b64encode(response).decode()})

    async def disconnect(self) -> None:
        self.connected = False


class BleBackend(Backend):
    def __init__(self) -> None:
        self.client = None
        self.devices: dict[str, object] = {}

    async def scan(self, emit) -> None:
        from bleak import BleakScanner
        log("scanning for INSTAX-* ...")
        found = await BleakScanner.discover(timeout=8.0)
        for d in found:
            if d.name and d.name.startswith("INSTAX-"):
                self.devices[d.address] = d
                await emit({"event": "device", "name": d.name, "address": d.address})

    async def connect(self, address: str, emit) -> None:
        from bleak import BleakClient

        def on_disconnect(_client) -> None:
            asyncio.get_event_loop().create_task(emit({"event": "disconnected"}))

        target = self.devices.get(address, address)
        client = BleakClient(target, disconnected_callback=on_disconnect)
        await client.connect()

        async def on_notify(_char, data: bytearray) -> None:
            log(f"<- notify {opcode_of(bytes(data))} ({len(data)}B)")
            await emit({"event": "notify", "data": base64.b64encode(bytes(data)).decode()})

        await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
        self.client = client
        await emit({"event": "connected"})

    async def send(self, frame: bytes, emit) -> None:
        if self.client is None:
            raise RuntimeError("not connected")
        for i in range(0, len(frame), BLE_WRITE_SPLIT):
            await self.client.write_gatt_char(
                WRITE_CHAR_UUID, frame[i:i + BLE_WRITE_SPLIT], response=False
            )

    async def disconnect(self) -> None:
        if self.client is not None:
            try:
                await self.client.disconnect()
            finally:
                self.client = None


async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter,
                        backend: Backend) -> None:
    peer = writer.get_extra_info("peername")
    log(f"client connected: {peer}")
    write_lock = asyncio.Lock()

    async def emit(obj: dict) -> None:
        async with write_lock:
            writer.write((json.dumps(obj) + "\n").encode())
            await writer.drain()

    try:
        while True:
            line = await reader.readline()
            if not line:
                break
            try:
                msg = json.loads(line)
                cmd = msg.get("cmd")
                if cmd == "scan":
                    await backend.scan(emit)
                elif cmd == "connect":
                    await backend.connect(msg["address"], emit)
                elif cmd == "send":
                    frame = base64.b64decode(msg["data"])
                    log(f"-> send {opcode_of(frame)} ({len(frame)}B)")
                    await backend.send(frame, emit)
                elif cmd == "disconnect":
                    await backend.disconnect()
                    await emit({"event": "disconnected"})
                else:
                    raise RuntimeError(f"unknown cmd: {cmd!r}")
            except Exception as e:  # error contract: report, then close
                log(f"error: {e}")
                try:
                    await emit({"event": "error", "message": str(e)})
                except Exception:
                    pass
                break
    finally:
        await backend.disconnect()
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
        log("client disconnected")


async def serve(host: str, port: int, make_backend) -> asyncio.AbstractServer:
    async def on_client(reader, writer):
        await handle_client(reader, writer, make_backend())

    server = await asyncio.start_server(on_client, host, port)
    log(f"bridge listening on {host}:{port}")
    return server


async def amain() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--port", type=int, default=47845)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--fake", action="store_true", help="simulated printer (no Bluetooth)")
    ap.add_argument("--film", type=int, default=7, help="fake mode: prints remaining")
    ap.add_argument("--print-delay", type=float, default=5.0, help="fake mode: seconds per print")
    args = ap.parse_args()

    if args.fake:
        # one shared fake so the film count persists across client reconnects
        fake = FakeBackend(film=args.film, print_delay=args.print_delay)
        backend_factory = lambda: fake  # noqa: E731
        log(f"FAKE mode: {args.film} prints loaded")
    else:
        backend_factory = BleBackend

    server = await serve(args.host, args.port, backend_factory)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(amain())
    except KeyboardInterrupt:
        pass
