# 编译应用源码

源码 ZIP 是本应用 Kotlin/Compose、运行控制层、Python 启动器、兼容层 C 源码、测试与构建脚本。AstrBot、插件、人设模板保留来源、固定版本和许可。SnowLuma 专有模块与腾讯 QQ 为原厂二进制依赖，不是本项目拥有的源码。

要复用本次 APK 的精确完整依赖，解压本次 APK ZIP，核对交付 SHA-256 后执行：

```sh
python3 tools/bigfish/prepare_release_inputs.py \
  --apk /绝对路径/BigFish-Android-1.3.6-arm64-v8a.apk \
  --sha256 交付记录中的APK_SHA256
```

这个步骤只提取运行镜像和编译依赖；不提取账号数据。输出 `.local/inputs` 不存在时才会写入。旧输入已有内容时应换目录，不覆盖原文件。

安装 JDK 21、Android SDK Platform 35/Build Tools 35.0.0，并按 `docs/examples/bigfish.local.properties.example` 建立 `local.properties`。文件中的路径改成自己的绝对路径，输入目录使用上述生成的 `.local/inputs`。Gradle Wrapper 固定为 8.11.1。

```sh
chmod +x gradlew
python3 tools/bigfish/run_gradle.py :core:test :app:testBootstrapDebugUnitTest
python3 tools/bigfish/run_gradle.py :app:assembleBundledRelease -PbundledRuntime=true
```

发布签名配置见 `app/build.gradle.kts`。本项目签名私钥不在源码中，自己构建需要自己的签名；不同签名不能覆盖安装原 APK。调试用途可先构建 `:app:assembleBundledDebug`。

检查完整包：`tools/bigfish/verify_release.py --sdk /你的AndroidSDK` 用于作者签名的本次交付。自行签名的包需要在自己的发布流程中记录证书并调整相应断言。默认不提供自己的更新服务器，语音保持关闭。

兼容库的构建命令和源文件散列见 `app/src/main/assets/runtime/desktop/compat-binary.json`；对应 C 源码随应用源码提供。完整安装资源的 Android 回归需运行 `tools/bigfish/tests/runtime_install_fixture.py` 并经 adb reverse 转发 18936 端口，只用于专用测试应用。
