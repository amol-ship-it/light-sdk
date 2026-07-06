# Owner-side setup scripts

Dev-machine scripts for the person who owns the Tesla fleet application and is
setting up the Light Phone tool for the first time (or re-provisioning it).
These run on your Mac/PC, **not** on the phone or in the tool — the SDK's
API/dependency restrictions (no third-party libs, size limits, etc.) do not
apply here.

## Prerequisites

- `openssl` (ships with macOS/most Linux)
- `python3` — **stdlib only**, no `pip install` needed (no `requests`, nothing)
- `qrencode` — `brew install qrencode` (expected at `/opt/homebrew/bin/qrencode`
  on Apple Silicon Homebrew; `login.py` also checks `PATH`)
- A Tesla Fleet API application already created in the
  [Tesla developer portal](https://developer.tesla.com/) (gives you
  `CLIENT_ID` / `CLIENT_SECRET`)
- A domain you control that can serve a static file over HTTPS (GitHub Pages
  is the easy option). This is your **key domain** — do not confuse it with
  the tool or the vehicle.

Go is **not** needed for any of this — that's only used by the separate
`scripts/tesla/vcp-fixtures/` test-fixture generator.

## Run order

1. **`./keygen.sh`** — generates `private-key.pem` / `public-key.pem` (EC
   prime256v1) in this directory. `private-key.pem` is chmod 600 and must
   never be committed or shared; `public-key.pem` is meant to be published.
   Re-running warns before overwriting an existing private key (overwriting
   invalidates anything already registered/encoded with the old key).

2. **Publish the public key** — commit `public-key.pem` to your GitHub Pages
   repo (or any HTTPS host) at exactly this path:
   ```
   https://<your-domain>/.well-known/appspecific/com.tesla.3p.public-key.pem
   ```
   This is the fixed path Tesla vehicles fetch to verify your fleet key.

3. **`./register.sh`** — registers your domain with Tesla so vehicles trust
   it. Reads from the environment:
   | Var | Required | Notes |
   |---|---|---|
   | `CLIENT_ID` | yes | from the Tesla developer portal |
   | `CLIENT_SECRET` | yes | from the Tesla developer portal |
   | `DOMAIN` | yes | the same domain from step 2 (no scheme, e.g. `example.github.io`) |
   | `REGION` | no (default `na`) | `na` or `eu` — must match where your vehicle is registered |

   Example:
   ```
   CLIENT_ID=... CLIENT_SECRET=... DOMAIN=example.github.io REGION=na ./register.sh
   ```
   Gets a partner (client-credentials) token, then `POST`s
   `{"domain": DOMAIN}` to `/api/1/partner_accounts`. Idempotent — re-running
   with the same domain just returns the existing registration, so it's safe
   to run again if you're unsure whether it already succeeded.

4. **`./login.py`** — runs the OAuth PKCE login as the vehicle owner and
   produces the QR code(s) the tool scans to complete setup.
   ```
   ./login.py --region na --client-id "$CLIENT_ID" --domain example.github.io \
       [--client-secret "$CLIENT_SECRET"] [--private-key ./private-key.pem]
   ```
   | Flag | Required | Notes |
   |---|---|---|
   | `--region` | yes | `na` or `eu`; embedded in the payload |
   | `--client-id` | yes | |
   | `--client-secret` | no | omit if your app uses PKCE-only (public client) |
   | `--domain` | yes | your key domain (same as step 2/3) |
   | `--private-key` | no (default `./private-key.pem`) | path to the EC private key from step 1 |

   What it does:
   - Opens your browser to `auth.tesla.com`, catches the redirect on
     `localhost:8085`, exchanges the code for tokens via PKCE.
   - Builds the setup payload (refresh token, client id/secret, region,
     domain, private key PEM) as JSON, then encodes it exactly the way the
     tool's Kotlin decoder expects: **raw deflate (zlib, no header) →
     base64url, no padding**.
   - If the encoded string is short enough, renders **one QR code**
     (`qrencode -t ANSIUTF8`) directly to the terminal. If it's too long, it
     splits the string into `LTP/<i>/<n>/<part>` frames and shows them one at
     a time (press ENTER to advance) — the tool can scan them in any order
     and reassembles once it has all `n`.
   - **The assembled payload (which contains your refresh token and private
     key) is never written to disk.** It exists only in memory and as
     terminal QR output.

   `--selftest` encodes a fixed, deterministic dummy payload and prints
   *only* the encoded string to stdout — no OAuth, no network, no QR. This
   exists purely so the encoder can be tested against the Kotlin decoder
   (`SetupPayloadTest.kt`) without touching real credentials; you shouldn't
   need it for normal setup.

## One-time virtual-key enrollment

After scanning the QR into the tool, the vehicle still needs to trust the
key. In the **official Tesla app**, open (on the phone that has the Tesla
app, this can be the same Light Phone or any phone with the app):
```
https://tesla.com/_ak/<your-domain>
```
and approve the virtual key request. This is a one-time step per vehicle.

## Troubleshooting

- **"Could not bind localhost:8085"** — something else is using port 8085.
  Find and stop it (`lsof -i :8085`), then re-run `login.py`.
- **Browser doesn't open** — `login.py` also prints the auth URL to stderr;
  copy/paste it into any browser manually, log in, and it'll still redirect
  to `localhost:8085` to complete the flow on your machine.
- **`register.sh` succeeds but the tool's verify-key step still fails** —
  double check `public-key.pem` is actually served at
  `/.well-known/appspecific/com.tesla.3p.public-key.pem` over HTTPS (not
  behind auth, not 404), and that you did the app-side enrollment step above.
- **Region mismatch** — `REGION`/`--region` must match the region your
  vehicle/account is actually in (`na` vs `eu`). Using the wrong one causes
  opaque 401s from the Fleet API even with otherwise-correct credentials.
- **`qrencode: command not found`** — `brew install qrencode`, or confirm it
  exists at `/opt/homebrew/bin/qrencode`.
