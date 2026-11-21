#!/usr/bin/env python3
"""Audit the assembled artifact, including actual VM content and cloud evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('hap', type=Path)
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--smoke', type=Path, required=True)
parser.add_argument('--lock', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
receipt = json.loads(args.receipt.read_text())
smoke = json.loads(args.smoke.read_text())
lock = json.loads(args.lock.read_text())
if smoke.get('passed') is not True:
    raise SystemExit('Actual guest cloud boot/service checks did not pass')

with zipfile.ZipFile(args.hap) as archive:
    pack = json.loads(archive.read('pack.info'))
    module = json.loads(archive.read('module.json'))
    modules = pack.get('summary', {}).get('modules', [])
    api_versions = [int(item['apiVersion']['compatible']) for item in modules]
    if not api_versions or any(api < 1 or api > 20 for api in api_versions):
        raise SystemExit(f'Unsupported compatible API levels: {api_versions}')
    # module.json's minAPIVersion can be encoded as 50100018, not simply 18.
    native = [name for name in archive.namelist() if name.endswith('.so')]
    if not any(name.endswith('/libqemu-system-aarch64-tci.so') for name in native):
        raise SystemExit('Actual TCI core is missing from HAP')
    permissions = module.get('module', {}).get('requestPermissions', [])
    if any(any(flag in item.get('name', '') for flag in
               ('ALLOW_WRITABLE_CODE_MEMORY', 'DISABLE_CODE_MEMORY_PROTECTION'))
           for item in permissions):
        raise SystemExit('Phone delivery must not require JIT code-memory ACL permissions')
    def member(suffix):
        hits = [name for name in archive.namelist() if name.endswith(suffix)]
        if len(hits) != 1:
            raise SystemExit(f'Expected one HAP member: {suffix}, found {len(hits)}')
        return hits[0]
    image_member = member('/rawfile/vm/rootfs_aarch64.qcow2')
    kernel_member = member('/rawfile/vm/kernel_aarch64')
    image_size = archive.getinfo(image_member).file_size
    if image_size != receipt['image']['bytes']:
        raise SystemExit('Packaged guest disk size differs from the cloud-tested image')
    with archive.open(image_member) as stream:
        image_digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if image_digest != receipt['image']['sha256']:
        raise SystemExit('Packaged disk is not the actual cloud-tested guest image')
    with archive.open(kernel_member) as stream:
        kernel_digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if kernel_digest != lock['assets']['kernel']['sha256']:
        raise SystemExit('Packaged kernel differs from the pinned cloud-tested kernel')
report = {
    'hap': args.hap.name, 'bytes': args.hap.stat().st_size,
    'compatibleApiVersions': api_versions, 'nativeLibraries': native,
    'guestImageSHA256': image_digest, 'kernelSHA256': kernel_digest,
    'guestCloudSmoke': smoke, 'sourceApkSHA256': receipt['sourceApkSHA256'],
    'signing': 'unsigned', 'harmonyDeviceValidation': 'not performed',
}
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False, indent=2))
