# Print — instax Link WIDE printing tool for LightOS

Prints photos on a Fujifilm instax Link WIDE over Bluetooth LE. Browse photos,
preview the exact crop, confirm ("Print — N sheets left"), watch progress.

- Spec: `docs/superpowers/specs/2026-07-10-instax-tool-design.md`
- Plan: `docs/superpowers/plans/2026-07-10-instax-tool.md`
- Protocol notes + fixtures: `scripts/instax/README.md`

## How it connects (this milestone)

Tools cannot use Bluetooth: Light's plugin blocks `Context`/`getSystemService`/
`contentResolver` in tool code, so `android.bluetooth` is unusable regardless of
permissions. Instead, the tool speaks its full Instax protocol (pure Kotlin,
fixture-verified) through `TcpBridgeTransport` to a small Mac-side relay:

```
tool (emulator) --TCP 10.0.2.2:47845--> scripts/instax/bridge.py --BLE--> printer
```

The bridge is a dumb pipe — every protocol byte is composed in the tool, so the
dev loop exercises the real code. When Light ships an SDK BLE API, implement
`InstaxTransport` over it and delete nothing.

Photos come from the tool's own `files/photos/` dir (`FilesDirPhotoRepository`)
— the sandbox blocks MediaStore, and there is no Album-read API. The likely
real-device source is Light's dashboard photo sync over HTTPS (future
`DashboardPhotoRepository` behind the same seam).

The tool needs only `android.permission.INTERNET` — it is fully policy-compliant
apart from needing a bridge on the same network.

## Dev loop (zero film)

```bash
# one-time
cd scripts/instax && python3 -m venv .venv && ./.venv/bin/pip install pytest pytest-asyncio bleak

# fake printer (no Bluetooth, no film)
./.venv/bin/python scripts/instax/bridge.py --fake --film 7

# emulator (system-app LightOS setup: docs/system_app/README.md)
./gradlew :tool:installDebug
adb shell am start -n com.thelightphone.instax/com.thelightphone.sdk.LightActivity

# seed photos
adb push photo.jpg /data/local/tmp/
adb shell run-as com.thelightphone.instax sh -c 'mkdir -p files/photos && cp /data/local/tmp/photo.jpg files/photos/'
```

Real printing: run `bridge.py` without `--fake` on a Mac with Bluetooth, printer
switched on. Everything else is identical.

## Tests

- `./gradlew :tool:testDebugUnitTest` — 46 JVM tests: packet codec + message
  parsers byte-for-byte against fixtures cross-generated from javl/InstaxBLE,
  session state machine (cancel, disconnects, timeouts, single-flight),
  TCP transport against an in-process bridge, crop geometry + quality ladder.
- `./.venv/bin/python -m pytest scripts/instax/` — 13 tests: protocol-faithful
  fake printer + the `--fake` TCP server end-to-end.

## Gate results

**G1 (2026-07-11, emulator + fake bridge, zero film): PASSED.**
Browse/preview/print/progress-to-Done; portrait auto-rotate + center-crop
verified visually; prints-remaining decremented after print; cancel mid-transfer
sent `PRINT_IMAGE_DOWNLOAD_CANCEL` and no `PRINT_IMAGE`; killing the bridge
mid-transfer surfaced "Connection lost" with Retry (found and fixed a real
error-mapping bug — see commit `01a8cf2`); `--film 0` showed the out-of-film
copy on Preview; empty photos dir showed "No photos yet.". Bridge log confirmed
the exact packet sequence (START, DATA×N with 4-byte indices, END, PRINT_IMAGE,
post-print status query).

**G2 (real printer, one sheet): PENDING.** Steps: printer on → `bridge.py`
(real mode) → status line must show real battery/prints-remaining (validates
scan/connect/info parsing with zero film cost) → print one photo with the owner
present. Record real-device facts in `scripts/instax/README.md` afterwards.

## Fork-only deviation

One isolated commit (`03f21b4`) adds Bluetooth/media permissions to the plugin
allow-list. The tool itself no longer uses them (see above) — the commit stays
as documentation of the upstream ask. Everything in `tool/` and `scripts/` is
upstream-clean.
