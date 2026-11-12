#!/usr/bin/env python3
"""Run an actual PRoot bind mount with Android-length host paths, without QQ."""
from pathlib import Path
import argparse
import json
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--proot', type=Path, required=True)
args = parser.parse_args()
source = ROOT / 'app/src/main/assets/runtime/bigfish_snowluma.py'
code = '''
import json, pathlib, runpy, socket, subprocess, sys
module=runpy.run_path(sys.argv[1])
old=pathlib.Path('/manager/extensions/snowluma/qq-runtime')
old.mkdir(parents=True,exist_ok=True)
server_code="import socket,sys; s=socket.socket(socket.AF_UNIX); s.settimeout(5); s.bind(sys.argv[1]); s.listen(1); print('ready',flush=True); c=s.accept()[0]; c.sendall(b'proot-ipc-fixture'); c.close(); s.close()"
reader_code="import json,os,socket,sys; p=sys.stdin.readline().strip(); s=socket.socket(socket.AF_UNIX); s.settimeout(2); visible=os.path.exists(p); listed=os.path.basename(p) in os.listdir(os.path.dirname(p))\\ntry:\\n s.connect(p); assert s.recv(32)==b'proot-ipc-fixture'; print(json.dumps({'visible':visible,'listed':listed,'connected':True}))\\nexcept OSError as e:\\n print(json.dumps({'visible':visible,'listed':listed,'connected':False,'errno':e.errno}))"
def exchange(path):
    # SnowLuma starts before QQ. Its existing PRoot tracee must be able to
    # discover/connect the socket later bound by its sibling QQ process.
    reader=subprocess.Popen([sys.executable,'-c',reader_code],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    server=subprocess.Popen([sys.executable,'-c',server_code,str(path)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    try:
        assert server.stdout.readline().strip()=='ready',server.stderr.read()
        output,error=reader.communicate(str(path)+'\\n',timeout=5)
        assert reader.returncode==0,error
        return json.loads(output)
    finally:
        for child in (reader,server):
            if child.poll() is None:child.terminate()
            child.wait(timeout=5)
        path.unlink(missing_ok=True)
legacy=exchange(old/'mojo.123456.control.sock')
assert not legacy['listed'],legacy
directory=module['prepare_ipc_directory']()
assert directory==pathlib.Path('/manager/sl-ipc')
for kind in ['control','recv']:
    path=directory/('mojo.123456.'+kind+'.sock')
    current=exchange(path)
    assert current['connected'] and current['visible'] and current['listed'],current
assert not list(directory.iterdir())
prepare=module['prepare_ipc_directory']
original_root=prepare.__globals__['ROOT']
try:
    prepare.__globals__['ROOT']=old/'deep/unused'
    try: prepare()
    except RuntimeError as error: assert 'IPC' in str(error)
    else: raise AssertionError('Preflight must reject a remapped, non-enumerable socket')
finally:
    prepare.__globals__['ROOT']=original_root
print(json.dumps({'oldSocketListed':legacy['listed'],'oldCrossProcessConnected':legacy['connected'],'shortControlAndRecv':'passed','hiddenSocketPreflight':'rejected'}))
'''
with tempfile.TemporaryDirectory(prefix='bf-proot-') as temporary:
    guest = Path(temporary) / 'guest'
    guest.mkdir()
    system = ('/usr', '/lib', '/lib64', '/opt', '/tmp', '/dev', '/proc', '/workspace')
    for directory in (*system, '/manager'):
        (guest / directory.lstrip('/')).mkdir(exist_ok=True)
    for index, prefix in enumerate(('/data/user/0/dev.bigfish.nativebot.qqfix/files',
                                    '/data/user/999/dev.bigfish.nativebot.qqfix/files')):
        base = Path(temporary) / str(index)
        padding = len(prefix) - len(str(base)) - 1
        assert padding > 0
        manager = base / ('x' * padding) / 'manager'
        manager.mkdir(parents=True)
        command = [str(args.proot.resolve()), '-r', str(guest), '-w', '/']
        for directory in system:
            command += ['-b', directory]
        command += ['-b', f'{manager}:/manager', sys.executable, '-c', code, str(source)]
        result = subprocess.run(command,
                                text=True, capture_output=True, timeout=25)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        value = json.loads(result.stdout)
        assert not value['oldSocketListed'] and value['shortControlAndRecv'] == 'passed'
        assert value['hiddenSocketPreflight'] == 'rejected'
        print(f"{prefix}: legacy directConnect={value['oldCrossProcessConnected']}, directoryListed={value['oldSocketListed']}; short control/recv and hidden-socket rejection passed")
