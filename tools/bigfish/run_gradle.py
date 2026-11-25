#!/usr/bin/env python3
"""Portable Gradle entry point; honors the current HTTP proxy and installed CA trust."""
import argparse
import os
from pathlib import Path
import subprocess
import urllib.parse

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(add_help=False)
parser.add_argument("--gradle", default=str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")))
args, tasks = parser.parse_known_args()
command = [args.gradle, "--no-daemon", "--console=plain"]
trust = Path("/etc/ssl/certs/java/cacerts")
if trust.is_file(): command.append("-Djavax.net.ssl.trustStore=" + str(trust))
proxy = urllib.parse.urlparse(os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy", ""))
if proxy.hostname:
    for scheme in ["https", "http"]:
        command += ["-D" + scheme + ".proxyHost=" + proxy.hostname, "-D" + scheme + ".proxyPort=" + str(proxy.port or 80)]
raise SystemExit(subprocess.run(command + tasks, cwd=root).returncode)
