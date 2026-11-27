<div align="center">

<img src="app/src/main/res/drawable-nodpi/whale_girl.png" alt="鲸语 WhaleTalk" width="120" style="border-radius: 28px;" />

# 鲸语 · WhaleTalk

**无需 Root，在 Android 手机上直接运行的 QQ 机器人与角色陪伴应用**

[![Release](https://img.shields.io/github/v/release/mzbgq29-ui/WhaleTalk-Android?label=Release&color=0066D6&style=flat-square)](https://github.com/mzbgq29-ui/WhaleTalk-Android/releases/latest)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20ARM64-1D8A6E?style=flat-square)](https://github.com/mzbgq29-ui/WhaleTalk-Android/releases/latest)
[![CI](https://img.shields.io/github/actions/workflow/status/mzbgq29-ui/WhaleTalk-Android/ci.yml?branch=main&label=Source%20Checks&style=flat-square)](https://github.com/mzbgq29-ui/WhaleTalk-Android/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/mzbgq29-ui/WhaleTalk-Android?color=4E5D73&style=flat-square)](LICENSE)
[![Stars](https://img.shields.io/github/stars/mzbgq29-ui/WhaleTalk-Android?style=flat-square)](https://github.com/mzbgq29-ui/WhaleTalk-Android/stargazers)

[下载安装包](https://github.com/mzbgq29-ui/WhaleTalk-Android/releases/latest) · [上手教程](docs/INSTALL.md) · [更新日志](docs/CHANGELOG.md) · [排障指南](docs/TROUBLESHOOTING.md) · [源码构建](docs/BUILD.md)

</div>

---

> [!TIP]
> **鲸语 `v1.8.4`** 新增 PRoot 子进程自动探测与按内核持久化 `PROOT_NO_SECCOMP=1` 兼容回退，减少桌面安装阶段 PRoot 子进程派生，并在诊断信息中补充内核版本与 seccomp 状态。

## 核心特性

- **原生物理质感交互**：基于 Jetpack Compose 与 Material 3 深度定制，提供运行首页、本机对话、工具中心、人设仓库与快捷排障入口；配置改动支持立即热应用或留到下次启动时生效。
- **开箱即用，免 Root 本机运行**：完整安装包内置 PRoot、Ubuntu 运行层、AstrBot 与 SnowLuma（Linux QQ）；文字模型直连你配置的云端 Chat Completions API，默认无需额外向量/嵌入或语音服务费用。
- **酒馆角色卡与陪伴增强**：内置角色仓库与离线酒馆卡导入（支持 V1 / V2 / V3 PNG 与 JSON，静态适配开场与世界书，不执行外部脚本）；随包提供长期记忆与拟人分段回复增强。
- **精细控制与规则试算**：可配置主人 QQ、好友与群允许名单、唤醒词、@ 回复及引用样式，并支持在页面内直接试算消息是否会被回复。
- **掉线守护与完整备份**：内置退避自动重连（5s / 15s / 60s）、运行状态监控、实时脱敏日志检索、诊断包导出以及加密分享备份导入/导出。

## 快速上手

1. **下载安装包**：前往 [GitHub Releases 最新版](https://github.com/mzbgq29-ui/WhaleTalk-Android/releases/latest) 下载 ARM64 APK（或解压 `.zip` 后安装其中的 APK）。
2. **准备运行环境**：打开应用进入「开始设置」，首次解压约 143 MB SnowLuma 运行层与内置组件时请保持应用在前台。
3. **配置模型与主人 QQ**：在「云端模型」页填写你的主人 QQ 号、API 地址、模型名称与 API Key。
4. **登录机器人小号**：在「QQ 登录」页打开内置登录画面扫码或验证登录，回到首页启动服务即可开始对话。

## 系统要求

> [!NOTE]
> - **设备要求**：Android 8.0 及以上、ARM64 (`arm64-v8a`) 手机，**无需 Root**。
> - **存储空间**：完整 APK 约 1 GB，解压并运行环境建议预留 **至少 6 GB** 可用空间。
> - **包名与升级**：鲸语使用独立包名 `dev.whaletalk.android`，1.4.0～1.8.3 同签名版本可直接覆盖升级至 1.8.4。若从 1.3.9 或更早的版本迁移，请先在旧版导出备份，再导入新版。

## 运行组件

| 组件 | 作用说明 | 版本 / 备注 |
| :--- | :--- | :--- |
| **Android 壳体** | 原生 Compose 操作台、服务守护、备份与诊断 | WhaleTalk 1.8.4（MIT） |
| **思考引擎** | 对话管理、人设、知识库、插件与模型调度 | AstrBot（本机运行） |
| **QQ 连接层** | 协议接入、OneBot 消息通道与版本锁定守护 | SnowLuma 2.4.2 · Linux QQ 3.2.35-54395 |
| **容器运行层** | 免 Root 本机隔离用户态运行环境 | PRoot + Ubuntu ARM64 |
| **内置增强** | 长期记忆、拟人分段、群聊空气读取（按需开启） | Memory Companion / Chat Plus |

## 文档导航

| 文档 | 内容说明 |
| :--- | :--- |
| [安装与上手教程](docs/INSTALL.md) | 安装、环境准备、QQ 登录、模型配置、人设与后台保活设置 |
| [常见问题与排障](docs/TROUBLESHOOTING.md) | QQ 在线但不回复、模型连接异常、环境准备失败排查 |
| [更新日志](docs/CHANGELOG.md) | 各版本功能迭代与变更记录 |
| [源码构建与签名](docs/BUILD.md) | 开发环境配置、内置运行时打包、构建命令与签名验证 |
| [消息通道说明](docs/消息通道.md) | 本机 OneBot IPC 通道发现与重连机制说明 |
| [历史验证范围](VALIDATION.md) | 测试覆盖范围与真机/模拟器验证记录 |
| [第三方依赖与许可](THIRD_PARTY.md) | 随包分发组件的开源许可与完整声明 |

## 参与与反馈

欢迎在阅读 [贡献指南](.github/CONTRIBUTING.md) 后提交 Issue 或 Pull Request。

- 提交反馈前建议先查看 [排障指南](docs/TROUBLESHOOTING.md)，或使用应用内「导出诊断包」（已内置脱敏），切勿直接贴出真实账号、聊天记录、API Key 或密码。
- 如发现安全漏洞，请遵循 [SECURITY.md](SECURITY.md) 指引私密报告。

## 开源许可

本仓库自研 Android 应用源码采用 [MIT 许可](LICENSE) 开源。第三方开源组件、字体、素材及角色卡各自的来源与许可协议见 [THIRD_PARTY.md](THIRD_PARTY.md) 与 [`docs/legal/`](docs/legal/) 目录；QQ 及 SnowLuma 等专有二进制依赖归各自版权方所有，请遵守其对应使用条款。
