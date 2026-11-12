#!/usr/bin/env python3
"""Exercise desktop launch profiles using inert tools, no display or QQ account."""
from pathlib import Path
import os,signal,subprocess,tempfile,time,json
root=Path(__file__).resolve().parents[2]
source=(root/'app/src/main/assets/runtime/desktop/desktop.sh').read_text()
with tempfile.TemporaryDirectory(prefix='bigfish-mobile-desktop-') as temporary:
 base=Path(temporary);bin_dir=base/'bin';bin_dir.mkdir()
 stub=bin_dir/'fixture-tool'
 stub.write_text('''#!/usr/bin/python3
import os,sys,time,json,pathlib
p=pathlib.Path(os.environ['DESKTOP_PROBE']);name=pathlib.Path(sys.argv[0]).name
with (p/'calls.jsonl').open('a') as f:f.write(json.dumps([name,*sys.argv[1:]])+'\\n')
if name=='xdpyinfo':
 counter=p/'counter';n=int(counter.read_text())+1 if counter.exists() else 1;counter.write_text(str(n));sys.exit(1 if n==1 else 0)
time.sleep(20)
''');stub.chmod(0o700)
 for name in ['xdpyinfo','Xvfb','fluxbox','x11vnc','websockify']:(bin_dir/name).symlink_to(stub)
 for native,geometry in [(True,'720x960'),(False,'1280x800')]:
  folder=base/str(native);folder.mkdir();(folder/'logs').mkdir();(folder/'vnc.passwd').write_text('fixture');(folder/'Xauthority').write_text('fixture')
  script=folder/'desktop.sh';script.write_text(source.replace('desktop_root=/manager/extensions/desktop','desktop_root='+str(folder)))
  env=dict(os.environ,PATH=str(bin_dir)+':'+os.environ['PATH'],DESKTOP_PROBE=str(folder),BIGFISH_NATIVE_VIEW='1' if native else '0',BIGFISH_DESKTOP_GEOMETRY=geometry)
  with (folder/'output').open('w') as output:
   process=subprocess.Popen(['bash',str(script)],env=env,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
   try:
    expected={'Xvfb','fluxbox','x11vnc'}| (set() if native else {'websockify'})
    deadline=time.monotonic()+5
    while True:
     log=folder/'calls.jsonl';calls=[json.loads(s) for s in log.read_text().splitlines()] if log.exists() else []
     if expected <= {x[0] for x in calls}:break
     if process.poll() is not None or time.monotonic()>deadline:raise AssertionError((native,(folder/'output').read_text()))
     time.sleep(.05)
    assert ('websockify' in {x[0] for x in calls}) == (not native)
    assert geometry+'x24' in next(x for x in calls if x[0]=='Xvfb')
    vnc=next(x for x in calls if x[0]=='x11vnc');assert vnc[vnc.index('-wait')+1]=='200';assert vnc[vnc.index('-defer')+1]=='40'
   finally:
    if process.poll() is None:os.killpg(process.pid,signal.SIGTERM)
    try:process.wait(timeout=5)
    except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
  print(('native phone' if native else 'generic desktop')+': geometry, bounded polling, relay selection and child cleanup passed')
 bad=dict(env,BIGFISH_DESKTOP_GEOMETRY='99999x99999')
 result=subprocess.run(['bash',str(script)],env=bad,capture_output=True,text=True,timeout=5)
 assert result.returncode!=0 and 'unsupported display size' in result.stderr
 print('invalid display size rejected')
