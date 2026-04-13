#!/usr/bin/env python3
import json
import pathlib
import shutil
import sys
import zipfile

PAYLOAD_SOURCES = [
    "app/src/main/java/com/github/catvod/spider/protect/RealLeodanmu.java",
    "app/src/main/java/com/github/catvod/spider/protect/PayloadBridge.java",
    "app/src/main/java/com/github/catvod/spider/protect/ProtectedLoader.java",
    "app/src/main/java/com/github/catvod/spider/LeoDanmakuService.java",
    "app/src/main/java/com/github/catvod/spider/DanmakuScanner.java",
    "app/src/main/java/com/github/catvod/spider/DanmakuUIHelper.java",
    "app/src/main/java/com/github/catvod/spider/NetworkUtils.java",
    "app/src/main/java/com/github/catvod/spider/TLSSocketFactory.java",
    "app/src/main/java/com/github/catvod/spider/TitleNormalizer.java",
    "app/src/main/java/com/github/catvod/spider/TitleMatchInfo.java",
    "app/src/main/java/com/github/catvod/spider/ExtFetcher.java",
    "app/src/main/java/com/github/catvod/spider/DanmakuManager.java",
    "app/src/main/java/com/github/catvod/spider/DanmakuUtils.java",
    "app/src/main/java/com/github/catvod/spider/TVFocusHelper.java",
    "app/src/main/java/com/github/catvod/spider/danmu/SharedPreferencesService.java",
]


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: build_payload.py <project-root> <output-dir>", file=sys.stderr)
        return 1

    project_root = pathlib.Path(sys.argv[1]).resolve()
    out_dir = pathlib.Path(sys.argv[2]).resolve()
    payload_jar = out_dir / "payload.jar"
    meta_path = out_dir / "payload.meta.json"

    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    found = []
    with zipfile.ZipFile(payload_jar, "w", zipfile.ZIP_DEFLATED) as zout:
        for rel in PAYLOAD_SOURCES:
            src = project_root / rel
            if not src.exists():
                continue
            zout.write(src, arcname=rel)
            found.append(rel)

    meta = {
        "projectRoot": str(project_root),
        "payloadJar": str(payload_jar),
        "sourcesRequested": PAYLOAD_SOURCES,
        "sourcesPacked": found,
        "note": "V1 phase-2 payload target manifest expanded; runtime still allows fallback if payload cannot be classloaded.",
    }
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[payload] built {payload_jar}")
    print(f"[payload] sources packed: {len(found)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
