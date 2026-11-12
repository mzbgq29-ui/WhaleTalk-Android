#!/usr/bin/env python3
"""Load the original native SnowLuma hook and verify both real socket HELLOs.

This checks local transport only, not QQ login or message compatibility.
Run on ARM64 glibc Linux, or pass QEMU and a matching guest sysroot.
"""
from pathlib import Path
import argparse
import os
import selectors
import shlex
import socket
import struct
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--hook', type=Path, required=True, help='Original snowluma-linux-arm64.so')
parser.add_argument('--compat', type=Path, default=ROOT / 'app/src/main/assets/runtime/desktop/libguest-compat.arm64.bin')
parser.add_argument('--cc', default='gcc')
parser.add_argument('--runner', default='')
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='snow-ipc-') as temporary:
    folder = Path(temporary)
    fixture = folder / 'fixture'
    subprocess.run([*shlex.split(args.cc), '-O2', '-Wall', '-Wextra', '-Werror',
                    str(Path(__file__).with_name('snowluma_ipc_fixture.c')), '-o', str(fixture), '-ldl'], check=True)
    env = dict(os.environ)
    settings = dict(SNOWLUMA_HOOK_RUNTIME_DIR=str(folder), XDG_RUNTIME_DIR='/unrelated-runtime',
                    LD_PRELOAD=str(args.compat.resolve(strict=True)))
    command = shlex.split(args.runner)
    if command:
        for key, value in settings.items():
            command += ['-E', f'{key}={value}']
    else:
        env.update(settings)
    command += [str(fixture), str(args.hook.resolve(strict=True))]
    with (folder / 'stderr.log').open('w') as error_log:
        process = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=error_log)
        try:
            # Bound startup even if a native constructor fails before printing.
            lines = b''
            deadline = time.monotonic() + 10
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while lines.count(b'\n') < 3:
                    if time.monotonic() >= deadline:
                        raise TimeoutError('Native SnowLuma startup did not finish')
                    if selector.select(.1):
                        chunk = os.read(process.stdout.fileno(), 4096)
                        if not chunk:
                            raise RuntimeError('Native SnowLuma exited before opening sockets')
                        lines += chunk
            result, control, recv = lines.decode().splitlines()[:3]
            assert result == '1', result
            pids = set()
            for kind, path in [('control', control), ('recv', recv)]:
                assert Path(path).parent == folder, (kind, path)
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                    connection.settimeout(5)
                    connection.connect(path)
                    data = b''
                    while len(data) < 40:
                        chunk = connection.recv(40 - len(data))
                        if not chunk:
                            raise RuntimeError('Socket closed before HELLO')
                        data += chunk
                    magic, version, op, request, status, flags, cmd_len, msg_len, body_len, pid = struct.unpack('<IHHIiIIIIQ', data)
                    assert (magic, version, op, request, status) == (0x31504851, 1, 1, 0, 0)
                    assert bool(flags & 2) == (kind == 'recv')
                    assert Path(path).name == f'mojo.{pid}.{kind}.sock'
                    assert cmd_len <= 4096 and msg_len <= 65536 and body_len <= 16 * 1024 * 1024
                    pids.add(pid)
                print(f'{kind}: original ARM64 native hook HELLO and shared IPC directory passed', flush=True)
            assert len(pids) == 1
            process.communicate(b'\n', timeout=10)
            assert process.returncode == 0
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
