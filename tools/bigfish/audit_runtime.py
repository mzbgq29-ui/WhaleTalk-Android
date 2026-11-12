#!/usr/bin/env python3
"""Offline, bounded audit: checks image digests and reads critical guest files without executing QQ."""
from pathlib import Path
import hashlib
import json
import struct
import tarfile

root = Path(__file__).resolve().parents[2]
assets = root / ".local/inputs/assets"
out = root / "validation"
out.mkdir(exist_ok=True)
def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): value.update(block)
    return value.hexdigest()
base = assets / "bootstrap/arm64-v8a"
manifest = json.loads((base / "manifest.json").read_text())
image = base / "ubuntu.tar.bin"
assert manifest["kind"] == "complete" and digest(image) == manifest["sha256"]
snapshot = assets / "desktop-snapshot/arm64-v8a"
desktop = json.loads((snapshot / "manifest.json").read_text())
assert desktop["baseRuntimeSHA256"] == manifest["sha256"]
assert desktop["bytes"] == (snapshot / "desktop.tar.bin").stat().st_size
assert desktop["sha256"] == digest(snapshot / "desktop.tar.bin")
assert desktop["bundleSHA256"] == digest(assets / "desktop-packages/arm64-v8a/manifest.json")
found = {}
private_session_files = 0
targets = {"var/lib/bigfish/runtime-components.json", "opt/astrbot/main.py", "opt/astrbot/astrbot/__init__.py", "opt/astrbot-venv/pyvenv.cfg", "usr/bin/python3.12"}
with tarfile.open(image, mode="r|*") as archive:
    for item in archive:
        name = item.name.removeprefix("./")
        lower = name.lower()
        assert not lower.startswith("opt/qq/"), "Obsolete QQ/backend remains"
        assert not ("napcat" in lower and not lower.startswith("opt/astrbot/")), "Obsolete backend path remains"
        if item.isfile() and item.size and (lower.startswith(("persistent/", "root/.config/qq/", "root/.config/tencent-qq/", "root/.config/tencentqq/")) or lower in {"root/.bash_history", "root/.python_history"}):
            private_session_files += 1
        if name in targets and item.isfile():
            stream = archive.extractfile(item)
            head = stream.read(4096)
            found[name] = {"bytes": item.size, "elfMachine": struct.unpack_from("<H", head, 18)[0] if head.startswith(b"\x7fELF") else None}
            if name == "usr/bin/python3.12": assert found[name]["elfMachine"] == 183, name
assert targets <= found.keys(), targets - found.keys()
assert private_session_files == 0, "Unexpected session/history data in upstream image; distribution stopped."
native_alignment = {}
for library in sorted((root / ".local/inputs/native/arm64-v8a").glob("*.so")):
    data = library.read_bytes()
    if data[:5] != b"\x7fELF\x02": continue  # The optional 32-bit loader is not a 64-bit program.
    phoff = struct.unpack_from("<Q", data, 32)[0]
    stride, count = struct.unpack_from("<HH", data, 54)
    loads = [struct.unpack_from("<Q", data, phoff + i * stride + 48)[0] for i in range(count)
             if struct.unpack_from("<I", data, phoff + i * stride)[0] == 1]
    assert loads and min(loads) >= 16384, library.name
    native_alignment[library.name] = min(loads)
report = {"runtimeId": manifest["id"], "runtimeSHA256": manifest["sha256"], "unpackedBytes": manifest["unpackedBytes"],
          "desktopSHA256": desktop["sha256"], "criticalFiles": found, "privateSessionFileCount": private_session_files,
          "native64BitMinimumLoadAlignment": native_alignment,
          "scope": "Offline payload audit only; no QQ account login or ARM phone execution."}
(out / "runtime-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False, indent=2))
