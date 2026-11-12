#!/usr/bin/env python3
"""Check the real delayed initializer with an idle event loop, without QQ login."""
from pathlib import Path
import argparse
import os
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, default=ROOT / 'app/src/main/assets/runtime/desktop/guest_compat.c')
parser.add_argument('--cc', default='gcc', help='Compiler command, optionally including cross-target flags')
parser.add_argument('--runner', default='', help='Optional emulator command and sysroot flags')
parser.add_argument('--library', type=Path, help='Test a prebuilt compatibility library instead of compiling it')
args = parser.parse_args()
compiler = shlex.split(args.cc)
runner = shlex.split(args.runner)
fixture_source = Path(__file__).with_name('hook_start_fixture.c')

with tempfile.TemporaryDirectory(prefix='bigfish-hook-start-') as temporary:
    folder = Path(temporary)
    library, fixture, hook = (folder / name for name in ('compat.so', 'fixture', 'wrapper.node'))
    common = [*compiler, '-O2', '-UNDEBUG', '-Wall', '-Wextra', '-Werror']
    if args.library:
        library = args.library.resolve(strict=True)
    else:
        subprocess.run([*common, '-shared', '-fPIC', '-Wl,-z,relro,-z,now', '-o', str(library), str(args.source), '-ldl'], check=True)
    subprocess.run([*common, '-o', str(fixture), str(fixture_source), '-pthread'], check=True)
    subprocess.run([*common, '-shared', '-fPIC', '-DHOOK_LIBRARY', '-o', str(hook), str(fixture_source)], check=True)
    for case in ('idle', 'pwait', 'finite', 'worker', 'signal', 'masked'):
        report = folder / f'{case}.txt'
        settings = dict(LD_PRELOAD=str(library), ASTRBOT_COMPAT_INIT_LIBRARY=str(hook),
                        ASTRBOT_COMPAT_INIT_SYMBOL='fixture_start', ASTRBOT_COMPAT_WAIT_MODULE='',
                        ASTRBOT_COMPAT_TARGET_NAME=fixture.name, ASTRBOT_COMPAT_INIT_DELAY_MS='150',
                        HOOK_REPORT=str(report))
        command = [*runner]
        env = dict(os.environ)
        if runner:
            # QEMU's -E applies LD_PRELOAD to the guest, not the host emulator.
            for key, value in settings.items():
                command += ['-E', f'{key}={value}']
        else:
            env.update(settings)
        command += [str(fixture), case]
        subprocess.run(command, env=env, check=True, timeout=8)
        assert report.read_text() == 'initialized\n', (case, 'initializer count')
        print(f'{case}: passed', flush=True)
