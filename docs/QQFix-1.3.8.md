# 1.3.8-qqfix：扫码后消息通道无法连接

本版修复 1.3.7-qqfix 并存包的本机通道目录问题。应用 ID 与上一修复版相同，使用同一签名，版本号增加到 100308；可直接覆盖 1.3.7-qqfix，保留设置与 QQ 账号目录。仍与原始 `dev.bigfish.nativebot` 应用分别安装。

## 问题与修复

原目录 `/manager/extensions/snowluma/qq-runtime` 在 PRoot 内看起来不长，但转换到 Android 的实际应用私有目录后，代表性路径可达到 109–112 字节。Unix socket 地址空间有限，PRoot 会把过长的绑定改到临时文件，并建立虚拟路径映射。

这不一定报错：直接连接已知路径可能成功，但是目录枚举中没有对应的 `mojo.<pid>.control.sock` 文件。SnowLuma 的管道观察器使用目录枚举发现控制 socket，因此会一直等待通道，而 QQ 的图形窗口仍可以正常打开。这解释了“扫码后有 QQ 画面，但消息通道没有连接”的一条可复现故障路径；实际手机上的最终连接仍需验证。

本版改用 `/manager/sl-ipc`，代表性完整路径缩短到约 85–90 字节。QQ 与 SnowLuma 使用同一目录。启动前会创建临时探测 socket，确认它既可绑定又可被目录枚举发现，检测后立即清理；失败会明确结束启动并记录原因，避免无提示地保持等待。

不修改账号 HOME、登录数据库、模型配置或第三方协议组件，也不把“窗口打开”当成消息通道在线。

## 安装

1. 先在现有修复版中停止运行服务。
2. 下载本版 ZIP，解压并直接安装其中 APK，覆盖 1.3.7-qqfix。**不要卸载、清除数据或重新准备整套环境。**
3. 重新打开修复版并启动服务，等待 QQ 与消息通道状态更新。现有登录数据保留；QQ 自身要求重新验证时，按原客户端提示操作。

## 回归验证

- `python3 tools/bigfish/tests/check_ipc_path_length.py`：按 `/data/user/0`、`/data/user/999`、`/data/data` 的路径长度构造目录，覆盖实际 socket 绑定与收发、5/6/10 位 PID 名称、权限与临时文件清理。
- `python3 tools/bigfish/tests/check_snowluma_launch.py`：确认 QQ 与 SnowLuma 共用短目录，原账号 HOME 不变。
- `python3 tools/bigfish/tests/check_proot_ipc_path.py --proot /absolute/path/to/proot`：在实际 Linux PRoot 中，先启动读取端再启动绑定端，复现旧路径“可直接连接但目录不可枚举”，验证新路径可被枚举和收发，并验证预检拒绝不可枚举的探测 socket。本次测试主机需要 `PROOT_NO_SECCOMP=1` 关闭 PRoot 的 syscall 加速；这只是测试进程选项，没有修改 APK 的 PRoot 配置。

同时检查原 SnowLuma ARM64 库在代表性长 / 短宿主路径下的启动与双通道 HELLO。完整 Android 构建、单元测试、静态检查和签名结果见同批 `validation/release-1.3.8-qqfix.json`。

未连接用户手机，不把这些检查记为真实 QQ 账号登录、好友或群消息收发通过。
