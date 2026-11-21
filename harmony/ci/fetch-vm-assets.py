#!/usr/bin/env python3
"""Fetch the locked Linux kernel; optionally stage the locked Ubuntu archive."""
import argparse
import hashlib
import json
import pathlib
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('project', type=pathlib.Path)
parser.add_argument('--ubuntu', action='store_true')
args = parser.parse_args()
lock = json.loads((args.project / 'runtime-sources.lock.json').read_text())
vm = args.project / 'entry/src/main/resources/rawfile/vm'
vm.mkdir(parents=True, exist_ok=True)
for name, filename in [('kernel', 'kernel_aarch64')] + ([('ubuntu', 'ubuntu-base-24.04.zip')] if args.ubuntu else []):
    asset = lock['assets'][name]
    destination = vm / filename
    def valid():
        return (destination.is_file() and destination.stat().st_size == asset['bytes']
                and hashlib.file_digest(destination.open('rb'), 'sha256').hexdigest() == asset['sha256'])
    if not valid():
        subprocess.run(['curl', '--fail', '--show-error', '--silent', '--location', '--retry', '3',
                        asset['url'], '-o', str(destination)], check=True)
    if not valid():
        raise SystemExit(f'Locked asset verification failed: {name}')
    print(name, asset['sha256'], destination)
