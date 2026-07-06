#!/usr/bin/env bash
# Generates the EC (prime256v1) keypair Tesla's Fleet API requires for
# vehicle-command signing. Run this once per fleet application. The private
# key is embedded (via login.py) into the QR payload scanned by the tool; the
# public key must be hosted at a well-known path on your registered domain.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PRIVATE_KEY="private-key.pem"
PUBLIC_KEY="public-key.pem"

if [ -f "$PRIVATE_KEY" ]; then
  echo "WARNING: $PRIVATE_KEY already exists in $SCRIPT_DIR" >&2
  read -r -p "Overwrite it? This invalidates any QR payload built from the old key. [y/N] " reply
  case "$reply" in
    [yY]|[yY][eE][sS]) ;;
    *) echo "Aborting; existing key left untouched." >&2; exit 1 ;;
  esac
fi

openssl ecparam -name prime256v1 -genkey -noout -out "$PRIVATE_KEY"
chmod 600 "$PRIVATE_KEY"
openssl ec -in "$PRIVATE_KEY" -pubout -out "$PUBLIC_KEY" 2>/dev/null

echo
echo "Generated:"
echo "  $SCRIPT_DIR/$PRIVATE_KEY  (chmod 600 — keep secret; feed to login.py --private-key, never commit)"
echo "  $SCRIPT_DIR/$PUBLIC_KEY   (public — host it, do not keep it secret)"
echo
echo "Next step: publish the PUBLIC key on your domain at exactly this path:"
echo "  https://<your-domain>/.well-known/appspecific/com.tesla.3p.public-key.pem"
echo "(e.g. via GitHub Pages: commit $PUBLIC_KEY to your Pages repo at"
echo " .well-known/appspecific/com.tesla.3p.public-key.pem and push)."
echo
echo "Then run register.sh with DOMAIN set to that same domain."
