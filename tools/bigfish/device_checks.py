#!/usr/bin/env python3
"""Install and test only the small disposable UI-check app, never a user's QQ or bot data."""
from pathlib import Path
import argparse
import os
import subprocess

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--skip-install", action="store_true", help="Keep an already installed and precompiled fixture on slow software emulators")
args = parser.parse_args()
base = [args.adb, "-s", args.serial]
def adb(*command, binary=False):
    result = subprocess.run(base + list(command), capture_output=True, text=not binary, check=True)
    return result.stdout
assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Emulator only"
sdk = Path(os.environ.get("ANDROID_HOME", ""))
aapt = sdk / "build-tools/35.0.0/aapt"
application = root / "app/build/outputs/apk/bootstrap/debug/app-bootstrap-debug.apk"
tests = root / "app/build/outputs/apk/androidTest/bootstrap/debug/app-bootstrap-debug-androidTest.apk"
metadata = subprocess.run([str(aapt), "dump", "badging", str(application)], capture_output=True, text=True, check=True).stdout
assert "name='dev.bigfish.nativebot.uicheck'" in metadata, "Refusing to install a non-fixture package"
assert application.stat().st_size < 100 * 1024 * 1024, "UI-check build accidentally contains large runtime assets; override optionalAssetsDir with an empty directory."
if not args.skip_install:
    for apk in [application, tests]: print(adb("install", "-r", str(apk)).strip(), flush=True)
adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "input", "keyevent", "KEYCODE_MENU")
validation = root / "validation"; validation.mkdir(exist_ok=True)
transcript = validation / "android-checks.txt"
with transcript.open("w") as log:
    result = subprocess.run(base + ["shell", "am", "instrument", "--no-window-animation", "-w", "dev.bigfish.nativebot.uicheck.test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
output = transcript.read_text()
print(output, flush=True)
assert result.returncode == 0, "ADB or instrumentation exited unsuccessfully"
assert "OK (10 tests)" in output and "FAILURES" not in output, "Device checks did not all pass"
for name in ["intro-light", "model-light", "model-dark-large-font", "features-search", "model-keyboard"]:
    data = adb("exec-out", "run-as", "dev.bigfish.nativebot.uicheck", "cat", "cache/bigfish-ui/" + name + ".png", binary=True)
    assert data.startswith(b"\x89PNG\r\n\x1a\n")
    (validation / (name + ".png")).write_bytes(data)
print("Android UI and persistence checks passed. UI fixtures only; no ARM PRoot execution or real QQ account.", flush=True)
