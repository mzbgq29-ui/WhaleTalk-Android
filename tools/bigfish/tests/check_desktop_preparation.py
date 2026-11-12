#!/usr/bin/env python3
"""Offline regressions for package-marker upgrades and bundled ARM64 compatibility."""
import ast,hashlib,json,shutil,subprocess,tempfile
from pathlib import Path
from unittest.mock import patch
project=Path(__file__).resolve().parents[3]
source=project/'app/src/main/assets/runtime/desktop/install.py'
tree=ast.parse(source.read_text());selected=[n for n in tree.body if isinstance(n,(ast.Import,ast.ImportFrom,ast.FunctionDef))]
module={};exec(compile(ast.fix_missing_locations(ast.Module(body=selected,type_ignores=[])),str(source),'exec'),module)
checks=[]
with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp);module.update(ROOT=root,MARKER=root/'desktop.json',PACKAGES_MARKER=root/'packages.json',PENDING=root/'desktop.pending',COMPATIBILITY_FILES=['guest_compat.c','proc_locks.py','guest-compat'],SOURCES={'architecture':'arm64'})
    (root/'bundled-packages').mkdir();bundle={'schemaVersion':1,'architecture':'arm64','baseRuntimeSha256':'new-image','packages':[{'name':'fixture','architecture':'arm64','version':'1.0'}]}
    manifest=root/'bundled-packages/manifest.json';manifest.write_text(json.dumps(bundle));marker=module['PACKAGES_MARKER']
    module['desktop_tools_ready']=lambda:True;module['installed_packages']=lambda:{('fixture','arm64'):'1.0'}
    marker.write_text('{"schemaVersion":1,"baseRuntimeSHA256":"old-image","bundleSHA256":"old"}')
    assert module['preinstalled_packages_ready']()
    assert json.loads(marker.read_text())['baseRuntimeSHA256']=='new-image';checks.append('old-image marker rebased only after actual package inventory validation')
    marker.write_text('{broken');assert module['preinstalled_packages_ready']();checks.append('malformed marker repaired from package inventory')
    module['installed_packages']=lambda:{('fixture','arm64'):'2.0'}
    assert module['preinstalled_packages_ready']();checks.append('newer user packages retained without reinstall loop')
    before=marker.read_bytes();module['installed_packages']=lambda:{}
    assert not module['preinstalled_packages_ready']() and marker.read_bytes()==before;checks.append('missing dependency never promoted as ready')
    module['installed_packages']=lambda:{('fixture','arm64'):'1.0'};module['desktop_tools_ready']=lambda:False
    assert not module['preinstalled_packages_ready']();checks.append('missing executable causes offline repair')
    module['desktop_tools_ready']=lambda:True;module['PENDING'].write_text('{}')
    assert not module['preinstalled_packages_ready']();module['PENDING'].unlink();checks.append('interrupted package transaction requires recovery')
    for name in module['COMPATIBILITY_FILES']+['compat-binary.json','libguest-compat.arm64.bin']:
        shutil.copyfile(source.parent/name,root/name)
    def forbidden_compiler(*args,**kwargs):
        assert args[0]!='gcc','Phone compiler unexpectedly invoked'
        return subprocess.CompletedProcess(args,0)
    module['run']=forbidden_compiler
    module['install_compatibility']()
    installed=(root/'libguest-compat.so').read_bytes()
    assert installed==(root/'libguest-compat.arm64.bin').read_bytes();checks.append('valid bundled compatibility library installs without invoking GCC')
    target=root/'libguest-compat.arm64.bin';data=bytearray(target.read_bytes());data[-1]^=1;target.write_bytes(data)
    try:module['install_compatibility']()
    except AssertionError:pass
    else:raise AssertionError('Corrupted compatibility library accepted')
    assert (root/'libguest-compat.so').read_bytes()==installed and not (root/'libguest-compat.so.new').exists();checks.append('corrupt library rejected and previous installation preserved')
    spec=json.loads((root/'compat-binary.json').read_text());h=hashlib.sha256()
    for name in module['COMPATIBILITY_FILES']:h.update((root/name).read_bytes())
    assert h.hexdigest()==spec['sourceSHA256'];checks.append('binary binds to exact current compatibility sources')
report={'checks':checks,'result':'passed','count':len(checks),'scope':'No apt/dpkg installation, network, QQ account or model API; dependency comparison uses native dpkg comparator.'}
(project/'validation/desktop-preparation-1.3.6.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
