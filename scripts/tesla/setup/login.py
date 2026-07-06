#!/usr/bin/env python3
"""Owner-side Tesla OAuth PKCE login + setup-QR generator (Task 27).

Runs the OAuth PKCE flow against Tesla's auth server, exchanges the
authorization code for a refresh token, then builds the setup payload the
tool's Kotlin QR scanner (SetupPayload.kt) expects: JSON -> raw deflate
(zlib, nowrap) -> base64url (no padding). Payloads that don't fit in one QR
are split into `LTP/<i>/<n>/<part>` frames, scannable in any order.

SECURITY: the assembled payload contains the refresh token (and the private
key text). It is NEVER written to disk — only rendered as QR code(s) to the
terminal via `qrencode -t ANSIUTF8`.

stdlib only. No pip/requests dependency.
"""
import argparse
import base64
import hashlib
import http.server
import json
import os
import secrets
import shutil
import subprocess
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import zlib

REDIRECT_URI = "http://localhost:8085/callback"
AUTH_URL = "https://auth.tesla.com/oauth2/v3/authorize"
TOKEN_URL = "https://auth.tesla.com/oauth2/v3/token"
SCOPES = "openid offline_access vehicle_device_data vehicle_cmds vehicle_charging_cmds"

# Kotlin decoder caps (SetupPayload.kt): reject well before these.
MAX_SCAN_LENGTH = 8192
QR_SINGLE_CHUNK_LIMIT = 1200  # conservative single-QR threshold (ANSI terminal QR readability)
QR_MULTI_CHUNK_SIZE = 1100  # data bytes per part when splitting (frame header adds a few bytes)


def log(msg):
    print(msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------------------
# Encoding: MUST match SetupPayload.kt's decode() exactly.
# ---------------------------------------------------------------------------

def encode_payload(payload: dict) -> str:
    """JSON -> raw deflate (nowrap, level 9) -> base64url, no padding."""
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)  # -15 = raw deflate, no zlib header
    deflated = compressor.compress(body) + compressor.flush()
    return base64.urlsafe_b64encode(deflated).rstrip(b"=").decode("ascii")


def build_qr_frames(encoded: str) -> list:
    """Return a list of strings to render as QR codes, one per frame."""
    if len(encoded) <= QR_SINGLE_CHUNK_LIMIT:
        return [encoded]
    n = -(-len(encoded) // QR_MULTI_CHUNK_SIZE)  # ceil div
    frames = []
    for i in range(n):
        chunk = encoded[i * QR_MULTI_CHUNK_SIZE : (i + 1) * QR_MULTI_CHUNK_SIZE]
        frame = "LTP/%d/%d/%s" % (i + 1, n, chunk)
        if len(frame) > MAX_SCAN_LENGTH:
            raise SystemExit(
                "internal error: QR frame %d exceeds decoder's max scan length "
                "(%d > %d) — reduce QR_MULTI_CHUNK_SIZE" % (i + 1, len(frame), MAX_SCAN_LENGTH)
            )
        frames.append(frame)
    return frames


def render_qr(data: str):
    qrencode = shutil.which("qrencode") or "/opt/homebrew/bin/qrencode"
    if not os.path.exists(qrencode) and shutil.which("qrencode") is None:
        raise SystemExit(
            "qrencode not found. Install it (e.g. `brew install qrencode`) "
            "or ensure /opt/homebrew/bin/qrencode exists."
        )
    subprocess.run([qrencode, "-t", "ANSIUTF8", "-o", "-", data], check=True)


def show_qr_frames(frames: list):
    total = len(frames)
    if total == 1:
        log("Rendering setup QR (single code) — scan it in the tool's setup screen:")
        render_qr(frames[0])
        return
    log(
        "Payload too large for one QR; splitting into %d parts. Scan them in any "
        "order (the tool accumulates parts until all %d are seen)." % (total, total)
    )
    for i, frame in enumerate(frames, start=1):
        log("\n--- Part %d of %d --- (press ENTER to show the next part)" % (i, total))
        render_qr(frame)
        if i < total:
            try:
                input()
            except EOFError:
                pass
    log("\nAll %d parts shown." % total)


# ---------------------------------------------------------------------------
# OAuth PKCE flow (mirrors the proven-working gate prototype).
# ---------------------------------------------------------------------------

def run_oauth_flow(client_id: str, client_secret: str | None) -> str:
    """Runs the PKCE flow; returns the refresh_token."""
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(64)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)

    auth_url = AUTH_URL + "?" + urllib.parse.urlencode({
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": REDIRECT_URI,
        "scope": SCOPES,
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    })

    result = {}
    done = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != "/callback":
                self.send_response(404)
                self.end_headers()
                return
            q = urllib.parse.parse_qs(parsed.query)
            if "error" in q:
                result["error"] = q.get("error_description", q.get("error"))[0]
                self.send_response(400)
                self.send_header("Content-Type", "text/html")
                self.end_headers()
                self.wfile.write(b"<h2>Login failed &mdash; return to the terminal.</h2>")
                done.set()
                return
            if q.get("state", [""])[0] != state:
                result["error"] = "state mismatch (possible CSRF/replay)"
                self.send_response(400)
                self.send_header("Content-Type", "text/html")
                self.end_headers()
                self.wfile.write(b"<h2>State mismatch &mdash; return to the terminal.</h2>")
                done.set()
                return
            result["code"] = q.get("code", [""])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(b"<h2>Login captured \xe2\x9c\x93 \xe2\x80\x94 return to the terminal.</h2>")
            done.set()

        def log_message(self, *a):
            pass

    try:
        server = http.server.HTTPServer(("localhost", 8085), Handler)
    except OSError as e:
        raise SystemExit(
            "Could not bind localhost:8085 (%s). Is another process using that port? "
            "Free it and re-run, or adjust REDIRECT_URI (must also match your Tesla "
            "app registration if you change it)." % e
        )

    threading.Thread(target=server.serve_forever, daemon=True).start()

    log("Opening browser for Tesla login. If it doesn't open automatically, paste this URL:")
    log(auth_url)
    try:
        webbrowser.open(auth_url)
    except Exception:
        pass

    if not done.wait(timeout=600):
        server.shutdown()
        raise SystemExit("TIMEOUT: no callback received within 10 minutes")
    server.shutdown()

    if "error" in result:
        raise SystemExit("OAuth error from Tesla: %s" % result["error"])
    if not result.get("code"):
        raise SystemExit("No authorization code received in callback")

    body = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "client_id": client_id,
        **({"client_secret": client_secret} if client_secret else {}),
        "code": result["code"],
        "code_verifier": verifier,
        "redirect_uri": REDIRECT_URI,
    }).encode()
    req = urllib.request.Request(
        TOKEN_URL, data=body, headers={"Content-Type": "application/x-www-form-urlencoded"}
    )
    try:
        with urllib.request.urlopen(req) as r:
            tok = json.load(r)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        raise SystemExit("Token exchange failed (HTTP %d): %s" % (e.code, detail))

    rt = tok.get("refresh_token")
    if not rt:
        safe = {k: v for k, v in tok.items() if k != "access_token"}
        raise SystemExit("ERROR: no refresh_token in token response: %s" % json.dumps(safe))
    return rt


# ---------------------------------------------------------------------------
# Selftest: deterministic canned payload, encoder-only, no OAuth/QR/network.
# ---------------------------------------------------------------------------

CANNED_SELFTEST_PAYLOAD = {
    "v": 1,
    "refresh_token": "selftest-refresh-token-0000",
    "client_id": "selftest-client-id",
    "client_secret": "selftest-client-secret",
    "region": "na",
    "domain": "selftest.example.com",
    "private_key": (
        "-----BEGIN EC PRIVATE KEY-----\n"
        "MHcCAQEEIBSELFTESTFAKEKEYMATERIALNOTREALDONOTUSEXXXXXXoAoGCCqGSM49\n"
        "AwEHoUQDQgAEfakepublickeymaterialforselftestonlynotarealkeyfakefake\n"
        "fakefakefakefakefakefakefakefakefakefakefakefakefakefake==\n"
        "-----END EC PRIVATE KEY-----\n"
    ),
}


def run_selftest():
    encoded = encode_payload(CANNED_SELFTEST_PAYLOAD)
    print(encoded)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args(argv):
    p = argparse.ArgumentParser(
        description="Tesla owner-side OAuth login + setup QR generator."
    )
    p.add_argument("--selftest", action="store_true",
                    help="Encode a fixed canned payload and print only the encoded "
                         "string (no OAuth, no QR, no network). Used for the "
                         "cross-implementation test against the Kotlin decoder.")
    p.add_argument("--region", choices=["na", "eu"],
                    help="Fleet API region; embedded in the payload. Required unless --selftest.")
    p.add_argument("--client-id", help="Tesla fleet app client ID. Required unless --selftest.")
    p.add_argument("--client-secret", default=None,
                    help="Tesla fleet app client secret (optional; omit if your app uses PKCE-only).")
    p.add_argument("--domain", help="Domain hosting your public key (the one you registered). "
                                     "Required unless --selftest.")
    p.add_argument("--private-key", default="./private-key.pem",
                    help="Path to the EC private key PEM (default: ./private-key.pem).")
    args = p.parse_args(argv)

    if not args.selftest:
        missing = [name for name, val in
                   (("--region", args.region), ("--client-id", args.client_id), ("--domain", args.domain))
                   if not val]
        if missing:
            p.error("the following arguments are required: %s" % ", ".join(missing))
    return args


def main(argv=None):
    args = parse_args(argv if argv is not None else sys.argv[1:])

    if args.selftest:
        run_selftest()
        return

    if not os.path.isfile(args.private_key):
        raise SystemExit(
            "Private key not found at %s. Run keygen.sh first, or pass --private-key." % args.private_key
        )
    with open(args.private_key, "r") as f:
        private_key_pem = f.read()
    if "PRIVATE KEY" not in private_key_pem:
        raise SystemExit("%s does not look like a PEM private key" % args.private_key)

    log("Starting Tesla OAuth PKCE flow (region=%s, domain=%s) ..." % (args.region, args.domain))
    refresh_token = run_oauth_flow(args.client_id, args.client_secret)
    log("Login successful; refresh token obtained (not printed).")

    payload = {
        "v": 1,
        "refresh_token": refresh_token,
        "client_id": args.client_id,
        "region": args.region,
        "domain": args.domain,
        "private_key": private_key_pem,
    }
    if args.client_secret:
        payload["client_secret"] = args.client_secret

    encoded = encode_payload(payload)
    log("Payload encoded (%d chars). This is NEVER written to disk — QR only." % len(encoded))

    frames = build_qr_frames(encoded)
    show_qr_frames(frames)


if __name__ == "__main__":
    main()
