# 鲸语 WhaleTalk · mzbgq

基于大肥鱼 1.3.8-qqfix 的源码现更名为「鲸语 WhaleTalk」。本次名称变更尚未生成新的 APK。1.3.8-qqfix 修复扫码后消息通道发现失败：缩短 PRoot 内的 IPC 目录，并在启动时检查 socket 能否被目录枚举发现。可覆盖上一修复版，保留账号与设置；安装见 [本版说明](docs/QQFix-1.3.8.md)。保留回应预设、QQ 指令复制、日志筛选和本机连接自检，GitHub 参考见 [1.3.7 说明](docs/QQFix-1.3.7.md)。真实手机账号收发尚未验证。

无 root 手机上的普通 QQ 小号机器人。**应用界面作者：mzbgq。**

1.3 使用独立设计的原生 Studio 界面：鲸鱼、聊天、工具、我的。可见产品页面、导航与本机 API 交互由 Studio 系列实现；不再把原版安卓版管理页或 AstrBot 网页前端作为日常操作界面。历史 Activity 名只保留为兼容通知与快捷方式的别名，均指向新宿主。

完整 ARM64 APK 内置 PRoot、Ubuntu、AstrBot、SnowLuma 2.4.2、Linux ARM64 QQ 3.2.35-54395。运行层复用既有实现，保留相应原作者和许可；模型推理使用用户配置的云端 API，语音保持关闭。

- [安装与使用](BIGFISH.md)
- [SnowLuma Linux ARM64 个人移植与验证范围](docs/SnowLuma-Linux-ARM64.md)
- [独立界面与玻璃修复](docs/独立界面.md)
- [运行层来源、GitHub 参考与构建](FORK.md)
- [当前交付与检查范围](VALIDATION.md)
- [保留的上游项目说明](docs/upstream/ASTRBOT_ANDROID_README.md)

入口：`app/src/main/java/io/astrbot/android/BigFishStudioActivity.kt`。界面状态、API、工具与维护分别位于 `StudioModel.kt`、`StudioApi.kt`、`StudioCollections.kt`、`StudioProviders.kt`、`StudioPluginMarket.kt`、`StudioMaintenance.kt`。玻璃与主题位于 `BigFishGlass.kt`、`BigFishTheme.kt`。

本次 Android 13 玻璃前景专项回归通过 3 项。没有把它写成 QQ 实号、云端服务商或完整真机矩阵的全面验收。
