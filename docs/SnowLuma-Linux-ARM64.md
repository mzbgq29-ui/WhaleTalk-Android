# SnowLuma 2.4.2 Linux ARM64 个人移植包

此包保留所提供 Windows 包的 JavaScript、客户端资源、协议和原声明，换用同版本已有的 Linux ARM64 Node.js 与原生模块。不是将 x64 DLL 修改文件名，也没有声称重新编译闭源原生模块。输入摘要、各文件摘要与来源记录在 `BIGFISH-PORT.json`。

Linux 包适用于 **glibc 的 ARM64 Linux**，建议 Ubuntu 24.04 ARM64。原生模块至少需要 glibc 2.34、GLIBCXX_3.4.29；不能直接在 Android 的 bionic / Termux shell 中执行。在手机上使用随 APK 内置的 Ubuntu / PRoot。交付包不包含 QEMU，不执行 Windows 或 x86 模拟。

## 独立 Linux 使用

解压到自己有写入权限的目录，运行 `bash start-linux-arm64.sh`。Node 和所需 SnowLuma 原生模块已经包含；Linux QQ 与图形桌面需要在自己的 Linux 环境中安装。使用同一 Linux 用户打开 QQ，在 `http://127.0.0.1:5099` 按原程序引导登录管理界面、接受原协议并选择 QQ 进程。若系统限制同用户进程附加，应按该 Linux 的调试权限设置处理；此包不修改系统权限。

配置和日志保存在解压目录。首次管理密码由原程序显示；自行保管。不要把包含账号配置的目录重新上传。

## 安卓应用集成

1.3 系列的主 QQ 服务改为 SnowLuma，沿用本机 6099 管理端口；OneBot 根据现有 AstrBot 配置同步到本机反向 WebSocket。默认不开放额外 HTTP / WS 监听端口。应用自己的登录页展示真实 QQ 窗口，支持放大、键盘和全屏。

应用统一启停 SnowLuma、QQ、登录桌面。QQ 使用原来的账号 HOME；SnowLuma 配置在既有备份范围内。独立的 QQ 程序目录恢复原始 `package.json`，省略 NapCat 入口，旧基础镜像与账号数据保留用于兼容和恢复。没有声称从旧镜像逐字节删除 NapCat。

## 已完成与未完成的验证

ARM64 Node 24.22.1、SnowLuma、WebSocket、FFmpeg、glibc SQLite 模块在 QEMU user 模式下加载通过。使用内置 Ubuntu 镜像的 ARM64 系统库，原始 JavaScript 成功启动 WebUI，正常密码认证、运行状态和未登录账号列表请求通过。原 JavaScript 内容摘要保持不变。

没有用户 QQ 账号，也没有连接 ARM64 真机，**未完成真实 QQ 登录、注入后的消息收发、安卓 PRoot 全流程验收**。上述启动检查不能代替这几项验收。FFmpeg 随原发行版保留；应用的语音能力保持关闭。

1.3.1 修复 PRoot 下程序副本路径，并使用安卓原生 Canvas 显示本机 QQ 画面；认证、点击、键盘、刷新和重新进入已通过安卓专项回归，具体边界见 VALIDATION.md。
