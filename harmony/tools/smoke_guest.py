#!/usr/bin/env python3
"""Boot the actual ARM64 image with Linux QEMU and verify guest services.

Cloud x86 TCG verifies guest Linux integration, not HarmonyOS API 20 policy,
device performance, QQ account login or a real end-to-end QQ conversation.
"""
import argparse
import json
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def main(args):
    args.output.mkdir(parents=True, exist_ok=True)
    share = (args.output / "share").resolve()
    share.mkdir()
    token = secrets.token_hex(32)
    (share / "whaletalk-bootstrap.json").write_text(json.dumps({"apiToken": token}))
    mappings = {port: free_port() for port in (8765, 6185, 6081, 5099)}
    network = "user,id=net0" + "".join(f",hostfwd=tcp:127.0.0.1:{host}-:{guest}" for guest, host in mappings.items())
    overlay = (args.output / "smoke-overlay.qcow2").resolve()
    subprocess.run(["qemu-img", "create", "-q", "-f", "qcow2", "-F", "qcow2", "-b", str(args.image.resolve()), str(overlay)], check=True)
    command = ["qemu-system-aarch64", "-machine", "virt,accel=tcg", "-cpu", "cortex-a72", "-smp", str(args.cpus), "-m", str(args.memory),
               "-nographic", "-no-reboot", "-kernel", str(args.kernel.resolve()), "-append", "root=/dev/sda rw rootwait rootfstype=ext4 console=ttyAMA0 init=/init",
               "-drive", f"if=none,format=qcow2,file={overlay},id=disk0,cache=writeback", "-device", "virtio-scsi-pci,id=scsi0",
               "-device", "scsi-hd,drive=disk0,bus=scsi0.0,logical_block_size=4096,physical_block_size=4096",
               "-device", "virtio-net-pci,netdev=net0", "-netdev", network,
               "-fsdev", f"local,security_model=mapped-file,id=share,path={share}", "-device", "virtio-9p-pci,fsdev=share,mount_tag=hostshare",
               "-device", "virtio-rng-pci"]
    (args.output / "qemu-command.json").write_text(json.dumps(command, indent=2))
    origin = "http://127.0.0.1:" + str(mappings[8765])
    def request(path, data=None):
        body = None if data is None else json.dumps(data).encode()
        req = urllib.request.Request(origin + path, data=body, headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)
    report = {"schema": 1, "guestBooted": False, "runtimeImports": False, "astrbotHttp": False, "qqLoginDesktop": False, "snowlumaHttp": False,
              "qqAccountLogin": "requires-device-and-account", "qqMessageRoundTrip": "requires-device-and-account"}
    success = False
    with (args.output / "serial.log").open("wb") as serial:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=serial, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + args.timeout
            last_status = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("QEMU exited before guest checks completed")
                try:
                    request("/health")
                    report["guestBooted"] = True
                    status = request("/api/v1/status")
                    if status != last_status:
                        (args.output / "status.json").write_text(json.dumps(status, indent=2))
                        last_status = status
                    report["runtimeImports"] = status["runtimeCheck"] == "passed"
                    report["astrbotHttp"] = status["services"]["astrbot"]["ready"]
                    report["qqLoginDesktop"] = status["services"]["qq"]["ready"]
                    for guest_port, key in ((6185, "astrbotHttp"), (6081, "noVncHttp"), (5099, "snowlumaHttp")):
                        try:
                            path = "/vnc.html" if guest_port == 6081 else "/"
                            with urllib.request.urlopen(f"http://127.0.0.1:{mappings[guest_port]}{path}", timeout=3) as response:
                                report[key] = 200 <= response.status < 400
                        except (OSError, urllib.error.HTTPError):
                            report[key] = False
                    if all(report.get(key) is True for key in ("guestBooted", "runtimeImports", "astrbotHttp", "qqLoginDesktop", "noVncHttp", "snowlumaHttp")):
                        success = True
                        break
                except (OSError, ValueError, KeyError):
                    pass
                time.sleep(3)
            for service in ("manager", "astrbot", "qq"):
                try:
                    value = request(f"/api/v1/logs?service={service}&lines=300")
                    (args.output / (service + ".log")).write_text("\n".join(value["lines"]) + "\n")
                except Exception:
                    pass
            try:
                request("/api/v1/shutdown", {})
                process.wait(timeout=45)
                report["orderlyShutdown"] = process.returncode == 0
            except (OSError, subprocess.TimeoutExpired):
                report["orderlyShutdown"] = False
        except Exception as exc:
            report["error"] = str(exc)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
    report["passed"] = success and report.get("orderlyShutdown", False)
    (args.output / "smoke-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kernel", type=Path, required=True)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--memory", type=int, default=3072)
    parser.add_argument("--cpus", type=int, default=2)
    main(parser.parse_args())
