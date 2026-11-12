#!/usr/bin/python3
"""Own the SnowLuma, local login desktop and QQ process groups as one service."""
import json, os, pathlib, secrets, shutil, signal, socket, subprocess, sys, time
QQ_LAYOUT_VERSION=2
ROOT=pathlib.Path('/manager/extensions/snowluma')
DESKTOP=pathlib.Path('/manager/extensions/desktop')
STATE=pathlib.Path('/persistent/snowluma')
RUNTIME=ROOT/'runtime'
QQ=ROOT/'qq'
children=[]
stopping=False

def stop(signum=None, frame=None):
    global stopping
    stopping=True

def atomic(path, value):
    path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_name(path.name+'.new')
    with open(temporary,'w') as f:
        os.fchmod(f.fileno(),0o600)
        json.dump(value,f,ensure_ascii=False,indent=2)
        f.flush(); os.fsync(f.fileno())
    os.replace(temporary,path)

def launch(args,env=None,cwd=None):
    child=subprocess.Popen(args,env=env,cwd=cwd,start_new_session=True)
    children.append(child)
    return child

def managed_qq():
    """Use the verified official package installed independently of the base guest.

    Existing guests intentionally survive APK upgrades. Selecting this managed
    tree therefore updates QQ for existing installations without resetting the
    Ubuntu image, account home, SnowLuma database or AstrBot configuration.
    """
    source=DESKTOP/'qq/opt/QQ'
    spec=json.loads((DESKTOP/'sources.json').read_text())['qq']
    marker=json.loads((DESKTOP/'qq.json').read_text())
    package=json.loads((source/'resources/app/package.json').read_text())
    if (marker.get('schemaVersion')!=1 or marker.get('version')!=spec['version'] or
            marker.get('archiveSHA256')!=spec['sha256'] or package.get('version')!=spec['version'] or
            package.get('main')!='./application.asar/app_launcher/index.js'):
        raise RuntimeError('QQ update is incomplete; restart the service to repair the bundled installation')
    if (not (source/'qq').is_file() or (source/'qq').is_symlink() or
            not (source/'resources/app/wrapper.node').is_file()):
        raise RuntimeError('The managed official QQ package is incomplete')
    return source

def prepare_ipc_directory():
    # PRoot rewrites AF_UNIX paths to Android's real app directory. The former
    # /manager/extensions/snowluma/qq-runtime path exceeds sun_path's limit
    # with our parallel-install ID. PRoot remaps such a bind to a temporary
    # file: direct connects can work, but SnowLuma's directory scan misses it.
    runtime=ROOT.parent.parent/'sl-ipc'
    runtime.mkdir(parents=True,exist_ok=True,mode=0o700)
    if runtime.is_symlink(): raise RuntimeError('SnowLuma IPC directory must be app-owned')
    runtime.chmod(0o700)
    # Test the translated kernel path, with a filename longer than the hook's
    # maximum PID socket name, before reporting a running QQ service.
    probe=runtime/('.check-'+secrets.token_hex(5)+'.control.sock')
    try:
        with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as check:
            check.bind(str(probe))
            if probe.name not in os.listdir(runtime):
                raise OSError('Socket is hidden from directory discovery by PRoot path remapping')
    except OSError as failure:
        raise RuntimeError('SnowLuma local IPC socket could not be created; check the app private storage path') from failure
    finally:
        probe.unlink(missing_ok=True)
    return runtime

def main():
    STATE.mkdir(parents=True,exist_ok=True)
    qq=managed_qq()
    # Write a dedicated profile instead of modifying the user's optional desktop profile.
    profile=ROOT/'compat-profile.json'
    atomic(profile,dict(library=str(RUNTIME/'native/snowluma-linux-arm64.so'),symbol='snowluma_linux_hook_start_dynamic',waitModule='wrapper.node',delayMs=5000))
    signal.signal(signal.SIGTERM,stop); signal.signal(signal.SIGINT,stop); signal.signal(signal.SIGHUP,stop)
    manager=json.loads((STATE/'manager.json').read_text())
    geometry=manager.get('desktopGeometry','720x960')
    if geometry not in ('720x960','1280x800'): raise RuntimeError('Unsupported QQ desktop size')
    env=dict(os.environ,DISPLAY=':20',XAUTHORITY=str(DESKTOP/'Xauthority'),
             BIGFISH_DESKTOP_GEOMETRY=geometry,BIGFISH_NATIVE_VIEW='1')
    # The native hook and its JS watcher must use the same socket directory,
    # including discovery before a QQ PID's environment can be inspected.
    runtime=prepare_ipc_directory()
    env.update(SNOWLUMA_HOOK_RUNTIME_DIR=str(runtime),XDG_RUNTIME_DIR=str(runtime))
    desktop=launch(['/bin/bash',str(DESKTOP/'desktop.sh')],env=env)
    deadline=time.monotonic()+30
    while subprocess.run(['/usr/bin/xdpyinfo','-display',':20'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        if stopping:return 0
        if desktop.poll() is not None or time.monotonic()>deadline: raise RuntimeError('QQ login desktop failed to start')
        time.sleep(.2)
    password=(STATE/'bootstrap-password').read_text().strip()
    port=manager['port']
    snow_env=dict(env,SNOWLUMA_HOOK_AUTOLOAD='0',SNOWLUMA_WEBUI_HOST='127.0.0.1',SNOWLUMA_WEBUI_PORT=str(port),SNOWLUMA_WEBUI_BOOTSTRAP_PASSWORD=password,SNOWLUMA_ACCEPT_EULA='1',SNOWLUMA_ACCEPT_PRIVACY='1',SNOWLUMA_LOG_MAX_TOTAL_MB='128',SNOWLUMA_LOG_RETAIN_DAYS='3')
    launch([str(DESKTOP/'guest-compat'),str(RUNTIME/'node'),str(RUNTIME/'index.mjs')],env=snow_env,cwd=STATE)
    home=STATE.parent/'home'
    for p in (home,home/'.config',home/'.cache',home/'.local/share'): p.mkdir(parents=True,exist_ok=True)
    qq_env=dict(env,HOME=str(home),XDG_CONFIG_HOME=str(home/'.config'),XDG_CACHE_HOME=str(home/'.cache'),XDG_DATA_HOME=str(home/'.local/share'),XDG_RUNTIME_DIR=str(runtime),ASTRBOT_COMPAT_PROFILE=str(profile),ASTRBOT_COMPAT_SELF_MEMORY='1')
    launch(['/usr/bin/dbus-run-session','--',str(DESKTOP/'guest-compat'),'--profile',str(qq/'qq'),'--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--in-process-gpu','--disable-gpu-compositing','--disable-features=Vulkan'],env=qq_env,cwd=qq)
    print('WhaleTalk: SnowLuma ARM64 and bundled QQ '+json.loads((qq/'resources/app/package.json').read_text())['version']+' login desktop started',flush=True)
    while not stopping:
        for child in children:
            if child.poll() is not None: raise RuntimeError('QQ service component exited with status '+str(child.returncode))
        time.sleep(.3)
    return 0

if __name__=='__main__':
    result=1
    try: result=main()
    except Exception as exc: print('WhaleTalk SnowLuma: '+str(exc),file=sys.stderr,flush=True)
    finally:
        for child in reversed(children):
            try: os.killpg(child.pid,signal.SIGTERM)
            except ProcessLookupError: pass
        deadline=time.monotonic()+8
        while any(child.poll() is None for child in children) and time.monotonic()<deadline: time.sleep(.1)
        # QQ can leave helpers after its main process exits: terminate every owned group.
        for child in reversed(children):
            try: os.killpg(child.pid,signal.SIGKILL)
            except ProcessLookupError: pass
        for child in children: child.wait()
    sys.exit(result)
