#!/usr/bin/env python3
import pathlib
import sys

KEY = b"Leo:Shell:V1"


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: encrypt_payload.py <input> <output>", file=sys.stderr)
        return 1
    src = pathlib.Path(sys.argv[1])
    dst = pathlib.Path(sys.argv[2])
    data = src.read_bytes()
    out = bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(data))
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(out)
    print(f"[payload] encrypted {src} -> {dst} ({len(out)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
