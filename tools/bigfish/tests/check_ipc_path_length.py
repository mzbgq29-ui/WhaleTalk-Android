#!/usr/bin/env python3
"""Reproduce Android/PRoot's translated AF_UNIX path limit and the short-path fix."""
from pathlib import Path
import importlib.util
import socket
import stat
import tempfile

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('snow_launch', ROOT / 'app/src/main/assets/runtime/bigfish_snowluma.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)

with tempfile.TemporaryDirectory(prefix='bf-ipc-') as temporary:
    # Match the real Android host prefix length instead of testing only a short
    # /tmp path. Include multi-user installs and the /data/data alias.
    for index, prefix in enumerate(('/data/user/0/dev.bigfish.nativebot.qqfix/files',
                                    '/data/user/999/dev.bigfish.nativebot.qqfix/files',
                                    '/data/data/dev.bigfish.nativebot.qqfix/files')):
        base = Path(temporary) / str(index)
        padding = len(prefix.encode()) - len(str(base).encode()) - 1
        assert padding > 0
        host = base / ('x' * padding)
        launcher.ROOT = host / 'manager/extensions/snowluma'
        launcher.ROOT.mkdir(parents=True)
        old = launcher.ROOT / 'qq-runtime'
        old.mkdir()
        old_path = old / 'mojo.123456.control.sock'
        assert len(str(old_path).encode()) >= 108
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
            try:
                server.bind(str(old_path))
            except OSError as failure:
                assert 'too long' in str(failure), failure
            else:
                raise AssertionError('The original translated path should fail')
        directory = launcher.prepare_ipc_directory()
        assert directory == host / 'manager/sl-ipc'
        assert stat.S_IMODE(directory.stat().st_mode) == 0o700
        assert not list(directory.iterdir()), 'Preflight socket must be removed'
        for name in ('mojo.12345.control.sock', 'mojo.123456.recv.sock',
                     'mojo.2147483647.control.sock'):
            path = directory / name
            assert len(str(path).encode()) <= 107
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
                server.bind(str(path))
                server.listen(1)
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                    client.connect(str(path))
                    with server.accept()[0] as peer:
                        client.sendall(b'ipc-path-fixture')
                        assert peer.recv(32) == b'ipc-path-fixture'
            path.unlink()
        print(f'{prefix}: original overlong bind rejected; short control/recv paths connected')
print('Android-length private IPC paths, real socket exchange and preflight cleanup: passed')
