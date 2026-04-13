#!/usr/bin/env python3
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime, timezone

PAYLOAD_SOURCES = [
    "app/src/main/java/com/github/catvod/spider/protect/impl/PayloadEntry.java",
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


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def git_commit(project_root: pathlib.Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(project_root), "rev-parse", "HEAD"],
            text=True,
        ).strip()
    except Exception:
        return "unknown"


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
        "gitCommit": git_commit(project_root),
        "stage": "phase7-release-chain",
        "builtAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "payloadJar": str(payload_jar),
        "payloadJarSha256": sha256_file(payload_jar),
        "sourcesRequested": PAYLOAD_SOURCES,
        "sourcesPacked": found,
        "sourcesPackedCount": len(found),
        "payloadCoverage": [
            "PayloadEntry",
            "RealLeodanmu",
            "homeContent/categoryContent/detailContent/playerContent",
            "init",
            "liveContent"
        ],
        "outerStableZone": [
            "Leodanmu shell entry",
            "DanmakuConfig",
            "DanmakuConfigManager",
            "EpisodeInfo",
            "entity/**",
            "bean/**"
        ],
        "note": "Phase 7 release-chain hardening: keep source maintenance stable while strengthening payload packaging metadata and protected artifact traceability.",
    }
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[payload] built {payload_jar}")
    print(f"[payload] sources packed: {len(found)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
