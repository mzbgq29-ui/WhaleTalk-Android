#!/usr/bin/env python3
"""Package reviewable source and evidence, excluding local runtime inputs and credentials."""
from pathlib import Path, PurePosixPath
import hashlib
import json
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[2]
dist = root / "dist"
dist.mkdir(exist_ok=True)
properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
version = properties["appVersionName"]
names = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root).decode().split("\0")
files = []
for name in sorted(set(filter(None, names))):
    parts = PurePosixPath(name).parts
    assert not set(parts).intersection({".local", ".git", ".gradle", ".kotlin", "build", "dist"}), name
    assert not name.endswith((".p12", ".pfx", ".jks", ".keystore", ".pem", ".apk")), name
    assert name != "local.properties", name
    path = root / name
    assert not path.is_symlink(), name
    if path.is_file() and name != "validation/current-ui.png":
        assert path.stat().st_size < 32 * 1024 * 1024, name
        content = path.read_bytes()
        for marker in [b"-----BEGIN " + b"PRIVATE KEY-----", b"-----BEGIN " + b"RSA PRIVATE KEY-----"]:
            assert marker not in content, name
        files.append((name, content))
archive = dist / f"BigFish-Android-{version}-source.zip"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for name, content in files:
        entry = zipfile.ZipInfo("BigFish-Android/" + name, (2026, 11, 10, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = (0o100755 if (root / name).stat().st_mode & 0o111 else 0o100644) << 16
        output.writestr(entry, content)
with zipfile.ZipFile(archive) as output:
    assert output.testzip() is None
    assert len(output.namelist()) == len(files)
for source, target in [("BIGFISH.md", "安装说明.md"), ("FORK.md", "参考与复用.md"), ("VALIDATION.md", "验证记录.md")]:
    shutil.copyfile(root / source, dist / target)
def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): value.update(block)
    return value.hexdigest()
apk = dist / f"BigFish-Android-{version}-arm64-v8a.apk"
report = json.loads((root / "validation/apk-verification.json").read_text())
assert digest(apk) == report["sha256"], "Release changed since verification"
checksums = [(digest(path), path.name) for path in [apk, archive, dist / "安装说明.md", dist / "参考与复用.md", dist / "验证记录.md"]]
(dist / "SHA256SUMS.txt").write_text("".join(checksum + "  " + name + "\n" for checksum, name in checksums))
print(json.dumps({"sourceFiles": len(files), "sourceBytes": archive.stat().st_size, "privateMaterial": "excluded", "apkSHA256": report["sha256"]}, indent=2))
