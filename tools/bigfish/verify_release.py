#!/usr/bin/env python3
"""Verify the actual signed, complete APK and copy only the installable release to dist."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--sdk", type=Path, required=True)
parser.add_argument("--previous-apk", type=Path)
parser.add_argument("--expected-certificate-sha256", default="f24ca4cc7c55c8c53845ec383a90b771573cdd86fb89d3865d087bc8d2aa5a96",
                    help="Explicit expected certificate for an independently signed build")
args = parser.parse_args()
properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
version = properties["appVersionName"]
version_code = int(properties["appVersionCode"])
application_id = properties.get("applicationIdOverride", "dev.bigfish.nativebot" + properties.get("verificationSuffix", ""))
apk = root / "app/build/outputs/apk/bundled/release/app-bundled-release.apk"
tools = args.sdk / "build-tools/35.0.0"
def run(*command): return subprocess.run(list(map(str, command)), check=True, capture_output=True, text=True).stdout
def digest(stream):
    value = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""): value.update(block)
    return value.hexdigest()
signatures = run(tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
# The APK's minSdk is 26, so default verification intentionally does not exercise v1.
# Check its JAR signature separately without changing the application's supported SDK.
v1_signatures = run(tools / "apksigner", "verify", "--verbose", "--min-sdk-version", "23", "--max-sdk-version", "23", apk)
assert re.search(r"Verified using v1 scheme.*: true", v1_signatures)
for scheme in ["v2", "v3"]: assert re.search(r"Verified using " + scheme + r" scheme.*: true", signatures), scheme
certificate = re.search(r"Signer #1 certificate SHA-256 digest: (\w+)", signatures).group(1)
assert re.fullmatch(r"[0-9a-f]{64}", args.expected_certificate_sha256), "Invalid expected certificate digest"
assert certificate == args.expected_certificate_sha256, "Signing identity changed"
badging = run(tools / "aapt", "dump", "badging", apk)
assert f"name='{application_id}'" in badging and f"versionCode='{version_code}'" in badging
assert f"versionName='{version}'" in badging and "sdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging
assert "application-debuggable" not in badging
manifest = run(tools / "aapt", "dump", "xmltree", apk, "AndroidManifest.xml")
assert "dev.bigfish.nativebot.MainActivity" in manifest and "BigFishWizardActivity" in manifest
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)), "Duplicate APK paths"
    assert {n.split('/')[1] for n in names if n.startswith('lib/')} == {'arm64-v8a'}
    assert "lib/arm64-v8a/libproot.so" in names and "lib/arm64-v8a/libproot-loader.so" in names
    metadata = json.loads(archive.read("assets/bootstrap/arm64-v8a/manifest.json"))
    assert metadata["kind"] == "complete"
    assert metadata["sha256"] == json.loads((root/"runtime/desktop-packages.lock.json").read_text())["baseRuntimeSha256"]
    assert "napcat" not in metadata["components"]
    assert not any("napcat" in n.lower() for n in names), "Obsolete resource path in APK"
    library=json.loads(archive.read("assets/bigfish/persona-library.json"))
    assert len(library["items"]) == 286
    assert len(json.loads(archive.read("assets/bigfish/tavern-library.json"))["items"]) == 11
    compatibility=json.loads(archive.read("assets/runtime/desktop/compat-binary.json"))
    payload=archive.read("assets/runtime/desktop/"+compatibility["file"])
    assert len(payload)==compatibility["bytes"] and hashlib.sha256(payload).hexdigest()==compatibility["sha256"]
    source_hash=hashlib.sha256()
    for part in ["guest_compat.c","proc_locks.py","guest-compat"]: source_hash.update(archive.read("assets/runtime/desktop/"+part))
    assert source_hash.hexdigest()==compatibility["sourceSHA256"]
    with zipfile.ZipFile(__import__("io").BytesIO(archive.read("assets/third-party/notices.zip"))) as notices:
        assert "bigfish/vendor/PersonaLibrary/LICENSE" in notices.namelist()
        assert "bigfish/vendor/TavernLibrary/provenance.json" in notices.namelist()
    with archive.open("assets/bootstrap/arm64-v8a/ubuntu.tar.bin") as stream: assert digest(stream) == metadata["sha256"]
    qq = json.loads(archive.read("assets/bigfish/qq-package.json"))
    assert json.loads(archive.read("assets/runtime/desktop/sources.json"))["qq"] == qq
    assert archive.getinfo("assets/" + qq["asset"]).file_size == qq["bytes"]
    with archive.open("assets/" + qq["asset"]) as stream: assert digest(stream) == qq["sha256"]
upgrade = None
if args.previous_apk:
    previous = run(tools / "apksigner", "verify", "--print-certs", args.previous_apk)
    old_certificate = re.search(r"Signer #1 certificate SHA-256 digest: (\w+)", previous).group(1)
    assert certificate == old_certificate, "Old app cannot be upgraded with this signing identity"
    old_badging = run(tools / "aapt", "dump", "badging", args.previous_apk)
    assert f"name='{application_id}'" in old_badging, "Application ID changed; this is a separate installation"
    assert int(re.search(r"versionCode='(\d+)'", old_badging).group(1)) < version_code, "Version code did not increase"
    upgrade = "Same application ID, same signing certificate, increased versionCode, old activity alias retained."
with apk.open('rb') as stream: checksum = digest(stream)
dist = root / "dist"; dist.mkdir(exist_ok=True)
output = dist / f"WhaleTalk-Android-{version}-arm64-v8a.apk"
assert shutil.disk_usage(dist).free + (output.stat().st_size if output.exists() else 0) > apk.stat().st_size + 32 * 1024 * 1024
if apk.resolve() != output.resolve(): shutil.copy2(apk, output)
with output.open('rb') as stream: assert digest(stream) == checksum
report = {"apk": output.name, "bytes": output.stat().st_size, "sha256": checksum, "certificateSHA256": certificate,
          "schemes": ["v1", "v2", "v3"], "applicationId": application_id, "versionName": version, "versionCode": version_code,
          "minSdk": 26, "targetSdk": 35, "abi": "arm64-v8a", "debuggable": False, "bundledRuntimeSHA256": metadata["sha256"], "bundledQqVersion": qq["version"], "bundledQqSHA256": qq["sha256"], "upgrade": upgrade}
validation = root / "validation"; validation.mkdir(exist_ok=True)
(validation / "apk-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
(validation / "apk-badging.txt").write_text(badging)
(validation / "apk-signature.txt").write_text(signatures + "\nSeparate v1 cryptographic check (not an Android 7 compatibility claim):\n" + v1_signatures)
print(json.dumps(report, ensure_ascii=False, indent=2))
