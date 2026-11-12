# GitHub 参考与复用范围

当前 1.3.7-qqfix 使用独立应用 ID 和独立签名；新增便捷功能、参考资料与安装方式见 [本版说明](docs/QQFix-1.3.7.md)。下文保留原 1.3 系列的来源和历史构建记录，其中“同签名覆盖升级”的说明不适用于本次独立签名安装包。

| 项目 | 查阅与选择 |
| --- | --- |
| [JunWan666/astrbot-for-android](https://github.com/JunWan666/astrbot-for-android) | 运行层复用：PRoot、Ubuntu 镜像、进程管理、QQ 组件、备份、配置事务、日志和兼容处理；保留 MIT 版权。1.2 的可见界面与导航由 mzbgq 重新设计，不启动旧版管理页面 |
| [Rarlya1/AstrBot-Android-App](https://github.com/Rarlya1/AstrBot-Android-App) | 对照一体化部署、首次安装与 NapCat 登录入口；没有另复制一套终端实现 |
| [linger-su/astrbot-termux](https://github.com/linger-su/astrbot-termux) | 对照原生 AstrBot + PRoot NapCat 混合部署、网络初始化与诊断。当前采用已打包镜像，降低手机首次 pip / apt 安装的不确定性 |
| [NapNeko/NapCat-Installer](https://github.com/NapNeko/NapCat-Installer) | 核对官方 Android / Termux / PRoot 路线和 Linux QQ 依赖；首次启动不执行浮动版本的远程安装脚本 |
| [whyever77/dafeiyu-astrbot](https://github.com/whyever77/dafeiyu-astrbot) | 核对“大肥鱼”功能边界。它的 Windows、外部 API 和平台插件不等于装入安卓运行器就自动可用；未把这些功能虚称为已经全部移植 |
| [android/compose-samples](https://github.com/android/compose-samples/tree/main/Reply) | 参考原生 Compose 的状态驱动界面、主操作层级和 Material 组件使用 |
| [android/nowinandroid](https://github.com/android/nowinandroid) | 参考设计系统、浅色 / 深色主题和可访问性；未复制其业务代码或素材 |

## 固定版本

- 管理器底座源码：`3ea3dc662ab9274e19440a41efc385444f64edab`，上游 `v1.4.1`。
- 上游完整 APK SHA-256：`cacdc3332c84df32a9958cdb16a3167c0a90d8f9c27815cde63df9e93ad658a1`。
- 运行镜像：Ubuntu 24.04.3、AstrBot 4.29.3、NapCat 4.18.41、Linux QQ 3.2.34-53644。
- 运行镜像 SHA-256：`9990abeb9febcefe4e68d75c13efa00ab764caea9e3a96d29a35eb42c62588b7`。
- Android 构建沿用上游 AGP 8.9.1、Kotlin 2.2.21、Gradle 8.11.1。当前构建机使用 JDK 21，输出 JVM 17 字节码。

完整运行镜像与 PRoot 程序直接取自已核对摘要的上游 APK，没有从登录过账号的手机导出环境。运行镜像未修改；新增功能位于 Android 向导、配置生成、模型验证和配置恢复代码中。

## 构建

需要 JDK 21、Android SDK 35。先准备上游固定资产：

```sh
python3 tools/bigfish/prepare_upstream.py
```

复制 `docs/examples/bigfish.local.properties.example` 为 `local.properties`，填写 SDK，以及 `.local/inputs/assets`、`.local/inputs/native` 的绝对路径。对应 `runtimeAssetsDir`、`distributionAssetsDir`、`optionalAssetsDir` 都指向同一个 assets 目录。

先生成实际依赖清单，再补充本分支许可信息并构建：

```sh
./gradlew -PbundledRuntime=true :app:collectReleaseDependencyMetadata
python3 tools/bigfish/collect_notices.py
./gradlew -PbundledRuntime=true :core:test :app:testBundledReleaseUnitTest :app:assembleBundledRelease :app:lintBundledRelease
python3 tools/bigfish/audit_runtime.py
```

安卓界面与文件恢复检查使用独立 `.uicheck` 包，只在模拟器中使用测试数据；不会运行 ARM64 镜像。构建时覆盖可选资产目录，避免把完整镜像带入此测试包：

```sh
mkdir -p .local/empty-native
./gradlew -PverificationSuffix=.uicheck -PnativeLibsDir="$PWD/.local/empty-native" -PoptionalAssetsDir="$PWD/.local/empty-native" :app:assembleBootstrapDebug :app:assembleBootstrapDebugAndroidTest
python3 tools/bigfish/device_checks.py --adb "$ANDROID_HOME/platform-tools/adb"
```

源码包刻意不包含私钥与 1 GB 二进制输入；`prepare_upstream.py` 按固定摘要取得相同上游资产。源码构建不是字节完全相同的可复现签名构建。

正式签名配置使用 `.local/signing/release.properties`，不随源码分发。当前交付保留旧版证书以便覆盖升级；自行构建需要自己的签名，不能覆盖已有不同证书的安装。没有配置发布通道时，更新入口明确显示未配置；不会让上游原版 APK 覆盖本分支。

许可清单中的原始集合记录对应上游发行包。本分支追加 `bigfish/FORK-MANIFEST.json`，绑定当前 Android classpath 与修改后的 MIT core artifact；新增或升级的 Android 模块另附真实 sources.jar、POM 与许可声明，没有把旧清单冒充新源码的验证结果。

## 改动位置

- `BigFishWizardActivity.kt`：原生首次使用与日常管理界面。
- `BigFishInput.kt`：输入与接口地址校验。
- `BigFishModelVerifier.kt`：可取消、无重试和无跳转的模型连接验证。
- `BigFishConfig.kt`：复用 AstrBot / NapCat 的真实配置格式，生成和配对参数。
- `BigFishSetupRepository.kt`：私有配置请求、变更前快照、提交与中断恢复。
- `RuntimeController.kt`：在既有单一操作锁下接入配置任务，复用原有服务和人设操作。
- `tools/bigfish/`：固定资产准备、校验、许可与交付工具。

账号扫码、平台验证码、用户 API Key 与实体手机后台策略不可能在开发机里代替用户完成。测试记录区分静态资源核验、配置 / 网络测试、安卓界面测试和真正的 QQ 账号收发，不把前几项当成最后一项。

## 1.1 轻量扩展与素材

保留同一固定运行镜像，新增 `BigFishFeaturesActivity`、`BigFishOptions`、`BigFishOptionFields`、`BigFishTheme`、`BigFishDeviceReport` 与固定 WebUI 页面入口。复用已有 AstrBot 的知识库、计划任务、插件和会话页面，不宣称另行实现了这些后端。默认关闭识图，按用户要求关闭 STT/TTS，不新装语音、浏览器或本地推理依赖。

网上鲸鱼娘头像取自 [whyever77/dafeiyu-astrbot](https://github.com/whyever77/dafeiyu-astrbot/tree/64274354c4331591620a925b3df498725df4564f)，`12_抱鲸鱼Q版.png`。原始像素不变，使用安卓遮罩和缩放；原仓库说明与完整许可保留在 `docs/artwork/`，APK 功能中心也能查看署名。没有使用本次对话中先前生成的新吉祥物。

兼容构建仍使用 AGP 8.9.1 / Kotlin 2.2.21 / Gradle 8.11.1 / JDK 21；只改变应用资源与管理代码，不升级镜像里的 Python、QQ 或 NapCat。

## 液态玻璃与手机适配

使用 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/eada9a8600142c1bd6a5c5fc143d06f599cdfaef) 的正式 Backdrop 1.0.0（Apache-2.0），复用背景采样、模糊、镜片折射、高光和阴影。应用增加海洋动态背景与统一玻璃组件；Android 13+ 使用 AGSL 折射，Android 12 使用 RenderEffect，旧安卓保留兼容显示。为正确消费其二进制，Kotlin 与 Compose Compiler 升到 2.2.21、Compose UI/Foundation 为 1.9.4；AGP 8.9.1、Gradle 8.11.1、compile/target 35 和 minSdk 26 保持不变。新增依赖实际 sources.jar、POM、许可声明和摘要随 APK 的 notices.zip 分发，旧 Linux 许可记录保留。

手机适配包括：原生设置与菜单共用主题、正文和触控目标、固定底部操作、键盘下一项与完成、搜索时的紧凑布局、保留草稿的主页返回、管理页 100% 默认布局和可缩放视口、同机 QQ 扫码帮助、WebView 缺失时的原生提示。内置网页继续使用 AstrBot/NapCat 原有业务界面，只对本地来源添加手机布局样式，不改变认证或业务请求。

## 1.2 独立界面

应用界面作者：mzbgq。Studio 系列负责全部产品页面；本机 API 适配参考所带 AstrBot 4.29.3 与 NapCat 4.18.41 的公开源码。QR 编码复用 ZXing 3.5.3（Apache-2.0），真实源代码、模块 POM 与父 POM 许可声明随包分发。

## 1.3.0 个人 SnowLuma 平台适配

主 QQ 后端改用 SnowLuma 2.4.2。保留用户提供包的原始 JS 与客户端资源，使用已有同版 Linux ARM64 Node 与原生模块。来源、摘要和第三方原条款见 `docs/legal/snowluma/`，实际验证范围见 `docs/SnowLuma-Linux-ARM64.md`。这些第三方内容不适用本应用代码的 MIT 许可。旧 Ubuntu 镜像不被破坏，QQ 运行副本恢复原始入口并省略 NapCat；聊天记录使用原平台 ID 继续保存。

### 1.3.1 修复

QQ 程序副本使用实际文件复制，避免 PRoot `--link2symlink` 把硬链接改成软链接后，Electron 解析到旧程序目录。复制布局升级为第 2 版，自动重建 1.3.0 的旧缓存；原程序与账号 HOME 保留。登录画面使用安卓原生 Canvas 与受限本机 RFB 适配器，刷新释放并重建对应连接；帧缓冲不放入折射图层。认证和消息格式参考所带 noVNC 1.3.0（https://github.com/novnc/noVNC/tree/v1.3.0），客户端实现为自有代码。

### 1.3.2 修复与手机功能

独立实现受限本机 RFB 协议适配器及共享坐标计算，参考既有 noVNC 协议格式。模型教程、模型列表/最近选择、连接耗时、相册保存与快速排障使用现有 Android、OkHttp、Compose 组件；没有引入额外 UI 模板或机器人运行层。保留所带 AstrBot 4.29.3 的配置继承和便携备份格式，新增 SnowLuma 状态迁移。具体修改和验证边界见 VALIDATION.md。

### 内置陪伴插件

新增 Memory Companion（menglimi，MIT）与 Chat Plus（Him666233，AGPL-3.0）完整源码和离线依赖，原许可不变。具体提交、配置选择、源差异和完整对应源码位置见 [内置插件说明](docs/bundled-plugins.md)。

### 1.3.3 新版 QQ 二进制兼容

- 从腾讯官方获取并校验 Linux ARM64 QQ 3.2.35-54395（QQNT 9.9.37 下载分支），完整包内置离线安装层。独立托管的 QQ 目录可在不替换现有 Ubuntu 的情况下更新，账号与机器人数据不变。
- SnowLuma 原始 JavaScript 和原生模块保持原样；其 ARM64 动态解析器在新版真实 ELF 结构样本中找到发送、接收、登录和状态目标。此步骤不需要 GitHub 原生源码，也未替换为其他后端。
- mzbgq 编写的 guest 兼容层新增自进程内存读取回退，明确启用且原调用被拒绝时才使用操作系统允许的 /proc/self/mem；不扩大其他进程访问范围。
- 新 QQ 原附 Electron/Chromium 许可与确切来源记录一并保留。详情与实际验证边界见 validation/latest-qq-adaptation-1.3.3.json；真实账号和 ARM64 手机验收仍待完成。

### 1.3.4 状态指令修复

修复应用自身生成的 #sl 禁用配置；新增原生状态指令开关，保留用户触发词、冷却与账号继承，匹配后由 SnowLuma 直接回复。厂商 JavaScript 未改动，纯文本匹配与冷却使用原实现；用户已反馈普通文字能正常回复，本次状态指令尚待手机确认。

### 1.3.4 手机运行与显示

由 mzbgq 修改自有 Studio 显示与桌面启动配置：按需大屏/初始放大/手机竖屏/兼容桌面选项，静态玻璃背景默认值，画面队列合并与解码缓冲复用，原生路径不启动网页转发。原厂 QQ 和 SnowLuma 字节不变，不暂停消息核心。参见 mobile-optimization-1.3.4.json 的具体范围与限制。
