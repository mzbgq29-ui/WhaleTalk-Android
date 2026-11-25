#!/usr/bin/env python3
"""Recover exact compiled runtime dependencies from the matching complete release APK.
Application source stays in the source ZIP; proprietary vendor binaries are not source.
"""
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import shutil
import zipfile

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk',type=Path,required=True)
parser.add_argument('--sha256',required=True,help='Expected APK SHA-256 from the delivery receipt')
parser.add_argument('--output',type=Path,default=root/'.local/inputs')
args=parser.parse_args()
h=hashlib.sha256()
with args.apk.open('rb') as f:
    for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
assert h.hexdigest()==args.sha256.lower(),'APK SHA-256 does not match'
assert not args.output.exists(),'Output exists; preserve existing inputs or select a new output'
with zipfile.ZipFile(args.apk) as z:
    assert 'assets/bootstrap/arm64-v8a/manifest.json' in z.namelist(),'Use the complete ARM64 release'
    for entry in z.infolist():
        p=PurePosixPath(entry.filename)
        assert not p.is_absolute() and '..' not in p.parts and '\\' not in entry.filename,'Invalid APK path'
        if entry.is_dir():continue
        if entry.filename.startswith('assets/'):
            relative=entry.filename.removeprefix('assets/')
            if relative.startswith('dexopt/') or (root/'app/src/main/assets'/relative).is_file():continue
            destination=args.output/'assets'/relative
        elif entry.filename.startswith('lib/arm64-v8a/') and not entry.filename.endswith('libandroidx.graphics.path.so'):
            destination=args.output/'native'/entry.filename.removeprefix('lib/')
        else:continue
        destination.parent.mkdir(parents=True,exist_ok=True)
        with z.open(entry) as source,destination.open('wb') as target:shutil.copyfileobj(source,target,1024*1024)
print('Exact release dependencies recovered:',args.output)
