# 鲸语 HarmonyOS

面向纯血鸿蒙手机的原生 HAP 工程。应用内使用 QEMU TCI 启动 Ubuntu ARM64，运行与鲸语 Android 1.7.0 同源的 AstrBot、SnowLuma、Linux QQ、角色素材及陪伴插件。机器人运行层在设备本机，大模型继续使用用户配置的云端 API。

这是独立的鸿蒙开发版本，版本号 0.1.0、包名 `dev.whaletalk.harmony`。最低构建目标为 HarmonyOS 5.1/API18，覆盖鸿蒙 6.0 的版本门槛；实际设备兼容、QQ 登录与消息收发、后台和锁屏仍须真机确认。

## 构建与下载

[Build HarmonyOS native phone HAP](../.github/workflows/harmony-cloud-build.yml) 在 GitHub Actions 编译，分别执行真正的 TCI 内核构建、完整 Ubuntu 镜像构建和 Linux QEMU 启动检查，再组装 HAP。Android APK 工作流与发布通道独立保留。

在该工作流成功的运行页面下载 `whaletalk-harmony-api18-arm64-unsigned-*` 产物，其中包含：

- 未签名 ARM64 HAP、SHA-256 与构建清单。
- 完整 Linux 运行镜像的来源和摘要。
- 云端 Linux QEMU 启动及服务检查结果。

HAP 没有使用 Android 的签名密钥。安装到自己的设备前，需要用 DevEco Studio／HarmonyOS 签名工具及包含目标设备的调试 Profile 签名。未签名包不能直接安装。不要将证书、Profile、账号或私钥提交至仓库。

云端 Linux QEMU 检查验证来宾系统启动、Python 原生依赖、AstrBot HTTP、QQ 登录桌面、SnowLuma HTTP、noVNC 和有序关机；它不等同于鸿蒙手机运行验证，也不使用 QQ 账号进行登录或真实消息测试。

## 本机构建

Linux x86_64 构建机需要 Node 18、JDK17、Python3.11+、GNU 工具链、Meson、e2fsprogs、qemu-utils、qemu-system-arm、zstd。以 GitHub Actions 的安装步骤为准。

```bash
bash harmony/ci/setup-harmony-sdk.sh "$PWD/.local/harmony-tools" "$PWD/.local/harmony-env.sh"
source "$PWD/.local/harmony-env.sh"
python3 harmony/ci/fetch-vm-assets.py harmony
bash harmony/ci/build-tci.sh harmony
python3 harmony/tools/check_guest_tools.py
```

从鲸语 1.7.0 Release 下载完整 ARM64 APK，`build_guest.py` 会校验固定 SHA-256，再执行：

```bash
python3 harmony/tools/build_guest.py --apk /绝对路径/WhaleTalk-Android-1.7.0-arm64-v8a.apk --output .local/harmony-guest
python3 harmony/tools/smoke_guest.py \
  --kernel harmony/entry/src/main/resources/rawfile/vm/kernel_aarch64 \
  --image .local/harmony-guest/whaletalk-ubuntu-arm64.qcow2 \
  --output .local/harmony-smoke --timeout 600
cp .local/harmony-guest/whaletalk-ubuntu-arm64.qcow2 harmony/entry/src/main/resources/rawfile/vm/rootfs_aarch64.qcow2
bash harmony/ci/assemble-unsigned.sh harmony
```

镜像和原生二进制不进入 Git。来源、上游提交、摘要与工具链版本在 `runtime-sources.lock.json` 和镜像构建回执中记录。

## 运行与数据

首次启动异步复制 Linux 镜像，后续不覆盖用户的可写系统盘。QQ 会话、角色、模型设置、插件与聊天数据保留在镜像内的 `/persistent`。卸载应用会删除这些数据；此版本尚未提供鸿蒙侧一键导出完整系统盘。

首页可启动运行环境、停止／重启机器人、打开 QQ 登录、模型设置、人设及日志。初始管理密码和 VNC 密码按设备生成，可在相应页面的“登录信息”中查看。

HAP 仅将 guest 的 8765、6185、6081、5099 转发到设备 `127.0.0.1`。管理 API 采用每设备随机令牌，令牌经应用私有共享目录传入虚机。网页不注入原生 JavaScript 桥。

2GB／4GB 是虚拟机分配量，手机系统和鲸语界面还需要额外内存。TCI 模拟对 QQ／Electron 和 Python 整套运行层开销较大。首次验证可在前台开启屏幕常亮；本版本不申请伪定位或播放音频保活，不承诺后台、锁屏或重启手机后自动运行。关闭环境会先停止服务、同步虚拟磁盘再关机；再次启动需重新打开应用。

## 来源与许可

执行核结构参考 [HiSH](https://github.com/harmoninux/HiSH)，QEMU 源码使用其 [qemu-ohos 分支](https://github.com/harmoninux/qemu)，相关提交固定在锁文件。鸿蒙界面和资源复制桥是鲸语工程代码。HiSH、QEMU、Linux、GLib、Python、QQ、SnowLuma 等各自保留适用许可，详见 [THIRD_PARTY.md](THIRD_PARTY.md) 和仓库根目录第三方说明。QQ 和 SnowLuma 专有组件从用户仓库的既有正式 APK 中提取，不伪装为开源源码。
