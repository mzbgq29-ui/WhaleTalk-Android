#!/usr/bin/env python3
"""Stage true ELF libraries with HAP-compatible .so names and matching DT_NEEDED."""
import pathlib
import re
import shutil
import subprocess
import sys

output, buildroot, destination = map(pathlib.Path, sys.argv[1:])
destination.mkdir(parents=True, exist_ok=True)
names = {}
for directory in (buildroot, output):
    for source in sorted(directory.glob('lib*.so*')):
        if not source.is_file():
            continue
        with source.open('rb') as stream:
            if stream.read(4) != b'\x7fELF':
                continue
        canonical = re.sub(r'(\.so)(?:\..*)?$', r'\1', source.name)
        # Ship the interpreter core only. qemu-img is useful for disk management.
        if canonical == 'libqemu-system-aarch64.so':
            continue
        names[source.name] = canonical
        soname = subprocess.check_output(['patchelf', '--print-soname', str(source)], text=True).strip()
        if soname:
            names[soname] = canonical
        shutil.copyfile(source, destination / canonical)

for library in destination.glob('*.so'):
    soname = subprocess.check_output(['patchelf', '--print-soname', str(library)], text=True).strip()
    if soname and soname != library.name:
        subprocess.run(['patchelf', '--set-soname', library.name, str(library)], check=True)
    needed = subprocess.check_output(['patchelf', '--print-needed', str(library)], text=True).splitlines()
    for dependency in needed:
        replacement = names.get(dependency)
        if replacement and replacement != dependency:
            subprocess.run(['patchelf', '--replace-needed', dependency, replacement, str(library)], check=True)

core = destination / 'libqemu-system-aarch64-tci.so'
if not core.is_file():
    raise SystemExit('TCI core missing')
symbols = subprocess.check_output(['readelf', '--dyn-syms', '--wide', str(core)], text=True)
if not re.search(r'\bqemu_system_entry\b', symbols):
    raise SystemExit('QEMU core does not export qemu_system_entry')
for library in destination.glob('*.so'):
    print(library.name)
    for dependency in subprocess.check_output(['patchelf', '--print-needed', str(library)], text=True).splitlines():
        if '.so.' in dependency and not (destination / dependency).exists():
            raise SystemExit(f'{library.name}: unbundled versioned dependency {dependency}')
