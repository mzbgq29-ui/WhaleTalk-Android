import sys, pathlib, importlib.util, tempfile, json, shutil
sys.dont_write_bytecode=True
source=pathlib.Path(__file__).resolve().parents[2]/'app/src/main/assets/runtime/bigfish_snowluma.py'
spec=importlib.util.spec_from_file_location('launcher',source);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
# An APK upgrade must select the freshly installed official QQ even when the
# user's Ubuntu guest and its older /opt/qq are deliberately retained.
with tempfile.TemporaryDirectory() as temp:
 root=pathlib.Path(temp);m.DESKTOP=root/'desktop';source=m.DESKTOP/'qq/opt/QQ'
 app=source/'resources/app';app.mkdir(parents=True)
 (app/'package.json').write_text(json.dumps({'version':'3.2.35-54395','main':'./application.asar/app_launcher/index.js'}))
 (app/'wrapper.node').write_bytes(b'fixture')
 shutil.copy2('/bin/sh',source/'qq')
 (m.DESKTOP/'sources.json').write_text(json.dumps({'qq':{'version':'3.2.35-54395','sha256':'verified-archive'}}))
 marker={'schemaVersion':1,'version':'3.2.35-54395','archiveSHA256':'verified-archive'}
 (m.DESKTOP/'qq.json').write_text(json.dumps(marker))
 assert m.managed_qq()==source
 for mutation in ('wrong-version','wrong-hash','redirected-entry','symlink-executable'):
  package={'version':'3.2.35-54395','main':'./application.asar/app_launcher/index.js'}
  value=dict(marker)
  if mutation=='wrong-version':value['version']='old'
  if mutation=='wrong-hash':value['archiveSHA256']='wrong'
  if mutation=='redirected-entry':package['main']='./loadNapCat.js'
  if mutation=='symlink-executable':
   (source/'qq').unlink();(source/'qq').symlink_to('/bin/sh')
  (app/'package.json').write_text(json.dumps(package))
  (m.DESKTOP/'qq.json').write_text(json.dumps(value))
  try:m.managed_qq()
  except RuntimeError:pass
  else:raise AssertionError('Unsafe package accepted: '+mutation)
 print('Managed QQ upgrade: selects new package without replacing guest; rejects stale version, hash, entry and symlink executable')
