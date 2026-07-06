# Tesla tool for LightOS

A [Light Phone III](https://www.thelightphone.com/) tool to check your Tesla and issue a
small, deliberate set of commands: lock/unlock, start/stop charging, charge limit & amps,
climate on/off & target temperature, cabin overheat protection, dog mode, and window
vent/close.

Commands go **directly against Tesla's official Fleet API** — no companion server, no
third party, no background polling. Each command is signed **on-device**, in pure
Kotlin, using Tesla's Vehicle Command Protocol (HMAC-SHA256 over the Fleet API; see
[Design notes](#design-notes) below). Tested against a 2023 Model 3.

**Out of scope:** audio/video calls, phone-as-key (BLE is blocked by the light-sdk's
permission allow-list).

## One-time setup ceremony

This is the part that matters — do these in order. The scripts live in
[`scripts/tesla/setup/`](../scripts/tesla/setup/README.md); this section is the
end-to-end narrative, that README has the exact flags/troubleshooting.

1. **Create a Tesla developer account and register an app** at
   [developer.tesla.com](https://developer.tesla.com/). When registering:
   - Grant type: **Authorization Code and Machine-to-Machine**
   - Allowed redirect URI: `http://localhost:8085/callback`
   - Scopes: `openid`, `offline_access`, `vehicle_device_data`, `vehicle_cmds`,
     `vehicle_charging_cmds`

   Note the **client id + client secret**, and decide what **domain** you'll host your
   key on (see next step).

2. **Generate your signing keypair:**
   ```
   cd scripts/tesla/setup
   ./keygen.sh
   ```
   Produces `private-key.pem` (never leaves your machine) and `public-key.pem`.

3. **Host `public-key.pem`** at exactly this path over HTTPS:
   ```
   https://<your-domain>/.well-known/appspecific/com.tesla.3p.public-key.pem
   ```
   A `<user>.github.io` GitHub Pages repo works well for this.

4. **Register your domain as a Fleet API partner:**
   ```
   CLIENT_ID=... CLIENT_SECRET=... DOMAIN=example.github.io REGION=na ./register.sh
   ```
   This proves to Tesla that you control the domain hosting your public key.

5. **Log in and generate the setup QR:**
   ```
   ./login.py --region na --client-id "$CLIENT_ID" --domain example.github.io \
       [--client-secret "$CLIENT_SECRET"]
   ```
   Opens your browser for Tesla sign-in, then renders a QR code in your terminal
   (deflate+base64url encoded; splits into multiple parts if the payload is large).
   This QR carries your refresh token and private key — see
   [Security posture](#security-posture).

6. **On the Light Phone:** open the **Tesla** tool → **Set up** → scan the QR
   (all parts, if multi-part) → pick your vehicle from the list.

7. **Enroll the virtual key.** In the **official Tesla app**, on any phone signed in to
   your Tesla account, open:
   ```
   https://tesla.com/_ak/<your-domain>
   ```
   and approve the request. This is what makes the car trust commands signed by your
   key — until this is done, the car rejects even correctly-signed commands.

8. **Back in the tool, tap "Verify key."** It should report **"Key enrolled ✓."** If it
   instead shows the `tesla.com/_ak/...` link again, step 7 hasn't been completed yet.

## Running without a car (emulator demo)

Flip the constant in `Graph.kt`:

```kotlin
const val USE_FAKE = true
```

This runs the entire UI against an in-memory fake vehicle repository — no Tesla
credentials, no setup ceremony, no network calls. Useful for walking through the UX on
an emulator. It's a one-line edit; flip it back to `false` before using a real vehicle.

## Design notes

**No polling.** Opening the dashboard makes exactly one Fleet API call; refresh is
manual. Waking a sleeping car polls the cheap vehicle-list endpoint, not the billed
`vehicle_data` endpoint. This keeps personal use comfortably inside Tesla's free monthly
Fleet API credit. **Keep it that way** — any future feature that adds polling needs to
justify itself against this budget.

## Security posture

The setup QR and your `private-key.pem` carry real secrets: a Tesla refresh token that
can control your car, and the private key used to sign commands.

- The private key lives only (a) as `private-key.pem` on your dev machine, and (b) in
  the tool's app-private storage after you scan the QR. It is never committed to the
  repo and never transmitted to anyone but Tesla.
- Scan the setup QR only on your own device.

**Rotating the key**, if you ever need to:
1. Re-run `keygen.sh`.
2. Re-host the new `public-key.pem`.
3. Re-approve the virtual key at `tesla.com/_ak/<domain>`.
4. Re-run `login.py`.
5. Re-scan the new QR in the tool.

Tesla also rotates your refresh token on every use — that's normal and handled
automatically by the tool.

## Build / install

Needs the repo's GitHub Packages token (see the [root README](../README.md#quickstart))
and Android Studio's SDK. Then:

```
./gradlew :tool:installDebug
```

Launch component: `com.amolpurohit.tesla/com.thelightphone.sdk.LightActivity`.

On a real LP3 this installs like any other SDK tool. To exercise the real SDK server
(push, permissions, etc.) on an emulator, see
[`docs/system_app`](../docs/system_app/README.md) for running the LightOS emulator as a
system app.
