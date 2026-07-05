#!/usr/bin/env bash
# Clones Tesla's open-source Vehicle Command Protocol reference at the commit
# pinned in pin.txt into ./upstream (gitignored). Idempotent: safe to re-run,
# always produces a clean checkout at exactly the pinned commit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

REPO_URL="https://github.com/teslamotors/vehicle-command.git"
PIN="$(tr -d '[:space:]' < pin.txt)"
DEST="upstream"

if [ -z "$PIN" ]; then
  echo "pin.txt is empty; expected a commit hash" >&2
  exit 1
fi

if [ -d "$DEST/.git" ]; then
  CURRENT="$(git -C "$DEST" rev-parse HEAD 2>/dev/null || true)"
  if [ "$CURRENT" = "$PIN" ]; then
    echo "upstream already at pinned commit $PIN"
    exit 0
  fi
  echo "upstream present but at $CURRENT, expected $PIN — re-cloning"
  rm -rf "$DEST"
fi

rm -rf "$DEST"
git clone --quiet "$REPO_URL" "$DEST"
git -C "$DEST" checkout --quiet "$PIN"

echo "vendored teslamotors/vehicle-command @ $(git -C "$DEST" rev-parse HEAD)"
