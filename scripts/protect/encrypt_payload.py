#!/usr/bin/env python3
import hashlib
import json
import pathlib
import sys
import zlib

KEY = b"Leo:Shell:V1"
MAGIC = b"LEO1"
PART_COUNT = 3


def derive_key(data: bytes) -> bytes:
    return hashlib.sha256(KEY + hashlib.sha256(data).digest()).digest()


def xor_bytes(data: bytes, key: bytes) -> bytes:
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def split_bytes(data: bytes, part_count: int):
    size = len(data)
    base = size // part_count
    extra = size % part_count
    parts = []
    start = 0
    for i in range(part_count):
        part_size = base + (1 if i < extra else 0)
        end = start + part_size
        parts.append(data[start:end])
        start = end
    return parts


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: encrypt_payload.py <input> <output-dir>", file=sys.stderr)
        return 1
    src = pathlib.Path(sys.argv[1])
    out_dir = pathlib.Path(sys.argv[2])
    raw = src.read_bytes()
    compressed = zlib.compress(raw, level=9)
    key = derive_key(raw)
    encrypted = xor_bytes(compressed, key)
    header = {
        "v": 2,
        "algo": "zlib+xor-sha256",
        "rawSize": len(raw),
        "packedSize": len(compressed),
        "rawSha256": hashlib.sha256(raw).hexdigest(),
    }
    header_bytes = json.dumps(header, separators=(",", ":")).encode("utf-8")
    merged = MAGIC + len(header_bytes).to_bytes(4, "big") + header_bytes + encrypted
    parts = split_bytes(merged, PART_COUNT)

    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "v": 1,
        "stage": "phase8-segmented-payload",
        "partCount": PART_COUNT,
        "totalSize": len(merged),
        "mergedSha256": hashlib.sha256(merged).hexdigest(),
        "parts": []
    }

    for i, chunk in enumerate(parts):
        name = f"seg-{i}.dat"
        path = out_dir / name
        path.write_bytes(chunk)
        manifest["parts"].append({
            "name": name,
            "size": len(chunk),
            "sha256": hashlib.sha256(chunk).hexdigest(),
            "order": i,
        })

    (out_dir / 'index.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f"[payload] encrypted {src} -> {out_dir} ({len(merged)} bytes, raw={len(raw)}, packed={len(compressed)}, parts={PART_COUNT})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
