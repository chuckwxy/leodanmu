#!/usr/bin/env python3
import hashlib
import json
import pathlib
import sys
import zlib

KEY = b"Leo:Shell:V1"
MAGIC = b"LEO1"


def derive_key(data: bytes) -> bytes:
    return hashlib.sha256(KEY + hashlib.sha256(data).digest()).digest()


def xor_bytes(data: bytes, key: bytes) -> bytes:
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: encrypt_payload.py <input> <output>", file=sys.stderr)
        return 1
    src = pathlib.Path(sys.argv[1])
    dst = pathlib.Path(sys.argv[2])
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
    out = MAGIC + len(header_bytes).to_bytes(4, "big") + header_bytes + encrypted
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(out)
    print(f"[payload] encrypted {src} -> {dst} ({len(out)} bytes, raw={len(raw)}, packed={len(compressed)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
