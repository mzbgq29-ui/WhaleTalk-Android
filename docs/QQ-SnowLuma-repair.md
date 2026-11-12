# QQ / SnowLuma 运行层修复（2026-11-12）

本文记录最初的源码修补阶段。后续 1.3.7-qqfix 并存 APK 及便捷功能的说明见 [QQFix-1.3.7.md](QQFix-1.3.7.md)；其最终构建状态以同批交付的验证记录为准。

此交付为基于用户提供的 BigFish Android 1.3.6 的源码修补包，包含重编译的 ARM64 兼容库。未生成新的签名 APK，不能把这个 ZIP 当成安卓安装包。

## 核对版本

- 运行目标是 Ubuntu / PRoot 内的 **Linux ARM64 QQ**，不是 Android QQ APK。
- 原包已内置 QQ **3.2.35-54395**。本次从锁定的腾讯官方地址重新下载，大小 208717176 字节，SHA-256 为 `5f93ea62347dd772c7be4115413c95d2a60d189f3dd81bc194834bd4d7343cf2`，与原包一致。
- 本次腾讯公开 Linux 下载配置返回 3.2.32，比内置版本旧；未找到可核验的更高 ARM64 版本。因此 QQ 版本与下载锁保持原值，没有降级或虚构升级。
- SnowLuma 为 **2.4.2**。原包 ARM64 `.node` / `.so` 与可取得的上游 2.4.2 发行组件逐字节相同。原 JavaScript 与原生协议组件均未改写。
- 核查入口：[腾讯 Linux 下载配置](https://cdn-go.cn/qq-web/im.qq.com_new/latest/rainbow/linuxConfig.js)、[已核验 QQ 包](https://qqdl.gtimg.cn/qqfile/QQNT/9.9.37/beta/816b3c79/linuxqq_3.2.35-54395_arm64.deb)、[SnowLuma 2.4.2 镜像发行记录](https://github.com/TomyJan/SnowLuma/releases/tag/v2.4.2)。这是本次可核验的版本范围，不保证腾讯其他渠道不存在更新。

## 修复内容

1. `guest_compat.c` 原来只在调用 `epoll_wait` / `epoll_pwait` 前检查延迟初始化。如果 QQ 在 5 秒延迟到期前进入长时间事件等待，等待期间不会再启动 SnowLuma 钩子。现在在主线程内部按最长 100 ms 检查初始化，完成后恢复正常等待；保留真实事件、有限超时、信号屏蔽和 EINTR，不向 QQ 返回人为制造的超时。
2. `bigfish_snowluma.py` 在启动任何子进程前建立权限为 0700 的共享通信目录，为 QQ 和 SnowLuma 同时设置 `SNOWLUMA_HOOK_RUNTIME_DIR` 与 `XDG_RUNTIME_DIR`，避免初始发现时使用不同默认目录。QQ 仍使用原有 `/persistent/home`。
3. 重编译 `libguest-compat.arm64.bin` 并更新 `compat-binary.json` 的二进制摘要和源码绑定摘要，使离线安装器接受本次修复库。目标 ABI 为 aarch64 / glibc 2.34，适用于随包 Ubuntu 24.04。

## 验证

以下命令可在普通 Linux 开发机运行：

```sh
python3 tools/bigfish/tests/check_hook_start.py
python3 tools/bigfish/tests/check_snowluma_launch.py
python3 tools/bigfish/check_self_memory.py
python3 tools/bigfish/check_qq_preparation.py
python3 tools/bigfish/tests/check_desktop_preparation.py
```

原始 `guest_compat.c` 在 `idle` 回归用例中 8 秒超时；修复版在 x86_64 与 ARM64 / QEMU 下均通过 6 种场景：无限等待、pwait、有限超时、工作线程排除、EINTR、临时信号屏蔽。最终随源码交付的预编译 ARM64 库也通过了这 6 种测试。

在 ARM64 glibc Linux 上，还可直接检查原 SnowLuma 的真实 IPC 握手：

```sh
python3 tools/bigfish/tests/check_snowluma_ipc.py --hook /absolute/path/to/native/snowluma-linux-arm64.so
```

本次在 ARM64 / QEMU 下实际加载原 `.so`，验证 control / recv 两个 Unix socket 均使用指定目录，且返回 SnowLuma QHP1 / v1 的 HELLO。没有 QQ 会话对象，这只证明本机通信层能够握手。完整检查结果见 `validation/qq-snowluma-repair-2026-11-12.json`。

**未验证：ARM64 手机上的完整 QQ 启动、真实账号扫码登录、好友 / 群消息收发、完整 Android APK 构建。不能据此声称新版 QQ 的协议收发已经全面适配。**

## 用原完整 APK 准备构建输入

源码不包含 1 GB 的运行二进制。可使用用户链接中的原完整 APK 恢复匹配输入，不需要下载旧上游镜像：

```sh
python3 tools/bigfish/prepare_release_inputs.py \
  --apk /absolute/path/to/BigFish-Android-1.3.6-arm64-v8a.apk \
  --sha256 eb1e2fa6a6415793f655c7afc0525156b16d7cf9d5906ca1701c43f9fa044cdd
```

脚本会略过当前源码已有的资产，因此本次修复库和启动器不会被旧 APK 覆盖。再按 `FORK.md` 配置 JDK 21、Android SDK 35 与 `local.properties`。正式覆盖安装需要原应用的签名私钥；源码及原 APK 中都没有该私钥。自行签名的 APK 无法直接覆盖不同签名的旧安装。

ARM64 兼容库的构建命令和准确摘要已记录于 `app/src/main/assets/runtime/desktop/compat-binary.json`。不要单独修改 C 文件而沿用旧摘要或旧二进制。
