#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

PYTHON_BIN="${PYTHON_BIN:-python3}"
BUILD_DIR="$ROOT_DIR/build"
DIST_DIR="$ROOT_DIR/dist"
RELEASE_DIR="$ROOT_DIR/release"
STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/birdstools-dmg.XXXXXX")"
trap 'rm -rf "$STAGING_DIR"' EXIT

echo "Installing build dependencies..."
"$PYTHON_BIN" -m pip install -r requirements.txt pyinstaller

rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$RELEASE_DIR"

echo "Building macOS application..."
"$PYTHON_BIN" -m PyInstaller --clean --noconfirm ToolBox-macos.spec

APP_PATH="$DIST_DIR/鸟友工具箱.app"
if [ ! -d "$APP_PATH" ]; then
  echo "Build failed: $APP_PATH not found" >&2
  exit 1
fi

echo "Creating DMG installer..."
ln -s /Applications "$STAGING_DIR/Applications"
cp -R "$APP_PATH" "$STAGING_DIR/"
rm -f "$RELEASE_DIR/鸟友工具箱-macOS.dmg"
hdiutil create \
  -volname "鸟友工具箱" \
  -srcfolder "$STAGING_DIR" \
  -ov \
  -format UDZO \
  "$RELEASE_DIR/鸟友工具箱-macOS.dmg"

echo "Created: $RELEASE_DIR/鸟友工具箱-macOS.dmg"
