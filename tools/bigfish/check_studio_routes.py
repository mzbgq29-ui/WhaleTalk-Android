#!/usr/bin/env python3
"""Compare Studio's literal local API paths with pinned FastAPI router declarations.
Checks path existence and lists upstream methods for review; upstream-contract and UI tests cover other layers.
"""
import argparse, ast, json, re
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--astrbot-source',type=Path,required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[2];routes=[]
for path in (args.astrbot_source/'astrbot/dashboard/api').glob('*.py'):
 tree=ast.parse(path.read_text());prefixes={}
 for node in tree.body:
  if isinstance(node,ast.Assign) and isinstance(node.value,ast.Call) and isinstance(node.value.func,ast.Name) and node.value.func.id=='APIRouter':
   prefix=next((k.value.value for k in node.value.keywords if k.arg=='prefix' and isinstance(k.value,ast.Constant)), '')
   for target in node.targets:
    if isinstance(target,ast.Name):prefixes[target.id]=prefix or ('/api/v1' if target.id=='router' else '')
 for node in ast.walk(tree):
  if isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef)):
   for dec in node.decorator_list:
    if isinstance(dec,ast.Call) and isinstance(dec.func,ast.Attribute) and isinstance(dec.func.value,ast.Name) and dec.func.value.id in prefixes and dec.args and isinstance(dec.args[0],ast.Constant):
     if dec.func.attr in ('get','post','put','delete','patch','head'):
      routes.append((prefixes[dec.func.value.id]+dec.args[0].value,dec.func.attr.upper()))
checks=[]
for path in (root/'app/src/main/java/io/astrbot/android').glob('Studio*.kt'):
 for match in re.finditer(r'"(/api/[^"\n]*)"',path.read_text()):
  endpoint=match.group(1).split('?')[0]
  if endpoint == '/api/': continue  # validation prefix, not a request
  expansions={'/api/plugin/':['on','off'], '/api/persona/':['update','create'], '/api/kb/':['update','create']}
  if endpoint in expansions:
   for suffix in expansions[endpoint]:
    full=endpoint+suffix
    checks.append({'file':path.name,'endpoint':full,'matches':[{'path':p,'method':m} for p,m in routes if p==full]})
   continue
  pattern=re.escape(endpoint.split('$')[0])
  candidates=[{'path':p,'method':m} for p,m in routes if re.match('^'+re.sub(r'\\\{.*?\\\}',r'[^/]+',re.escape(p))+'$',endpoint) or ('$' in endpoint and p.startswith(endpoint.split('$')[0])) or p==endpoint]
  checks.append({'file':path.name,'endpoint':endpoint,'matches':candidates})
missing=[c for c in checks if not c['matches']]
report={'scope':'Static path existence against pinned AstrBot 4.29.3 FastAPI declarations; does not verify authenticated live behavior or arbitrary dynamic paths.', 'checked':len(checks),'missing':missing,'checks':checks}
(root/'validation/studio-api-routes-1.3.5.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({'checked':len(checks),'missing':missing},ensure_ascii=False,indent=2))
assert not missing
