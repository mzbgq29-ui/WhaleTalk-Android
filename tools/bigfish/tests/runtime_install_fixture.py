#!/usr/bin/env python3
"""Local, read-only stream of the pinned full runtime for Android extractor regression."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[3]
assets=root/'.local/inputs/assets'
base=assets/'bootstrap/arm64-v8a';snapshot=assets/'desktop-snapshot/arm64-v8a'
for path,manifest in [(base/'ubuntu.tar.bin',base/'manifest.json'),(snapshot/'desktop.tar.bin',snapshot/'manifest.json')]:
    with path.open('rb') as f: actual=hashlib.file_digest(f,'sha256').hexdigest()
    assert actual==json.loads(manifest.read_text())['sha256']
files={'/bootstrap':base/'ubuntu.tar.bin','/snapshot':snapshot/'desktop.tar.bin','/base.json':base/'manifest.json','/snapshot.json':snapshot/'manifest.json'}
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        file=files.get(self.path)
        if file is None:self.send_error(404);return
        self.send_response(200);self.send_header('Content-Length',str(file.stat().st_size));self.end_headers()
        with file.open('rb') as f:
            while data:=f.read(1024*1024):self.wfile.write(data)
    def log_message(self,fmt,*args):print(fmt%args,flush=True)
print('Validated full runtime fixture on localhost:18936',flush=True)
ThreadingHTTPServer(('127.0.0.1',18936),Handler).serve_forever()
