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
compare_versions = module['compare_debian_versions']
package_at_least = module['package_at_least']
orig_installed_packages = module['installed_packages']
with patch.object(subprocess, 'run', side_effect=AssertionError('Version comparison spawned a subprocess')):
    for older, newer in (
        ('99.0', '1:0.1'), ('9:99.0', '10:0.1'),
        ('1.0~beta1', '1.0'), ('1.0~rc1', '1.0~rc2'), ('1.0~~', '1.0~'),
        ('1.0-1', '1.0-2'), ('1.0-hot-1', '1.0-hot-2'), ('1.0-99', '1.1-1'),
        ('1.2', '1.10'), ('1.0', '1.0a'), ('1.0a', '1.0+'), ('1.0+', '1.0.'),
    ):
        assert compare_versions(older, newer) < 0 and compare_versions(newer, older) > 0, (older, newer)
        assert not package_at_least(older, newer) and package_at_least(newer, older)
    for left, right in (('1.0-1', '1.0-1'), ('0:1.02-01', '1.2-1'), ('1.0', '1.0-0')):
        assert compare_versions(left, right) == 0 and compare_versions(right, left) == 0
        assert package_at_least(left, right) and package_at_least(right, left)
    assert not package_at_least(None, '1.0')
checks.append('pure-Python Debian versions compare epochs, tildes, revisions, numeric runs and ASCII ordering')
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
    with patch.object(subprocess, 'run', side_effect=AssertionError('Version comparison spawned a subprocess')):
        assert module['preinstalled_packages_ready']()
    checks.append('newer user packages retained without reinstall loop')
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
    status_file=root/'dpkg-status'
    status_file.write_text(
        'Package: xvfb\nStatus: install ok installed\nArchitecture: arm64\nVersion: 2:21.1.12-1ubuntu1.1\n'
        'Description: Virtual Framebuffer fake X server\n continued line: ignored\n\n'
        'Package: libfoo\nStatus: install ok installed\nArchitecture: arm64\nMulti-Arch: same\nVersion: 1.2.3-4\n\n'
        'Package: removed-pkg\nStatus: deinstall ok config-files\nArchitecture: arm64\nVersion: 0.9\n\n'
        'Package: unpacked-pkg\nStatus: install ok unpacked\nArchitecture: arm64\nVersion: 0.8\n',
        encoding='utf-8',
    )
    saved_installed=module['installed_packages']
    try:
        module['installed_packages']=orig_installed_packages
        module['DPKG_STATUS']=status_file
        with patch.object(subprocess, 'check_output', side_effect=AssertionError('dpkg-query spawned a subprocess')):
            parsed=module['read_dpkg_status'](status_file)
            assert parsed=={
                ('xvfb','arm64'):{'version':'2:21.1.12-1ubuntu1.1','multi_arch':''},
                ('libfoo','arm64'):{'version':'1.2.3-4','multi_arch':'same'},
            }, parsed
            assert module['installed_packages']()=={('xvfb','arm64'):'2:21.1.12-1ubuntu1.1',('libfoo','arm64'):'1.2.3-4'}
            assert module['installed_package_lines'](['xvfb','libfoo'])==['xvfb\t2:21.1.12-1ubuntu1.1','libfoo:arm64\t1.2.3-4']
    finally:
        module['installed_packages']=saved_installed
        module.pop('DPKG_STATUS',None)
    checks.append('dpkg status reader resolves installed packages and Multi-Arch without spawning dpkg-query')
report={'checks':checks,'result':'passed','count':len(checks),'scope':'No apt/dpkg installation, network, QQ account or model API; dependency checks use pure-Python Debian version comparison.'}
(project/'validation/desktop-preparation-1.3.6.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
