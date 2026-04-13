#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

OUT_NAME="${1:-leodm-protected.jar}"
APKTOOL_PATH="jar/3rd/apktool_2.11.0.jar"
PAYLOAD_WORK_DIR="jar/payload_work"
PAYLOAD_DIR="$PAYLOAD_WORK_DIR/out"
PAYLOAD_JAR="$PAYLOAD_DIR/payload.jar"
PAYLOAD_BIN="$PAYLOAD_DIR/payload.bin"
PAYLOAD_META="$PAYLOAD_DIR/payload.meta.json"
APK_CANDIDATES=(
  "app/build/outputs/apk/protectedRelease/app-protectedRelease-unsigned.apk"
  "app/build/outputs/apk/protectedRelease/app-protectedRelease.apk"
)
WORK_DIR="jar/protected_work"
TEMPLATE_DIR="$WORK_DIR/spider.jar"
SMALI_DIR="$WORK_DIR/Smali_classes"
META_OUT="jar/${OUT_NAME}.meta.txt"
MAP_SRC="app/build/outputs/mapping/protectedRelease/mapping.txt"
MAP_DST="jar/${OUT_NAME}.mapping.txt"

APK_PATH=""
for candidate in "${APK_CANDIDATES[@]}"; do
  if [ -f "$candidate" ]; then
    APK_PATH="$candidate"
    break
  fi
done

if [ -z "$APK_PATH" ]; then
  echo "[protect] protected APK not found. looked for: ${APK_CANDIDATES[*]}" >&2
  exit 1
fi

rm -f "jar/${OUT_NAME}" "jar/${OUT_NAME}.md5" "$META_OUT" "$MAP_DST"
rm -rf "$WORK_DIR" "$PAYLOAD_WORK_DIR"
mkdir -p "$WORK_DIR" "$PAYLOAD_DIR"

cp -R jar/spider.jar "$TEMPLATE_DIR"

python3 scripts/protect/build_payload.py "$ROOT_DIR" "$PAYLOAD_DIR"
python3 scripts/protect/encrypt_payload.py "$PAYLOAD_JAR" "$PAYLOAD_BIN"

echo "[protect] using apk: $APK_PATH"
echo "[protect] decompile protected APK main classes"
java -jar "$APKTOOL_PATH" d -f --only-main-classes "$APK_PATH" -o "$SMALI_DIR"

rm -rf "$TEMPLATE_DIR/smali/com/github/catvod/spider"
rm -rf "$TEMPLATE_DIR/smali/com/github/catvod/js"
rm -rf "$TEMPLATE_DIR/smali/com/github/catvod/net"
rm -rf "$TEMPLATE_DIR/smali/org/slf4j"
mkdir -p "$TEMPLATE_DIR/smali/com/github/catvod/"
mkdir -p "$TEMPLATE_DIR/smali/org/slf4j/"

[ -d "$SMALI_DIR/smali/com/github/catvod/spider" ] && mv "$SMALI_DIR/smali/com/github/catvod/spider" "$TEMPLATE_DIR/smali/com/github/catvod/"
[ -d "$SMALI_DIR/smali/com/github/catvod/js" ]     && mv "$SMALI_DIR/smali/com/github/catvod/js"     "$TEMPLATE_DIR/smali/com/github/catvod/"
[ -d "$SMALI_DIR/smali/com/github/catvod/net" ]    && mv "$SMALI_DIR/smali/com/github/catvod/net"    "$TEMPLATE_DIR/smali/com/github/catvod/"
[ -d "$SMALI_DIR/smali/org/slf4j" ]                && mv "$SMALI_DIR/smali/org/slf4j"                "$TEMPLATE_DIR/smali/org/"

# Replace plain assets with shell payload asset.
rm -rf "$TEMPLATE_DIR/assets"
mkdir -p "$TEMPLATE_DIR/assets/payload"
cp "$PAYLOAD_BIN" "$TEMPLATE_DIR/assets/payload/payload.bin"
cp "$PAYLOAD_META" "$TEMPLATE_DIR/assets/payload/payload.meta.json"

echo "[protect] rebuild protected dex.jar"
java -jar "$APKTOOL_PATH" b "$TEMPLATE_DIR" -c
mv "$TEMPLATE_DIR/dist/dex.jar" "jar/${OUT_NAME}"
md5sum "jar/${OUT_NAME}" | awk '{print $1}' > "jar/${OUT_NAME}.md5"

{
  echo "name=${OUT_NAME}"
  echo "apk_path=${APK_PATH}"
  echo "mapping_saved=$( [ -f "$MAP_SRC" ] && echo yes || echo no )"
  echo "payload_bin=$(basename "$PAYLOAD_BIN")"
  echo "payload_meta=$(basename "$PAYLOAD_META")"
  echo "built_at=$(date -u +%FT%TZ)"
} > "$META_OUT"

if [ -f "$MAP_SRC" ]; then
  cp "$MAP_SRC" "$MAP_DST"
fi

cp "$PAYLOAD_BIN" "jar/${OUT_NAME}.payload.bin"
cp "$PAYLOAD_META" "jar/${OUT_NAME}.payload.meta.json"

rm -rf "$WORK_DIR" "$PAYLOAD_WORK_DIR"

echo "[protect] generated jar/${OUT_NAME}"
ls -lh "jar/${OUT_NAME}"
ls -lh "jar/${OUT_NAME}.payload.bin" "jar/${OUT_NAME}.payload.meta.json"
echo "[protect] md5: $(cat "jar/${OUT_NAME}.md5")"
