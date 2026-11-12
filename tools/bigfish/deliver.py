#!/usr/bin/env python3
"""Package the release and record the checks already completed; does not run tests."""
from pathlib import Path, PurePosixPath
import hashlib,json,shutil,subprocess,zipfile
root=Path(__file__).resolve().parents[2]
properties=dict(line.split('=',1) for line in (root/'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
version=properties['appVersionName'];dist=root/'dist';dist.mkdir(exist_ok=True)
raw=root/'app/build/outputs/apk/bundled/release/app-bundled-release.apk'
apk=dist/f'WhaleTalk-Android-{version}-arm64-v8a.apk'
if raw.resolve() != apk.resolve(): shutil.copyfile(raw,apk)
print('Release APK copied:',apk.name,apk.stat().st_size,flush=True)
archive=dist/f'WhaleTalk-Android-{version}-arm64-v8a.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as z:z.write(apk,apk.name)
print('APK ZIP created:',archive.name,archive.stat().st_size,flush=True)
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
receipt={'version':version,'versionCode':int(properties['appVersionCode']),'author':'mzbgq','apk':apk.name,'apkBytes':apk.stat().st_size,'apkSHA256':sha(apk),'zip':archive.name,'zipBytes':archive.stat().st_size,'zipSHA256':sha(archive),'build':'assembleBundledRelease completed successfully','acceptanceChecks':'Fixes complete-runtime readiness after obsolete QQ removal; preserves account data and legacy backup import. Native log categories include preparation details. Verified precompiled ARM64 compatibility library and package-marker recovery avoid unnecessary phone compilation/reinstallation. Tavern V1/V2/V3 PNG/JSON import, greeting/examples/static lore, 11 licensed or original cards; old prompt templates retained separately. Automated checks are not a guarantee for every physical phone or a real QQ account login.', 'deliveryStatus':'built_and_signed_further_android_testing_stopped_at_user_request','latestQqAdaptationComplete':False,'uploadStatus':'pending','privateUseOnly':True, 'qqBackend':'SnowLuma 2.4.2','qqVersion':'3.2.35-54395','githubNativeSourceRequired':False}
glass_file=root/'validation/studio-glass.json'
if glass_file.is_file(): receipt['historicalGlassChecks']=json.loads(glass_file.read_text())
snow_file=root/'validation/snowluma-arm64-smoke.json'
if snow_file.is_file(): receipt['snowLumaArm64Checks']=json.loads(snow_file.read_text())
for name in ['audit-1.3.2.json','studio-api-routes-1.3.2.json','qq-view-android-1.3.2.json','bundled-plugins-1.3.2.json','latest-qq-adaptation-1.3.3.json','status-command-1.3.4.json','mobile-optimization-1.3.4.json','clean-runtime-1.3.5.json','native-features-1.3.5.json','studio-api-routes-1.3.5.json','environment-arm64-1.3.6.json','desktop-preparation-1.3.6.json','unit-tests-1.3.6.json','android-runtime-1.3.6.json','release-build-1.3.6.json','upgrade-1.3.6.json']:
    report=root/'validation'/name
    if report.is_file(): receipt[name.removesuffix('.json')]=json.loads(report.read_text())
(root/'validation/delivery.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
source=dist/f'WhaleTalk-Android-{version}-source.zip'
names=subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=root).decode().split('\0')
count=0
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for name in sorted(set(filter(None,names))):
  parts=PurePosixPath(name).parts;p=root/name
  if set(parts).intersection({'.local','.git','.gradle','.kotlin','build','dist'}) or p.is_symlink() or not p.is_file():continue
  if name=='local.properties' or name.endswith(('.p12','.pfx','.jks','.keystore','.pem','.apk')):continue
  if name=='validation/current-ui.png':continue
  content=p.read_bytes()
  if any(marker in content for marker in [b'-----BEGIN '+b'PRIVATE KEY-----',b'-----BEGIN '+b'RSA PRIVATE KEY-----',b'-----BEGIN '+b'EC PRIVATE KEY-----']):raise RuntimeError('Private signing material must never be published')
  entry=zipfile.ZipInfo('BigFish-Android/'+name);entry.compress_type=zipfile.ZIP_DEFLATED
  entry.external_attr=(0o100755 if p.stat().st_mode & 0o111 else 0o100644)<<16
  z.writestr(entry,content);count+=1
print('Source archive created:',count,'files',flush=True)
for a,b in [('WHALETALK.md','安装说明.md'),('THIRD_PARTY.md','参考与复用.md'),('VALIDATION.md','交付说明.md')]:shutil.copyfile(root/a,dist/b)
items=[apk,archive,source,dist/'安装说明.md',dist/'参考与复用.md',dist/'交付说明.md']
(dist/'SHA256SUMS.txt').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in items))
print(json.dumps(receipt,ensure_ascii=False,indent=2),flush=True)
