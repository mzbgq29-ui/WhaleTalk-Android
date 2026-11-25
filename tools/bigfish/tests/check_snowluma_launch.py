#!/usr/bin/env python3
"""Check QQ/SnowLuma launch environments and existing account-home selection."""
from pathlib import Path
from unittest.mock import patch
import importlib.util
import json
import os
import stat
import tempfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('snow_launch', ROOT / 'app/src/main/assets/runtime/bigfish_snowluma.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)

with tempfile.TemporaryDirectory(prefix='bigfish-snow-launch-') as temporary:
    folder = Path(temporary)
    launcher.ROOT = folder / 'extensions/snowluma'
    launcher.DESKTOP = folder / 'extensions/desktop'
    launcher.STATE = folder / 'persistent/snowluma'
    launcher.RUNTIME = launcher.ROOT / 'runtime'
    launcher.ROOT.mkdir(parents=True)
    launcher.STATE.mkdir(parents=True)
    (launcher.STATE / 'manager.json').write_text(json.dumps({'port': 6099}))
    (launcher.STATE / 'bootstrap-password').write_text('fixture-password')
    qq = launcher.DESKTOP / 'qq/opt/QQ'
    (qq / 'resources/app').mkdir(parents=True)
    pinned_version = '3.2.35-54395'
    archive_sha = 'f' * 64
    (launcher.DESKTOP / 'sources.json').write_text(json.dumps({'qq': {'version': pinned_version, 'sha256': archive_sha}}))
    (launcher.DESKTOP / 'qq.json').write_text(json.dumps({'schemaVersion': 1, 'version': pinned_version, 'archiveSHA256': archive_sha}))
    (qq / 'resources/app/package.json').write_text(json.dumps({
        'version': pinned_version, 'main': './application.asar/app_launcher/index.js',
    }))
    (qq / 'qq').touch()
    (qq / 'resources/app/wrapper.node').touch()
    qq_config = folder / 'persistent/home/.config/QQ/versions/config.json'
    qq_config.parent.mkdir(parents=True)
    previous = {'curVersion': 'hot-updated', 'onErrorVersions': ['older']}
    qq_config.write_text(json.dumps(previous))
    calls = []

    def launch(arguments, env=None, cwd=None):
        calls.append((arguments, env, cwd))
        directory = Path(env['SNOWLUMA_HOOK_RUNTIME_DIR'])
        assert directory == launcher.ROOT.parent.parent / 'sl-ipc'
        assert directory.is_dir() and stat.S_IMODE(directory.stat().st_mode) == 0o700
        assert env['XDG_RUNTIME_DIR'] == str(directory)
        if len(calls) == 3:
            launcher.stopping = True
        return SimpleNamespace(poll=lambda: None)

    with patch.object(launcher, 'launch', launch), \
            patch.object(launcher.signal, 'signal'), \
            patch.object(launcher.subprocess, 'run', return_value=SimpleNamespace(returncode=0)), \
            patch.dict(os.environ, {'XDG_RUNTIME_DIR': '/unrelated-runtime', 'SNOWLUMA_HOOK_RUNTIME_DIR': '/unrelated-hook'}):
        assert launcher.main() == 0
    assert len(calls) == 3
    snow_env, qq_env = calls[1][1], calls[2][1]
    assert snow_env['SNOWLUMA_HOOK_AUTOLOAD'] == '0'
    assert snow_env['SNOWLUMA_WEBUI_HOST'] == '127.0.0.1'
    assert qq_env['HOME'] == str(folder / 'persistent/home')
    assert qq_env['ASTRBOT_COMPAT_SELF_MEMORY'] == '1'
    assert calls[2][2] == qq
    assert json.loads(qq_config.read_text()) == {
        'curVersion': pinned_version, 'onErrorVersions': ['older', 'hot-updated'],
    }
    [original] = qq_config.parent.glob('config.json.whaletalk-backup-*')
    assert json.loads(original.read_text()) == previous
    profile = json.loads((launcher.ROOT / 'compat-profile.json').read_text())
    assert profile['symbol'] == 'snowluma_linux_hook_start_dynamic'
    assert profile['waitModule'] == 'wrapper.node' and profile['delayMs'] == 5000
    print('Pinned QQ package, hot-update recovery, private IPC and existing account HOME: passed')
