#!/usr/bin/env python3
"""Package pinned third-party plugin sources and offline ARM64 add-on dependencies.
Run after fetch_candidates.py and pip download documented in docs/bundled-plugins.md.
No network access or account credentials are used by this step.
"""
import argparse,hashlib,json,zipfile,difflib
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--research',type=Path,required=True);p.add_argument('--wheels',type=Path,required=True);args=p.parse_args()
entries={};plugins=[]
selected=[('astrbot_plugin_memory_companion','0e22a01e680d5a43b2ca98dea8a550a16c7611fa','2.6.6','MIT'),('astrbot_plugin_group_chat_plus','aa97e0e35a18733e73db753238dd2a3021446e42','V1.2.3.hotfix.2','AGPL-3.0')]
for name,commit,version,license in selected:
 folder=args.research/name;proof=json.loads((folder/'research-provenance.json').read_text());assert proof['commit']==commit
 for file in sorted(folder.rglob('*')):
  if not file.is_file() or file.is_symlink() or any(x in file.parts for x in ['.git','__pycache__','.pytest_cache']):continue
  relative=file.relative_to(folder).as_posix()
  if relative=='research-provenance.json':continue
  content=file.read_bytes()
  if relative=='requirements.txt':
   entries[f'plugins/{name}/requirements.upstream.txt']=content
   content=(b'# BigFish mobile runtime profile. The source-deployed AstrBot SDK is supplied by the pinned host.\n# Original requirements retained as requirements.upstream.txt.\n' if name=='astrbot_plugin_memory_companion' else b'# BigFish mobile runtime profile: development-only pytest/hypothesis omitted.\n# Original requirements retained as requirements.upstream.txt. Argon2 binaries are bundled offline.\naiohttp>=3.8.0\nargon2-cffi>=23.1.0\n')
  if name=='astrbot_plugin_memory_companion' and relative=='core/injection.py':
   original=content.decode()
   old="""            for item_limit in dict.fromkeys(max(24, value) for value in candidates):
                candidate_line = self._memory_item_line(
                    item,
                    slot_name=slot_name,
                    compact=compact,
                    detail_limit=item_limit,
                    query_text=query_text,
                )
                trial = {**buckets, key: [*(buckets.get(key) or []), candidate_line]}
                if fits(render(trial)):
                    line = candidate_line
                    break
"""
   new="""            # mzbgq mobile patch: retain scope/expression guards under a tight budget.
            # Try the upstream compact representation before omitting a valid item.
            for concise in ([True] if compact else [False, True]):
                for item_limit in dict.fromkeys(max(24, value) for value in [*candidates, 40, 24]):
                    candidate_line = self._memory_item_line(
                        item,
                        slot_name=slot_name,
                        compact=concise,
                        detail_limit=item_limit,
                        query_text=query_text,
                    )
                    trial = {**buckets, key: [*(buckets.get(key) or []), candidate_line]}
                    if fits(render(trial)):
                        line = candidate_line
                        break
                if line:
                    break
"""
   assert original.count(old)==1
   modified=original.replace(old,new)
   entries[f'plugins/{name}/core/injection.upstream.py']=content
   content=modified.encode()
   entries[f'plugins/{name}/bigfish-patches/memory-budget.patch']=''.join(difflib.unified_diff(original.splitlines(True),modified.splitlines(True),fromfile='upstream/core/injection.py',tofile='mobile/core/injection.py')).encode()
  entries[f'plugins/{name}/{relative}']=content
 plugins.append({**proof,'id':name,'version':version,'license':license})
wheels=[]
for file in sorted(args.wheels.glob('*.whl')):
 assert file.name.startswith(('argon2_cffi-25.1.0-','argon2_cffi_bindings-25.1.0-'))
 blob=file.read_bytes();wheels.append({'file':file.name,'sha256':hashlib.sha256(blob).hexdigest()})
 with zipfile.ZipFile(file) as wheel:
  for name in wheel.namelist():
   if name.endswith('/'):continue
   assert '..' not in Path(name).parts and not name.startswith('/') and '.data/' not in name
   content=wheel.read(name)
   if name.endswith('.so'):assert content.startswith(b'\x7fELF') and int.from_bytes(content[18:20],'little')==183
   entries['python/'+name]=content
assert len(wheels)==2
out=root/'app/src/main/assets/bigfish';out.mkdir(exist_ok=True)
archive=out/'bundled-plugins.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for name,data in sorted(entries.items()):
  info=zipfile.ZipInfo(name,(2026,11,11,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100600<<16
  z.writestr(info,data)
manifest={'schema':1,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'bytes':archive.stat().st_size,'unpackedBytes':sum(map(len,entries.values())),
 'plugins':plugins,'dependencies':wheels,'files':{name:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for name,data in sorted(entries.items())},
 'profile':'No extra API credentials. Memory embedding and reranking disabled; current chat model used only for optional summaries. Group Chat Plus developer requirements omitted; original source requirements retained. Voice, image services and autonomous idle sending disabled.'}
(out/'bundled-plugins.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
legal=root/'docs/legal/bundled-plugins';legal.mkdir(parents=True,exist_ok=True)
for plugin in plugins:(legal/(plugin['id']+'-LICENSE.txt')).write_bytes((args.research/plugin['id']/'LICENSE').read_bytes())
(legal/'provenance.json').write_text(json.dumps({k:v for k,v in manifest.items() if k!='files'},ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in manifest.items() if k not in ['files','plugins']},ensure_ascii=False,indent=2))
