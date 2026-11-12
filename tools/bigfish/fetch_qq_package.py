#!/usr/bin/env python3
"""Fetch the pinned official ARM64 QQ binary, without a GitHub dependency."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import urllib.request

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--assets', type=Path, default=root / '.local/inputs/assets')
args = parser.parse_args()
spec = json.loads((root / 'app/src/main/assets/bigfish/qq-package.json').read_text())
target = args.assets / spec['asset']
target.parent.mkdir(parents=True, exist_ok=True)
def verified(path):
    if not path.is_file() or path.stat().st_size != spec['bytes']:
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == spec['sha256']
if not verified(target):
    staged = target.with_suffix('.partial')
    try:
        with urllib.request.urlopen(spec['url'], timeout=90) as response, staged.open('wb') as output:
            count = 0
            while block := response.read(1024 * 1024):
                count += len(block)
                if count > spec['bytes']:
                    raise RuntimeError('QQ archive exceeds its pinned size')
                output.write(block)
        if not verified(staged):
            raise RuntimeError('Official QQ archive integrity check failed')
        os.replace(staged, target)
    finally:
        staged.unlink(missing_ok=True)
print('Verified official QQ', spec['version'], spec['sha256'])
