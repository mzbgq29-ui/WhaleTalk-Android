#!/usr/bin/env python3
"""Derive a clean bot image from pinned inputs; remove the obsolete QQ bridge/client.
Only run on build assets, never on a user's installed guest.
"""
from pathlib import Path
import hashlib,json,tarfile,gzip,io,copy,shutil,zipfile
root=Path(__file__).resolve().parents[2];assets=root/'.local/inputs/assets';work=Path('/dev/shm/bigfish-clean-runtime');work.mkdir(exist_ok=True)
def digest(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def encoded(value):return (json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode()
def chain(parts):
 h=hashlib.sha256()
 for p in parts:h.update(p.encode());h.update(b'\0')
 return h.hexdigest()
base=assets/'bootstrap/arm64-v8a';manifest=json.loads((base/'manifest.json').read_text());oldsha=manifest['sha256']
assert digest(base/'ubuntu.tar.bin')==oldsha
if manifest.get('bigfishCleanRuntime'):
 print('Clean runtime already prepared');raise SystemExit(0)
qq=json.loads((root/'app/src/main/assets/bigfish/qq-package.json').read_text())
snow=json.loads((assets/'snowluma/arm64-v8a/manifest.json').read_text())
components=copy.deepcopy(manifest['components']);components.pop('napcat',None)
components['snowluma']={'version':snow['version'],'runtimeSHA256':snow['sha256']}
components['qq']={'version':qq['version'],'arm64-v8a':{'url':qq['url'],'sha256':qq['sha256']},'installation':'separate verified managed package, outside base image'}
needed={'opt/astrbot/astrbot/__init__.py','opt/qq/resources/app/app_launcher/napcat/napcat.mjs','opt/qq/resources/app/package.json','opt/astrbot-venv/pyvenv.cfg','opt/astrbot/.astrbot-source.json','var/lib/astrbot-manager/astrbot-python.lock.txt'}
found={};removed=[];unpacked=0;count=0
out=work/'ubuntu.tar.bin'
def reject(n):return n=='opt/qq' or n.startswith('opt/qq/') or ('napcat' in n.lower() and not n.startswith('opt/astrbot/'))
def add_json(archive,name,value):
 global unpacked,count
 data=encoded(value);t=tarfile.TarInfo(name);t.size=len(data);t.mode=0o600;t.uid=t.gid=0;archive.addfile(t,io.BytesIO(data));unpacked+=len(data);count+=1
with out.open('wb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',compresslevel=6,mtime=0) as gz,tarfile.open(fileobj=gz,mode='w|') as target,tarfile.open(base/'ubuntu.tar.bin',mode='r|gz') as source:
 for entry in source:
  n=entry.name.removeprefix('./');entry=copy.copy(entry);entry.name=n
  if n in needed:
   data=source.extractfile(entry).read();found[n]=data.decode();stream=io.BytesIO(data)
  else:stream=source.extractfile(entry) if entry.isfile() else None
  if reject(n):removed.append({'name':n,'bytes':entry.size});continue
  if n.endswith('.astrbot-components.json'):
   data=encoded(components);entry.size=len(data);stream=io.BytesIO(data)
  target.addfile(entry,stream);count+=1;unpacked+=entry.size if entry.isfile() else 0
 assert needed<=found.keys(),needed-found.keys()
 backend=found['opt/qq/resources/app/app_launcher/napcat/napcat.mjs']
 assert '/* astrbot-android-cpu-v1 */' not in backend and '/* astrbot-android-memory-v1 */' not in backend
 legacy_key=chain([oldsha,found['opt/astrbot/astrbot/__init__.py'],backend,found['opt/qq/resources/app/package.json'],found['opt/astrbot-venv/pyvenv.cfg'],found['opt/astrbot/.astrbot-source.json'],found['var/lib/astrbot-manager/astrbot-python.lock.txt']])
 sdk=chain([found['opt/astrbot/astrbot/__init__.py'],found['opt/astrbot-venv/pyvenv.cfg'],found['opt/astrbot/.astrbot-source.json'],found['var/lib/astrbot-manager/astrbot-python.lock.txt']])
 identity={'schema':1,'sdkFingerprint':sdk,'legacyCompatibilityKeys':[legacy_key]}
 add_json(target,'var/lib/bigfish/legacy-backup-identity.json',identity)
 add_json(target,'var/lib/bigfish/runtime-components.json',components)
newsha=digest(out)
manifest.update(id='ubuntu24.04.3-astrbot4.29.3-snowluma2.4.2',sha256=newsha,unpackedBytes=unpacked,components=components,bigfishCleanRuntime=True,derivedFromRuntimeSHA256=oldsha)
# The package closure is unchanged; bind its committed offline marker to the new base.
bundle_file=assets/'desktop-packages/arm64-v8a/manifest.json';bundle=json.loads(bundle_file.read_text());bundle['baseRuntimeSha256']=newsha;bundle_bytes=encoded(bundle);bundle_sha=hashlib.sha256(bundle_bytes).hexdigest()
snap=assets/'desktop-snapshot/arm64-v8a';snapshot=json.loads((snap/'manifest.json').read_text());snapshot_out=work/'desktop.tar.bin';snapshot_size=0;snapshot_count=0
with snapshot_out.open('wb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',compresslevel=6,mtime=0) as gz,tarfile.open(fileobj=gz,mode='w|') as target,tarfile.open(snap/'desktop.tar.bin',mode='r|gz') as source:
 for entry in source:
  entry=copy.copy(entry);n=entry.name.removeprefix('./');entry.name=n
  if reject(n):continue
  stream=source.extractfile(entry) if entry.isfile() else None
  if n=='var/lib/astrbot-manager/desktop-packages.json':
   value=json.loads(stream.read());value.update(baseRuntimeSHA256=newsha,bundleSHA256=bundle_sha);data=encoded(value);entry.size=len(data);stream=io.BytesIO(data)
  target.addfile(entry,stream);snapshot_count+=1;snapshot_size+=entry.size if entry.isfile() else 0
snapshot.update(baseRuntimeSHA256=newsha,bundleSHA256=bundle_sha,sha256=digest(snapshot_out),bytes=snapshot_out.stat().st_size,unpackedBytes=snapshot_size,entryCount=snapshot_count,derivedFromSnapshotSHA256=snapshot['sha256'])
# Independently scan the new archive before replacing any pinned build asset.
with tarfile.open(out,mode='r|gz') as archive:
 names=[]
 for e in archive:
  n=e.name.removeprefix('./');assert not reject(n),n;names.append(n)
 assert 'opt/astrbot/main.py' in names and 'usr/bin/python3.12' in names
for p in [base/'ubuntu.tar.bin',snap/'desktop.tar.bin']:
 staged=p.with_suffix('.clean-new');shutil.copyfile(out if p.parent==base else snapshot_out,staged);staged.replace(p)
(base/'manifest.json').write_bytes(encoded(manifest));(snap/'manifest.json').write_bytes(encoded(snapshot));bundle_file.write_bytes(bundle_bytes);(root/'runtime/desktop-packages.lock.json').write_bytes(bundle_bytes)
(root/'runtime/projects.lock.json').write_bytes(encoded(components))
notice=assets/'third-party';index=json.loads((notice/'manifest.json').read_text());index['runtimeSha256']=newsha;index['derivedFromRuntimeSha256']=oldsha
with zipfile.ZipFile(notice/'notices.zip') as source,zipfile.ZipFile(notice/'notices.clean-new.zip','w',zipfile.ZIP_DEFLATED) as target:
 for e in source.infolist():
  if '/opt/qq/' in e.filename or 'napcat' in e.filename.lower():continue
  target.writestr(e,source.read(e))
(notice/'notices.clean-new.zip').replace(notice/'notices.zip')
with zipfile.ZipFile(notice/'notices.zip') as z:index['names']=z.namelist()
index['noticesSha256']=digest(notice/'notices.zip');(notice/'manifest.json').write_bytes(encoded(index))
report={'sourceSHA256':oldsha,'runtimeSHA256':newsha,'runtimeBytes':(base/'ubuntu.tar.bin').stat().st_size,'removedEntries':removed,'removedUnpackedBytes':sum(x['bytes'] for x in removed),'entryCount':count,'legacyBackupIdentity':identity,'desktopSnapshotSHA256':snapshot['sha256'],'scope':'Build image cleaned. Existing phone data cleanup is handled separately with account/config preservation.'}
(root/'validation/clean-runtime-1.3.5.json').write_bytes(encoded(report));print(json.dumps({k:v for k,v in report.items() if k!='removedEntries'},indent=2),flush=True)
shutil.rmtree(work)
