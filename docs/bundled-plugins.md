# 内置记忆与拟人增强

截至 2026-11-11，从 GitHub 核对并固定以下源版本。星数仅是检索当时的快照，不把本组合宣称为所有类别的热度第一名。

| 功能 | 原项目 | 固定版本 / 提交 | 星数快照 | 许可 |
| --- | --- | --- | --- | --- |
| 长期记忆、本地检索、人物与情绪线索 | [Memory Companion · menglimi](https://github.com/menglimi/astrbot_plugin_memory_companion) | 2.6.6 / `0e22a01e680d5a43b2ca98dea8a550a16c7611fa` | 113 | MIT |
| 自然群聊、读空气与打字节奏 | [Chat Plus · Him666233](https://github.com/Him666233/astrbot_plugin_group_chat_plus) | V1.2.3.hotfix.2 / `aa97e0e35a18733e73db753238dd2a3021446e42` | 206 | AGPL-3.0 |

评估过 LivingMemory 2.10.0（当时 440 星），但其语义召回依赖 Embedding Provider，不符合本次“不增加其他 API”的要求，因此没有内置它。也没有打包需要语音、天气、摄像头或额外服务账号的整合插件。

## 手机使用

「工具 → 陪伴增强」提供原生设置。插件源码、原许可、移动版差异以及 ARM64 Argon2 依赖已经包含于 APK，思考引擎首次启动时离线部署。已存在的同名插件和用户修改过的文件不会被覆盖，加载状态以引擎插件列表和日志为准。

- 默认启用本地记忆采集/召回与较短回复的自然分段。私聊、群聊、人物与人格的可见性继续由原插件控制，跨平台身份自动合并关闭。数据在 AstrBot 的 `data/plugin_data` 内，进入既有快照和便携备份。
- Embedding、Rerank 和来源语义索引关闭，不需要额外 API Key。
- 自动总结默认关闭。开启后只复用应用已有的 `bigfish-chat` 模型；按消息数量触发、后台积压批处理关闭、每会话每小时最多 4 次自动总结调用。用户消息和机器人回复分别计数。它会增加已有模型的用量。
- 群聊增强默认关闭。启用前需要在回应方式添加群号并关闭“群聊仅 @ 回应”。插件只在这些群内启用；空名单不会变成“所有群”。读空气判断复用主聊天模型，会增加调用量。
- 不启用语音、独立网页面板、额外图片接口或空闲主动发消息。普通私聊仍走 AstrBot 主链；没有打开上游仍在开发中的 Chat Plus 私聊模块。

QQ 内可以发送 `/mcomp status` 查看记忆状态、`/mcomp search 关键词` 搜索、`/mcomp help` 看原插件命令。这些是用户自行操作的功能，构建和验证过程中没有登录 QQ 或向实际会话发消息。

## 离线配置与源码差异

源码完整放在 `app/src/main/assets/bigfish/bundled-plugins.zip`，包含原 README、许可、源码和测试；这份 ZIP 也在交付源码包中。逐文件校验、依赖哈希和来源见同目录的 `bundled-plugins.json` 与 `docs/legal/bundled-plugins/provenance.json`。原插件的许可保持独立，不能因为 Android 外壳是 MIT 而改掉它们的许可。

移动配置只做两类修改：

1. 保留原 `requirements.txt` 为 `requirements.upstream.txt`。Memory Companion 的宿主 AstrBot SDK 已由固定的源码运行环境提供，不再要求 pip 重装同名 SDK 包；Chat Plus 不安装仅供开发使用的 pytest、hypothesis，保留实际运行依赖。Argon2 25.1.0 的原 wheel 文件内容、版权声明和 Linux ARM64 原生绑定随包离线提供，使用镜像现有的 cffi 与 pycparser，不替换核心依赖。
2. 修复 Memory Companion 小预算下单条有效记忆被整个丢弃：保留原有范围、表达、脱敏和分组约束，在冗长表示放不下时尝试原插件已有的紧凑表示。原文件为 `core/injection.upstream.py`，补丁为 `bigfish-patches/memory-budget.patch`；修改署名 mzbgq。原测试中的单条预算、相关度排序和拟人化约束用于回归，没有把失败断言删除。

APK 安装器逐文件核对 SHA-256，在私有暂存目录验证后整目录移入目标位置；启动前再按原 schema 合并手机设置。保存设置只写应用自己的配置文件，实际插件配置在引擎停止后、下次启动前应用。已有聊天记录和插件数据库不在这些写入目标中。

## 检查边界

当前结果记录在 `validation/bundled-plugins-1.3.2.json`。真实 QQ 账号、SnowLuma 实机登录和真实群聊行为仍需要用户的 ARM64 手机确认；主机测试和模拟器中的协议测试不能代替这些步骤。

## 重新准备插件资源

源码已随资源 ZIP 提供；也可运行 `python tools/bigfish/fetch_companion_sources.py --output .local/companion-upstream` 获取相同提交并校验原归档哈希。下载依赖用：

```sh
python -m pip download --only-binary=:all: --platform manylinux_2_28_aarch64 --platform manylinux_2_17_aarch64 --python-version 312 --implementation cp --abi cp312 --abi abi3 --no-deps --dest .local/companion-wheels argon2-cffi==25.1.0 argon2-cffi-bindings==25.1.0
python tools/bigfish/package_companion_plugins.py --research .local/companion-upstream --wheels .local/companion-wheels
```

这些命令只用于构建主机，手机安装 APK 后无需执行。
