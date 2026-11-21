#!/usr/bin/env python3
"""Build a bootable Ubuntu ARM64 guest from a checksum-pinned WhaleTalk APK.

No Android binaries or package scripts are executed. All archive members are
validated before writing; Linux absolute links become equivalent relative links.
Host requirements: Python 3.11+, e2fsprogs, qemu-utils, zstd (for zstd .debs).
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import tarfile
import tempfile
import zipfile

APK_SHA256 = "681f08f50157b7a6a9a59a1bc587646f881b26f8fe14823349719a8eec80fc73"
APK_URL = "https://github.com/mzbgq29-ui/WhaleTalk-Android/releases/download/v1.7.0/WhaleTalk-Android-1.7.0-arm64-v8a.apk"


def digest(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def name_path(name):
    p = PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts or "\\" in name or "\0" in name:
        raise ValueError(f"Unsafe archive name: {name!r}")
    return Path(*p.parts)


def inside(root, name):
    target = root / name_path(name)
    # Follow existing directory links, but never follow the final file link.
    if not target.parent.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Archive parent leaves destination: {name!r}")
    return target


def remove(path):
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)


def unpack_tar(fileobj, root, mode="r:*"):
    root.mkdir(parents=True, exist_ok=True)
    pending_links = []
    count = 0
    with tarfile.open(fileobj=fileobj, mode=mode) as archive:
        for member in archive:
            if member.name.strip("./") == "":
                continue
            target = inside(root, member.name)
            if member.isdir():
                if target.is_symlink():
                    if not target.resolve().is_relative_to(root.resolve()):
                        raise ValueError("Directory link leaves extraction root")
                else:
                    target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            remove(target)
            if member.issym():
                link = member.linkname
                if "\0" in link or "\\" in link:
                    raise ValueError("Invalid symlink")
                if link.startswith("/"):
                    link = os.path.relpath(root / name_path(link.lstrip("/")), target.parent)
                if not (target.parent / link).resolve().is_relative_to(root.resolve()):
                    raise ValueError(f"Symlink leaves root: {member.name}")
                target.symlink_to(link)
            elif member.islnk():
                pending_links.append((target, member.linkname))
            elif member.isfile():
                source = archive.extractfile(member)
                with source, target.open("wb") as out:
                    shutil.copyfileobj(source, out, 1024 * 1024)
                target.chmod(member.mode & 0o777)
            elif member.ischr() or member.isblk() or member.isfifo():
                # Kernel-provided devtmpfs replaces /dev. Host never needs root.
                continue
            else:
                raise ValueError(f"Unsupported archive type: {member.name}")
            count += 1
    for target, link in pending_links:
        source = inside(root, link.lstrip("/"))
        if not source.is_file() or not source.resolve().is_relative_to(root.resolve()):
            raise ValueError(f"Missing/unsafe hard link: {link}")
        os.link(source, target)
    return count


def unpack_deb(path, root):
    with path.open("rb") as f:
        if f.read(8) != b"!<arch>\n":
            raise ValueError("Not a Debian ar archive")
        while header := f.read(60):
            if len(header) != 60 or header[58:] != b"`\n":
                raise ValueError("Invalid Debian member")
            name = header[:16].decode("ascii").strip().rstrip("/")
            size = int(header[48:58].strip())
            if name.startswith("data.tar"):
                with tempfile.TemporaryFile() as data:
                    remaining = size
                    while remaining:
                        block = f.read(min(1024 * 1024, remaining))
                        if not block:
                            raise ValueError("Truncated Debian payload")
                        data.write(block)
                        remaining -= len(block)
                    data.seek(0)
                    if name.endswith(".zst"):
                        process = subprocess.Popen(["zstd", "-q", "-d", "-c"], stdin=data, stdout=subprocess.PIPE)
                        try:
                            unpack_tar(process.stdout, root, "r|")
                        finally:
                            process.stdout.close()
                        if process.wait() != 0:
                            raise RuntimeError("zstd decompression failed")
                    else:
                        unpack_tar(data, root)
                return
            f.seek(size + size % 2, 1)
    raise ValueError("Debian package has no data archive")


def json_asset(apk, name):
    return json.loads(apk.read("assets/" + name))


def verified_asset(apk, name, expected, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    with apk.open("assets/" + name) as source, output.open("wb") as target:
        shutil.copyfileobj(source, target, 1024 * 1024)
    if digest(output) != expected:
        raise ValueError(f"Asset checksum mismatch: {name}")
    return output


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def assert_aarch64(path):
    with path.open("rb") as f:
        header = f.read(20)
    if header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", header, 18)[0] != 183:
        raise ValueError(f"Expected ELF64 little-endian AArch64: {path}")


def build(args):
    if digest(args.apk) != args.sha256.lower():
        raise ValueError("Release APK checksum mismatch")
    if args.output.exists():
        raise FileExistsError("Output exists; choose a new directory to preserve prior builds")
    args.output.mkdir(parents=True)
    root = args.output / "rootfs"
    staging = args.output / "staging"
    staging.mkdir()
    with zipfile.ZipFile(args.apk) as apk:
        base = json_asset(apk, "bootstrap/arm64-v8a/manifest.json")
        if base.get("kind") != "complete" or base.get("abi") != "arm64-v8a":
            raise ValueError("Use a complete ARM64 WhaleTalk release")
        print("Extracting pinned complete Ubuntu/Python/AstrBot runtime", flush=True)
        archive = verified_asset(apk, "bootstrap/arm64-v8a/ubuntu.tar.bin", base["sha256"], staging / "ubuntu.tar.bin")
        with archive.open("rb") as f:
            unpack_tar(f, root)
        snapshot = json_asset(apk, "desktop-snapshot/arm64-v8a/manifest.json")
        if snapshot["baseRuntimeSHA256"] != base["sha256"]:
            raise ValueError("Desktop overlay is for a different base image")
        bundle = apk.read("assets/desktop-packages/arm64-v8a/manifest.json")
        if hashlib.sha256(bundle).hexdigest() != snapshot["bundleSHA256"]:
            raise ValueError("Desktop package manifest checksum mismatch")
        for name in snapshot.get("removed", []):
            remove(inside(root, name))
        archive = verified_asset(apk, "desktop-snapshot/arm64-v8a/desktop.tar.bin", snapshot["sha256"], staging / "desktop.tar.bin")
        with archive.open("rb") as f:
            unpack_tar(f, root)

        # Preserve every WhaleTalk role/warehouse/legal/runtime asset, including
        # companion plugins, so feature migration does not discard product data.
        assets = root / "usr/share/whaletalk/assets"
        for item in apk.infolist():
            if item.is_dir() or not item.filename.startswith("assets/"):
                continue
            name = item.filename[len("assets/"):]
            if name.startswith(("bigfish/", "third-party/", "runtime/")):
                out = inside(assets, name)
                out.parent.mkdir(parents=True, exist_ok=True)
                with apk.open(item) as f, out.open("wb") as g:
                    shutil.copyfileobj(f, g, 1024 * 1024)

        desktop = root / "manager/extensions/desktop"
        shutil.copytree(assets / "runtime/desktop", desktop, dirs_exist_ok=True)
        desktop_script = desktop / "desktop.sh"
        desktop_text = desktop_script.read_text()
        if "{1..75}" not in desktop_text:
            raise ValueError("Pinned desktop startup contract changed")
        desktop_script.write_text(desktop_text.replace("{1..75}", "{1..900}", 1))
        for p in desktop.iterdir():
            if p.is_file():
                p.chmod(0o755 if p.name in ("guest-compat", "desktop.sh", "qq.sh", "lan.sh") else 0o644)
        shutil.copyfile(desktop / "libguest-compat.arm64.bin", desktop / "libguest-compat.so")
        spec = json.loads((desktop / "sources.json").read_text())["qq"]
        qq_deb = verified_asset(apk, spec["asset"], spec["sha256"], staging / "qq.deb")
        print("Extracting pinned Linux QQ (no package scripts)", flush=True)
        unpack_deb(qq_deb, desktop / "qq")
        write_json(desktop / "qq.json", {"schemaVersion": 1, "version": spec["version"], "archiveSHA256": spec["sha256"]})

        snow = json_asset(apk, "snowluma/arm64-v8a/manifest.json")
        archive = verified_asset(apk, "snowluma/arm64-v8a/runtime.tar.bin", snow["sha256"], staging / "snowluma.tar.bin")
        snow_root = root / "manager/extensions/snowluma"
        with archive.open("rb") as f:
            unpack_tar(f, snow_root / "runtime")
        for name, file_spec in snow["files"].items():
            path = inside(snow_root / "runtime", name)
            if path.stat().st_size != file_spec["bytes"] or digest(path) != file_spec["sha256"]:
                raise ValueError(f"SnowLuma file checksum mismatch: {name}")
        (snow_root / "runtime/node").chmod(0o755)
        (snow_root / "runtime/launcher.sh").chmod(0o755)
        launcher = (assets / "runtime/bigfish_snowluma.py").read_text()
        if "deadline=time.monotonic()+30" not in launcher:
            raise ValueError("Pinned QQ startup contract changed")
        launcher = launcher.replace("deadline=time.monotonic()+30", "deadline=time.monotonic()+180", 1)
        launcher = launcher.replace("ASTRBOT_COMPAT_INIT_TIMEOUT_MS='180000'", "ASTRBOT_COMPAT_INIT_TIMEOUT_MS='600000'", 1)
        (snow_root / "launch.py").write_text(launcher)
        # Keep the original tested hook/QQ launcher. The guest supervisor adds a
        # reachable noVNC relay while x11vnc itself remains on guest loopback.
        for name in ("launch_astrbot.py", "astrbot_instance.py", "log_limits.py", "managed_restart.py"):
            shutil.copyfile(assets / "runtime" / name, root / "manager" / name)
        login_page = root / "usr/share/novnc/bigfish-login.html"
        shutil.copyfile(assets / "runtime/bigfish-login.html", login_page)

        # Reproduce BundledCompanionPlugins' python/ -> site-packages mapping.
        plugin_manifest = json_asset(apk, "bigfish/bundled-plugins.json")
        plugin_zip = assets / "bigfish/bundled-plugins.zip"
        if digest(plugin_zip) != plugin_manifest["sha256"]:
            raise ValueError("Companion plugin archive checksum mismatch")
        seed = root / "usr/share/whaletalk/seed/astrbot/data"
        with zipfile.ZipFile(plugin_zip) as plugins:
            seen = set()
            for item in plugins.infolist():
                if item.is_dir():
                    continue
                file_spec = plugin_manifest["files"].get(item.filename)
                if file_spec is None or item.filename in seen:
                    raise ValueError("Unexpected or duplicate companion plugin file")
                seen.add(item.filename)
                data = plugins.read(item)
                if len(data) != file_spec["bytes"] or hashlib.sha256(data).hexdigest() != file_spec["sha256"]:
                    raise ValueError("Companion plugin file checksum mismatch")
                name = item.filename.replace("python/", "site-packages/", 1) if item.filename.startswith("python/") else item.filename
                out = inside(seed, name)
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(data)
            if seen != set(plugin_manifest["files"]):
                raise ValueError("Companion archive is incomplete")

    for name in ("proc", "sys", "dev", "run", "tmp", "dev/shm", "persistent", "hostshare", "var/log/whaletalk"):
        (root / name).mkdir(parents=True, exist_ok=True)
    (root / "tmp").chmod(0o1777)
    guest_sources = args.guest_sources or Path(__file__).resolve().parents[1] / "guest"
    shutil.copyfile(guest_sources / "init", root / "init")
    (root / "init").chmod(0o755)
    shutil.copyfile(guest_sources / "supervisor.py", root / "manager/whaletalk_supervisor.py")
    for path in (root / "usr/bin/python3", root / "opt/astrbot-venv/bin/python", desktop / "qq/opt/QQ/qq", snow_root / "runtime/node"):
        assert_aarch64(path)
    required = ("usr/bin/Xvfb", "usr/bin/xauth", "usr/bin/x11vnc", "usr/bin/websockify", "usr/bin/fluxbox", "opt/astrbot/main.py", "opt/astrbot/runtime_bootstrap.py", "usr/share/novnc/vnc.html")
    for name in required:
        if not (root / name).is_file():
            raise ValueError(f"Required runtime component absent: {name}")
    receipt = {"schema": 1, "sourceApkSHA256": args.sha256, "sourceUrl": APK_URL, "base": base, "desktop": snapshot,
               "snowlumaSHA256": snow["sha256"], "plugins": plugin_manifest["plugins"], "architecture": "aarch64",
               "entrypoint": "/init", "rootDevice": "/dev/sda", "vmAdaptations": ["desktop startup grace 180s", "QQ desktop grace 180s", "hook startup grace 600s"],
               "ports": {"manager": 8765, "astrbot": 6185, "qqWeb": 6081, "snowluma": 6099, "snowWeb": 5099, "rfb": 5920}}
    write_json(root / "usr/share/whaletalk/build-receipt.json", receipt)
    write_json(args.output / "build-receipt.json", receipt)
    if args.rootfs_only:
        print("Root filesystem assembled:", root, flush=True)
        return
    raw = args.output / "whaletalk-ubuntu-arm64.ext4"
    with raw.open("wb") as f:
        f.truncate(args.disk_gib * 1024 ** 3)
    subprocess.run(["mke2fs", "-q", "-F", "-t", "ext4", "-b", "4096", "-L", "WHALETALK", "-E", "root_owner=0:0", "-d", str(root), str(raw)], check=True)
    with tempfile.NamedTemporaryFile("w", suffix=".debugfs", delete=False, dir=args.output) as f:
        # debugfs mknod accepts paths only through cwd, hence the explicit cd.
        f.write("cd /dev\nmknod console c 5 1\nmknod null c 1 3\nmknod zero c 1 5\nmknod random c 1 8\nmknod urandom c 1 9\n")
        command_file = Path(f.name)
    subprocess.run(["debugfs", "-w", "-f", str(command_file), str(raw)], check=True)
    command_file.unlink()
    image = args.output / "whaletalk-ubuntu-arm64.qcow2"
    subprocess.run(["qemu-img", "convert", "-f", "raw", "-O", "qcow2", "-c", str(raw), str(image)], check=True)
    subprocess.run(["qemu-img", "check", str(image)], check=True)
    receipt["image"] = {"file": image.name, "bytes": image.stat().st_size, "sha256": digest(image), "virtualBytes": args.disk_gib * 1024 ** 3}
    write_json(args.output / "build-receipt.json", receipt)
    print("Bootable guest image:", image, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--sha256", default=APK_SHA256)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--guest-sources", type=Path)
    parser.add_argument("--disk-gib", type=int, default=12, choices=range(8, 65))
    parser.add_argument("--rootfs-only", action="store_true")
    build(parser.parse_args())
