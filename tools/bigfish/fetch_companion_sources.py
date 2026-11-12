#!/usr/bin/env python3
"""Fetch the exact, unmodified source archives used by the bundled companion profile."""
import argparse,hashlib,io,json,tarfile,urllib.request
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
proof=json.loads((root/'docs/legal/bundled-plugins/provenance.json').read_text())
for plugin in proof['plugins']:
 url='https://codeload.github.com/'+plugin['repo']+'/tar.gz/'+plugin['commit']
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'BigFish-personal-build'}),timeout=60) as response:
  blob=response.read(50*1024*1024+1)
 assert len(blob)<=50*1024*1024 and hashlib.sha256(blob).hexdigest()==plugin['archiveSHA256']
 folder=args.output/plugin['id'];folder.mkdir(parents=True,exist_ok=True)
 with tarfile.open(fileobj=io.BytesIO(blob),mode='r:gz') as archive:
  for entry in archive:
   parts=Path(entry.name).parts[1:]
   assert '..' not in parts
   if not entry.isfile() or not parts:continue
   target=folder.joinpath(*parts)
   assert target.resolve().is_relative_to(folder.resolve())
   target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.extractfile(entry).read())
 (folder/'research-provenance.json').write_text(json.dumps(plugin,ensure_ascii=False,indent=2))
 print(plugin['id'],plugin['commit'],flush=True)
