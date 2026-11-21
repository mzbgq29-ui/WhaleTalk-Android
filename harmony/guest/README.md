# WhaleTalk local Linux guest

This guest is the real application runtime for the HarmonyOS HAP. It retains the
existing pinned Ubuntu ARM64, Python/AstrBot, Linux QQ and SnowLuma distributions,
the bundled memory/group plugins, and the WhaleTalk persona/tavern libraries.
There is no Android compatibility container, PRoot or external execution server.
The text model still uses the user's cloud API, as in the Android edition.

## Build from the existing complete release

Host packages: Python 3.11+, `e2fsprogs`, `qemu-utils`, `zstd`. The Linux smoke
check additionally requires `qemu-system-arm`.

```sh
curl --fail --location --retry 3 \
  --output /tmp/WhaleTalk-Android-1.7.0-arm64-v8a.apk \
  https://github.com/mzbgq29-ui/WhaleTalk-Android/releases/download/v1.7.0/WhaleTalk-Android-1.7.0-arm64-v8a.apk
python3 harmony/tools/check_guest_tools.py
python3 harmony/tools/build_guest.py \
  --apk /tmp/WhaleTalk-Android-1.7.0-arm64-v8a.apk \
  --output work/guest-image
```

The builder requires the published APK SHA-256
`681f08f50157b7a6a9a59a1bc587646f881b26f8fe14823349719a8eec80fc73`, then checks
the base image, desktop overlay, QQ package, SnowLuma archive and every bundled
plugin/SnowLuma file against their respective manifests. It executes no APK,
Android native library, Debian maintainer script or downloaded deployment script.

The resulting `work/guest-image/whaletalk-ubuntu-arm64.qcow2` has a 12 GiB virtual
ext4 disk. Only allocated data is stored. It is suitable for the HiSH-derived
QEMU SCSI disk configuration, with `root=/dev/sda rw rootwait rootfstype=ext4
console=ttyAMA0 init=/init`. Copy it into the HAP rawfile location
`harmony/entry/src/main/resources/rawfile/vm/rootfs_aarch64.qcow2` before assembly.
Build receipts record the exact inputs and image checksum; third-party binaries
remain release/build inputs, outside source Git.

## First boot and persistence

The packaged qcow2 is an immutable seed. The HAP must copy it into a temporary
private file, flush it, and rename it to commit the first writable virtual disk.
Later launches and app upgrades reuse that private disk. A partial copy is never
booted. Replacing a populated disk requires an explicit data-migration workflow.

Inside the VM, `/opt/astrbot` and `/opt/astrbot-venv` retain pinned code and Python
dependencies. `/manager` replaces Android's bind-mounted manager directory;
`/persistent` replaces its persistent bind mount without changing guest paths.
QQ profile/login data, AstrBot databases/configuration/plugins, and SnowLuma
configuration stay under `/persistent`. The first boot only installs missing
plugin components from `/usr/share/whaletalk/seed`. It preserves subsequent user
changes. The original role/warehouse JSON files and licence materials are under
`/usr/share/whaletalk/assets` and available through the authenticated library API.

`/init` is a Python Linux PID 1. It mounts proc/sysfs/devtmpfs/tmpfs/devpts and the
private `hostshare` 9p directory, configures the QEMU user network at
`10.0.2.15/24` with gateway `10.0.2.2` and DNS `10.0.2.3`, and starts the supervisor.
No systemd package or DHCP executable is needed. The supervisor calls `waitpid`
for tracked children and orphaned descendants, records descendant process groups,
uses bounded restart backoff, and closes owned process groups during shutdown.

## HAP boundary

Before starting QEMU, the HAP creates or reuses a per-install random token in
private preferences and writes `whaletalk-bootstrap.json` to its **private** 9p
share, containing `{ "apiToken": "at-least-32-random-characters" }`. This share
must not be the user's public Documents folder. The guest persists that token and
writes `whaletalk-connection.json` for development tooling. Neither file belongs
in source Git or build logs.

Forward only host loopback ports:

| Host and guest port | Purpose |
|---|---|
| 8765 | Management API, bound to guest Ethernet |
| 6185 | AstrBot dashboard, bound to guest Ethernet |
| 6081 | noVNC relay to guest-loopback RFB 5920 |
| 5099 | Transparent TCP relay to guest-loopback SnowLuma 6099 |

The internal OneBot reverse WebSocket uses guest loopback 6199 and is never
forwarded. A direct QEMU host-forward to a guest service bound only to
`127.0.0.1` does not work; this is why noVNC and the SnowLuma TCP relay bind the
guest Ethernet address. The SnowLuma relay preserves absolute HTTP asset and
WebSocket URLs. It is not an HTTP prefix proxy.

`GET /health` is unauthenticated and returns only the app/schema health marker.
Every `/api/v1/*` endpoint requires `Authorization: Bearer <apiToken>`:

| Request | Result |
|---|---|
| `GET /api/v1/status` | Service process/readiness states, versions, ports, uptime, Python runtime import check |
| `POST /api/v1/control` with `{service,action}` | Queues start/stop/restart for `all`, `astrbot` or `qq` |
| `GET /api/v1/logs?service=manager&lines=100` | Bounded, redacted log lines for manager/astrbot/qq |
| `GET /api/v1/credentials` | Initial dashboard/SnowLuma credentials and VNC password |
| `GET /api/v1/library?kind=personas` | Original WhaleTalk library; kinds: personas/tavern/labels |
| `POST /api/v1/shutdown` | Stops services, reaps, syncs the disk, and powers off the guest |

`qq.ready` confirms the local login desktop and SnowLuma listener are available.
It does **not** claim that the QQ account is logged in or that OneBot is connected;
those fields remain `unknown` until separately observed. Service actions never
accept arbitrary shell commands or filesystem paths. API tokens, passwords,
authorization values and API keys are redacted from exported logs.

## Validation

```sh
python3 harmony/tools/smoke_guest.py \
  --kernel path/to/HiSH/vmlinuz \
  --image work/guest-image/whaletalk-ubuntu-arm64.qcow2 \
  --output work/guest-smoke --timeout 600
```

This creates a disposable writable overlay and boots the actual ARM64 guest.
It checks guest management/authentication, imports including FAISS, AstrBot HTTP,
QQ login desktop, noVNC/SnowLuma HTTP and orderly shutdown. It writes a JSON report
and serial/application logs. It cannot verify HarmonyOS 6.0 app policy, phone
performance/background survival, QQ account login, or a real QQ-to-model reply.
Those require a signed HAP, target phone and test account.
