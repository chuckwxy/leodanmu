#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

OUT_NAME="${1:-leodm-protected.jar}"
APK_PATH="app/build/outputs/apk/protectedRelease/app-protectedRelease-unsigned.apk"
APKTOOL_PATH="jar/3rd/apktool_2.11.0.jar"
WORK_DIR="jar/protected_work"
TEMPLATE_DIR="$WORK_DIR/spider.jar"
SMALI_DIR="$WORK_DIR/Smali_classes"
META_OUT="jar/${OUT_NAME}.meta.txt"
MAP_SRC="app/build/outputs/mapping/protectedRelease/mapping.txt"
MAP_DST="jar/${OUT_NAME}.mapping.txt"

rm -f "jar/${OUT_NAME}" "jar/${OUT_NAME}.md5" "$META_OUT" "$MAP_DST"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

cp -R jar/spider.jar "$TEMPLATE_DIR"

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

# Remove plain assets from template to keep output minimal.
rm -rf "$TEMPLATE_DIR/assets"

echo "[protect] rebuild protected dex.jar"
java -jar "$APKTOOL_PATH" b "$TEMPLATE_DIR" -c
mv "$TEMPLATE_DIR/dist/dex.jar" "jar/${OUT_NAME}"
md5sum "jar/${OUT_NAME}" | awk '{print $1}' > "jar/${OUT_NAME}.md5"

{
  echo "name=${OUT_NAME}"
  echo "apk_path=${APK_PATH}"
  echo "mapping_saved=$( [ -f "$MAP_SRC" ] && echo yes || echo no )"
  echo "built_at=$(date -u +%FT%TZ)"
} > "$META_OUT"

if [ -f "$MAP_SRC" ]; then
  cp "$MAP_SRC" "$MAP_DST"
fi

rm -rf "$WORK_DIR"

echo "[protect] generated jar/${OUT_NAME}"
ls -lh "jar/${OUT_NAME}"
echo "[protect] md5: $(cat "jar/${OUT_NAME}.md5")"
