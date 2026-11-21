#!/usr/bin/env python3
"""Host checks for archive boundaries, the HAP API and real child reaping."""
import importlib.util
import io
import json
from pathlib import Path
import socket
import socketserver
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.request


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


BASE = Path(__file__).resolve().parents[1]
builder = module("builder", BASE / "tools/build_guest.py")
guest = module("guest", BASE / "guest/supervisor.py")


def tar(entries):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode="w") as archive:
        for name, kind, content in entries:
            info = tarfile.TarInfo(name)
            if kind == "file":
                payload = content.encode()
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
            else:
                info.type = tarfile.SYMTYPE
                info.linkname = content
                archive.addfile(info)
    data.seek(0)
    return data


with tempfile.TemporaryDirectory() as directory:
    root = Path(directory) / "root"
    builder.unpack_tar(tar([("usr/lib/fixture", "file", "verified"), ("lib", "link", "/usr/lib"), ("lib/second", "file", "safe")]), root)
    assert (root / "lib/fixture").read_text() == "verified"
    assert (root / "usr/lib/second").read_text() == "safe"
    escaped_name = r"usr/lib/systemd/system/system-systemd\x2dcryptsetup.slice"
    escaped_link = r"usr/lib/systemd/system/multi-user.target.wants/system-systemd\x2dcryptsetup.slice"
    builder.unpack_tar(tar([(escaped_name, "file", "[Slice]\n"),
                            (escaped_link, "link", r"../system-systemd\x2dcryptsetup.slice")]), root)
    assert (root / escaped_name).read_text() == "[Slice]\n"
    assert (root / escaped_link).read_text() == "[Slice]\n"
    assert not (root / escaped_name.replace(r"\x2d", "-")).exists(), "POSIX escapes were incorrectly decoded"
    for name in ("/absolute", "../outside", "nested/../outside", "nul\0name"):
        try:
            builder.name_path(name)
        except ValueError:
            pass
        else:
            raise AssertionError("Unsafe archive name was accepted")
    for entries in ([('../outside', 'file', 'escape')], [('escape', 'link', '../../outside')],
                    [(".././", "file", "escape")], [("absolute-link", "link", "/../outside")]):
        try:
            builder.unpack_tar(tar(entries), root)
        except ValueError:
            pass
        else:
            raise AssertionError("Archive escape was accepted")
    guest.STATE = Path(directory) / "persistent"
    guest.MANAGER = Path(directory) / "manager"
    guest.DATA = guest.STATE / "astrbot/data"
    guest.PRODUCT = Path(directory) / "product"
    guest.LOGS = Path(directory) / "logs"
    guest.LOGS.mkdir()
    for name in ("manager", "astrbot", "qq"):
        (guest.LOGS / (name + ".log")).write_text('Authorization: Bearer secret-test-value\n{"api_key":"private-value"}\nvisible\n')
    supervisor = guest.Supervisor()
    supervisor.token = "a" * 64
    server = guest.ThreadingHTTPServer(("127.0.0.1", 0), guest.handler(supervisor))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    origin = "http://127.0.0.1:" + str(server.server_port)
    def request(path, value=None, authorized=True):
        headers = {"Authorization": "Bearer " + supervisor.token} if authorized else {}
        if value is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(origin + path, headers=headers, data=None if value is None else json.dumps(value).encode())
        try:
            with urllib.request.urlopen(req, timeout=3) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as exc:
            return exc.code, json.load(exc)
    try:
        assert request("/health", authorized=False)[0] == 200
        assert request("/api/v1/status", authorized=False)[0] == 401
        assert request("/api/v1/control", {"service": "qq", "action": "start"})[0] == 202
        assert supervisor.commands.get_nowait() == ("qq", "start")
        assert request("/api/v1/control", {"service": "../../etc", "action": "start"})[0] == 400
        code, logs = request("/api/v1/logs?service=manager")
        assert code == 200 and "visible" in logs["lines"]
        assert "secret-test-value" not in str(logs) and "private-value" not in str(logs)
        assert request("/api/v1/logs?service=../../etc/passwd")[0] == 400
        # Actual children are reaped with waitpid and their exit status survives.
        process = subprocess.Popen([sys.executable, "-c", "raise SystemExit(7)"], start_new_session=True)
        supervisor.children["verify"] = {"process": process, "exit": None, "groups": {process.pid}, "started": time.monotonic()}
        deadline = time.monotonic() + 3
        while supervisor.children["verify"]["exit"] is None and time.monotonic() < deadline:
            supervisor.reap()
            time.sleep(0.01)
        assert supervisor.children["verify"]["exit"] == 7
        assert not Path(f"/proc/{process.pid}").exists(), "Child remained a zombie"
        assert request("/api/v1/status")[1]["runtimeCheck"] == "failed"
    finally:
        server.shutdown()
        server.server_close()

    # Exercise the transparent relay with binary data; HTTP and WebSockets must
    # retain their original payloads and paths, rather than using a URL prefix.
    class Echo(socketserver.BaseRequestHandler):
        def handle(self):
            while block := self.request.recv(65536):
                self.request.sendall(block)
    echo = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Echo)
    echo.daemon_threads = True
    threading.Thread(target=echo.serve_forever, daemon=True).start()
    relay = guest.tcp_relay(("127.0.0.1", 0), echo.server_address)
    threading.Thread(target=relay.serve_forever, daemon=True).start()
    try:
        payload = bytes(range(256)) * 8
        with socket.create_connection(relay.server_address, timeout=3) as client:
            client.sendall(payload)
            received = bytearray()
            while len(received) < len(payload):
                received.extend(client.recv(4096))
            assert bytes(received) == payload
    finally:
        relay.shutdown()
        relay.server_close()
        echo.shutdown()
        echo.server_close()

print("PASS: safe archives, authenticated API, secret redaction, process reaping, transparent TCP relay")
