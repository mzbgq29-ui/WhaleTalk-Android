#!/usr/bin/env python3
"""Build a personal Linux ARM64 runtime, retaining the supplied JavaScript verbatim.

This is platform packaging, not conversion of a Windows DLL into an ELF library.
Use matching Linux native components and retain all supplied notices.
"""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, shutil, struct, tarfile, zipfile

def sha(data): return hashlib.sha256(data).hexdigest()
def safe(name):
    name=name.removeprefix('./');p=PurePosixPath(name)
    if not name or p.is_absolute() or '..' in p.parts or '\\' in name: raise ValueError('Unsafe archive path')
    return name
def arm64(data, name):
    if data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1 or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('Not a Linux ARM64 ELF: '+name)

def prepare(windows, linux, linux_sha256, destination):
    if destination.exists(): raise ValueError('Destination already exists; preserve previous work')
    if sha(linux.read_bytes()) != linux_sha256: raise ValueError('Linux archive checksum mismatch')
    entries={}
    with zipfile.ZipFile(windows) as z:
        for item in z.infolist():
            name=safe(item.filename)
            if item.is_dir(): continue
            if item.file_size > 160*1024*1024: raise ValueError('Oversized input entry')
            if name in ['node.exe','launcher.bat','release.json'] or name.startswith('native/'): continue
            entries[name]=z.read(item)
        original_main=sha(z.read('index.mjs'))
        package=json.loads(entries['package.json'])
    with tarfile.open(linux,'r:gz') as t:
        manifest=None
        for item in t:
            name=safe(item.name)
            if item.isdir(): continue
            if not item.isfile() or item.size > 160*1024*1024: raise ValueError('Unsupported Linux archive entry')
            if name=='release.json': manifest=json.loads(t.extractfile(item).read())
            if name in ['node','launcher.sh'] or name.startswith('native/'):
                data=t.extractfile(item).read()
                if name=='node' or name.endswith(('.node','.so')): arm64(data,name)
                entries[name]=data
        if not manifest or manifest.get('target')!='linux-arm64' or manifest.get('version')!=package['version']:
            raise ValueError('Linux and supplied runtime versions do not match')
    for required in ['node','launcher.sh','native/snowluma-linux-arm64.node','native/snowluma-linux-arm64.so','native/websocket-linux-arm64.node']:
        if required not in entries: raise ValueError('Missing native component: '+required)
    if any('win32' in name or name.endswith(('.exe','.dll','.bat')) for name in entries):
        raise ValueError('A Windows executable remains in the port')
    entries['release.json']=(json.dumps(manifest,indent=2)+'\n').encode()
    port={'schema':1,'version':package['version'],'target':'linux-arm64','privateUseOnly':True,
        'javascriptPreservedVerbatim':sha(entries['index.mjs'])==original_main,'javascriptSHA256':original_main,
        'windowsInputSHA256':sha(windows.read_bytes()),'linuxInputSHA256':linux_sha256,
        'method':'Original supplied JavaScript and client assets; matching Linux ARM64 Node and native modules.',
        'files':{name:{'bytes':len(data),'sha256':sha(data)} for name,data in sorted(entries.items())}}
    entries['BIGFISH-PORT.json']=(json.dumps(port,ensure_ascii=False,indent=2)+'\n').encode()
    destination.mkdir(parents=True)
    for name,data in entries.items():
        path=destination/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        path.chmod(0o700 if name in ['node','launcher.sh'] else 0o600)
    write_linux_launcher(destination)
    print(json.dumps({key:port[key] for key in ['version','target','javascriptPreservedVerbatim','javascriptSHA256']},indent=2))
    return port

def write_linux_launcher(destination):
    launcher = destination / 'start-linux-arm64.sh'
    launcher.write_text("""#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
case "$(uname -m)" in aarch64|arm64) ;; *) echo 'This package requires Linux ARM64.' >&2; exit 1 ;; esac
if ! getconf GNU_LIBC_VERSION >/dev/null 2>&1; then
    echo 'A glibc Linux environment is required; Ubuntu 24.04 ARM64 is recommended.' >&2
    exit 1
fi
sha256sum --check --status checksums.sha256 || { echo 'Runtime checksum verification failed.' >&2; exit 1; }
./node ./check-node-version.cjs
exec ./launcher.sh "$@"
""")
    launcher.chmod(0o700)
    guide = Path(__file__).resolve().parents[2] / 'docs/SnowLuma-Linux-ARM64.md'
    shutil.copyfile(guide, destination / '使用说明.md')
    files = json.loads((destination / 'BIGFISH-PORT.json').read_text())['files']
    checksums = ''.join(files[name]['sha256'] + '  ' + name + '\n' for name in sorted(files))
    (destination / 'checksums.sha256').write_text(checksums)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--windows',type=Path,required=True);parser.add_argument('--linux',type=Path,required=True)
    parser.add_argument('--linux-sha256',required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();prepare(args.windows,args.linux,args.linux_sha256,args.output)
