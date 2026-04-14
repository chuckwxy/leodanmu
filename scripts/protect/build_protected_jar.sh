#!/usr/bin/env bash
set -euo pipefail

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

OUT_NAME="${1:-leodm-protected.jar}"
APKTOOL_PATH="jar/3rd/apktool_2.11.0.jar"
PAYLOAD_WORK_DIR="jar/payload_work"
PAYLOAD_DIR="$PAYLOAD_WORK_DIR/out"
PAYLOAD_JAR="$PAYLOAD_DIR/payload.jar"
PAYLOAD_INDEX="$PAYLOAD_DIR/c.bin"
PAYLOAD_META="$PAYLOAD_DIR/payload.meta.json"
APK_CANDIDATES=(
  "app/build/outputs/apk/protectedRelease/app-protectedRelease-unsigned.apk"
  "app/build/outputs/apk/protectedRelease/app-protectedRelease.apk"
)
WORK_DIR="jar/protected_work"
TEMPLATE_DIR="$WORK_DIR/spider.jar"
SMALI_DIR="$WORK_DIR/Smali_classes"
META_OUT="jar/${OUT_NAME}.meta.txt"
BUILDINFO_OUT="jar/${OUT_NAME}.buildinfo.json"
STAGE_OUT="jar/${OUT_NAME}.stage.txt"
SHA256_OUT="jar/${OUT_NAME}.sha256"
PAYLOAD_SHA256_OUT="jar/${OUT_NAME}.payload.sha256"
PAYLOAD_MANIFEST_OUT="jar/${OUT_NAME}.payload.index.json"
PAYLOAD_PARTS_GLOB="jar/${OUT_NAME}.payload.part*.dat"
PAYLOAD_PART_SHA_GLOB="jar/${OUT_NAME}.payload.part*.sha256"
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

rm -f "jar/${OUT_NAME}" "jar/${OUT_NAME}.md5" "$META_OUT" "$BUILDINFO_OUT" "$STAGE_OUT" "$SHA256_OUT" "$PAYLOAD_SHA256_OUT" "$PAYLOAD_MANIFEST_OUT" $PAYLOAD_PARTS_GLOB $PAYLOAD_PART_SHA_GLOB "$MAP_DST"
rm -rf "$WORK_DIR" "$PAYLOAD_WORK_DIR"
mkdir -p "$WORK_DIR" "$PAYLOAD_DIR"

cp -R jar/spider.jar "$TEMPLATE_DIR"

python3 scripts/protect/build_payload.py "$ROOT_DIR" "$PAYLOAD_DIR"
python3 scripts/protect/encrypt_payload.py "$PAYLOAD_JAR" "$PAYLOAD_DIR"

echo "[protect] using apk: $APK_PATH"
echo "[protect] decompile protected APK main classes"
java -jar "$APKTOOL_PATH" d -f --only-main-classes "$APK_PATH" -o "$SMALI_DIR"

rm -rf "$TEMPLATE_DIR/smali/com/github/catvod/spider"
rm -rf "$TEMPLATE_DIR/smali/com/github/catvod/js"
rm -rf "$TEMPLATE_DIR/smali/com/github/catvod/net"
rm -rf "$TEMPLATE_DIR/smali/org/slf4j"
rm -rf "$TEMPLATE_DIR/lib"
mkdir -p "$TEMPLATE_DIR/smali/com/github/catvod/"
mkdir -p "$TEMPLATE_DIR/smali/org/slf4j/"
mkdir -p "$TEMPLATE_DIR/lib"

[ -d "$SMALI_DIR/smali/com/github/catvod/spider" ] && mv "$SMALI_DIR/smali/com/github/catvod/spider" "$TEMPLATE_DIR/smali/com/github/catvod/"
[ -d "$SMALI_DIR/smali/com/github/catvod/js" ]     && mv "$SMALI_DIR/smali/com/github/catvod/js"     "$TEMPLATE_DIR/smali/com/github/catvod/"
[ -d "$SMALI_DIR/smali/com/github/catvod/net" ]    && mv "$SMALI_DIR/smali/com/github/catvod/net"    "$TEMPLATE_DIR/smali/com/github/catvod/"
[ -d "$SMALI_DIR/smali/org/slf4j" ]                && mv "$SMALI_DIR/smali/org/slf4j"                "$TEMPLATE_DIR/smali/org/"
[ -d "$SMALI_DIR/lib" ] && cp -R "$SMALI_DIR/lib/." "$TEMPLATE_DIR/lib/"

rm -rf "$TEMPLATE_DIR/assets"
mkdir -p "$TEMPLATE_DIR/assets/x"
cp "$PAYLOAD_INDEX" "$TEMPLATE_DIR/assets/x/c.bin"
for part in "$PAYLOAD_DIR"/q*.bin; do
  cp "$part" "$TEMPLATE_DIR/assets/x/$(basename "$part")"
done

echo "[protect] rebuild protected dex.jar"
java -jar "$APKTOOL_PATH" b "$TEMPLATE_DIR" -c
mv "$TEMPLATE_DIR/dist/dex.jar" "jar/${OUT_NAME}"
md5sum "jar/${OUT_NAME}" | awk '{print $1}' > "jar/${OUT_NAME}.md5"
sha256_file "jar/${OUT_NAME}" > "$SHA256_OUT"
PAYLOAD_INDEX_ENV="$PAYLOAD_INDEX" PAYLOAD_SHA256_OUT_ENV="$PAYLOAD_SHA256_OUT" python3 <<'PY'
import hashlib, json, os, pathlib
idx = pathlib.Path(os.environ['PAYLOAD_INDEX_ENV'])
out = pathlib.Path(os.environ['PAYLOAD_SHA256_OUT_ENV'])
manifest = json.loads(idx.read_text())
merged = b''
base = idx.parent
for part in manifest['p']:
    merged += (base / part['n']).read_bytes()
out.write_text(hashlib.sha256(merged).hexdigest() + '\n', encoding='utf-8')
PY
cp "$PAYLOAD_INDEX" "$PAYLOAD_MANIFEST_OUT"
part_num=0
for part in "$PAYLOAD_DIR"/q*.bin; do
  cp "$part" "jar/${OUT_NAME}.payload.part${part_num}.dat"
  sha256_file "$part" > "jar/${OUT_NAME}.payload.part${part_num}.sha256"
  part_num=$((part_num + 1))
done

{
  echo "name=${OUT_NAME}"
  echo "stage=v3-native-full"
  echo "apk_path=${APK_PATH}"
  echo "mapping_saved=$( [ -f "$MAP_SRC" ] && echo yes || echo no )"
  echo "payload_index=$(basename "$PAYLOAD_INDEX")"
  echo "payload_meta=$(basename "$PAYLOAD_META")"
  echo "payload_sha256=$(cat "$PAYLOAD_SHA256_OUT")"
  echo "jar_sha256=$(cat "$SHA256_OUT")"
  echo "built_at=$(date -u +%FT%TZ)"
} > "$META_OUT"

cat > "$BUILDINFO_OUT" <<EOF
{
  "name": "${OUT_NAME}",
  "stage": "v3-native-full",
  "apkPath": "${APK_PATH}",
  "builtAt": "$(date -u +%FT%TZ)",
  "jarSha256": "$(cat "$SHA256_OUT")",
  "payloadSha256": "$(cat "$PAYLOAD_SHA256_OUT")",
  "mappingSaved": "$( [ -f "$MAP_SRC" ] && echo yes || echo no )",
  "payloadMeta": "$(basename "$PAYLOAD_META")",
  "payloadIndex": "$(basename "$PAYLOAD_INDEX")",
  "payloadPartCount": "$(PAYLOAD_INDEX_ENV="$PAYLOAD_INDEX" python3 - <<'PY'
import json, os
print(len(json.load(open(os.environ['PAYLOAD_INDEX_ENV']))['p']))
PY
)"
}
EOF

echo "v3-native-full" > "$STAGE_OUT"

if [ -f "$MAP_SRC" ]; then
  cp "$MAP_SRC" "$MAP_DST"
fi

cp "$PAYLOAD_META" "jar/${OUT_NAME}.payload.meta.json"

rm -rf "$WORK_DIR" "$PAYLOAD_WORK_DIR"

echo "[protect] generated jar/${OUT_NAME}"
ls -lh "jar/${OUT_NAME}"
ls -lh "$PAYLOAD_MANIFEST_OUT" "jar/${OUT_NAME}.payload.meta.json" jar/${OUT_NAME}.payload.part*.dat

echo "[protect] md5: $(cat "jar/${OUT_NAME}.md5")"
echo "[protect] sha256: $(cat "$SHA256_OUT")"
echo "[protect] payload sha256: $(cat "$PAYLOAD_SHA256_OUT")"
