#!/usr/bin/env python3
"""Reuse the pinned complete upstream payload without repacking a live phone's data."""
from pathlib import Path
import argparse
import hashlib
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
URL = "https://github.com/JunWan666/astrbot-for-android/releases/download/v1.4.1/AstrBot-for-Android-v1.4.1-arm64-v8a.apk"
SHA256 = "cacdc3332c84df32a9958cdb16a3167c0a90d8f9c27815cde63df9e93ad658a1"

def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): value.update(block)
    return value.hexdigest()

def prepare(apk):
    if digest(apk) != SHA256: raise SystemExit("Upstream APK SHA-256 mismatch; extraction refused.")
    output = ROOT / ".local/inputs"
    with zipfile.ZipFile(apk) as archive:
        for item in archive.infolist():
            name = item.filename
            destination = None
            if name.startswith("assets/") and not name.startswith("assets/dexopt/"):
                relative = name[len("assets/"):]
                if not (ROOT / "app/src/main/assets" / relative).is_file(): destination = output / "assets" / relative
            elif name.startswith("lib/arm64-v8a/") and not name.endswith("libandroidx.graphics.path.so"):
                destination = output / "native" / name[len("lib/"):]
            if destination is not None:
                if not destination.resolve().is_relative_to(output.resolve()): raise SystemExit("Unsafe upstream archive path")
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(item) as source, destination.open("wb") as target: shutil.copyfileobj(source, target, 1024 * 1024)
    print("Pinned runtime and native PRoot programs prepared:", output)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    apk = args.apk or ROOT / ".cache/upstream-v1.4.1.apk"
    if not apk.exists():
        apk.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=60) as source, apk.open("wb") as target: shutil.copyfileobj(source, target, 1024 * 1024)
    prepare(apk)
