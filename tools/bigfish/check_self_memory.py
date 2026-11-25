#!/usr/bin/env python3
"""Exercise the opt-in fallback with a real kernel syscall denial, in children."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='bigfish-self-memory-') as tmp:
    folder = Path(tmp)
    library = folder / 'compat.so'
    fixture = folder / 'fixture'
    subprocess.run(['gcc', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-Wl,-z,relro,-z,now', '-o', str(library),
                    str(root / 'app/src/main/assets/runtime/desktop/guest_compat.c'), '-ldl'], check=True)
    subprocess.run(['gcc', '-O2', '-Wall', '-Wextra', '-Werror',
                    str(Path(__file__).with_name('self_memory_fixture.c')), '-o', str(fixture)], check=True)
    env = dict(os.environ, LD_PRELOAD=str(library))
    env.pop('ASTRBOT_COMPAT_SELF_MEMORY', None)
    subprocess.run([str(fixture)], env=env, check=True, timeout=20)
    subprocess.run([str(fixture), 'enabled'],
                   env=dict(env, ASTRBOT_COMPAT_SELF_MEMORY='1'), check=True, timeout=20)
