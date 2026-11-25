# 源码构建

## 准备

安装 JDK 21、Android SDK Platform 35、Build Tools 35.0.0 和 Python 3；项目使用 Gradle Wrapper 8.11.1。仓库包含应用源码、锁文件、构建脚本及许可材料；完整运行镜像、QQ 与 SnowLuma 的专有二进制依赖不作为源码提供。

仅编译应用与核心源码：

```bash
printf 'sdk.dir=/你的/Android/Sdk\n' > local.properties
python3 tools/bigfish/run_gradle.py :core:compileKotlin :app:compileBootstrapDebugKotlin
```

## 完整 ARM64 包

从可信来源取得与源码对应的 **鲸语完整 APK**，并核对发布页提供的 SHA-256。解压安装包 ZIP 后执行：

```bash
python3 tools/bigfish/prepare_release_inputs.py \
  --apk /绝对路径/WhaleTalk-Android-1.3.9-arm64-v8a.apk \
  --sha256 72b1a558f8740ed31de7ef588f24b826e4f4fe1a2673c3578977171269e406ce
```

脚本仅在输出目录不存在时提取所需运行资源。复制 `docs/examples/bigfish.local.properties.example` 为 `local.properties`，将 SDK、`.local/inputs/assets` 和 `.local/inputs/native` 的路径改成自己的绝对路径，然后执行：

```bash
python3 tools/bigfish/run_gradle.py :core:test :app:testBootstrapDebugUnitTest
python3 tools/bigfish/run_gradle.py :app:assembleBundledRelease -PbundledRuntime=true
```

正式签名配置由 `app/build.gradle.kts` 从本机 `.local/signing/release.properties` 读取。仓库不包含原版本签名私钥。不同签名的 APK 不能覆盖同包名的已安装版本；请使用你自己的密钥并妥善处理备份与迁移，不要上传密钥或密码。未配置签名时，release 产物可能需要另行签名。

可运行 `tools/bigfish/tests/` 下与变更相关的专项检查；涉及真实 QQ 的验证应在自己的设备和账号上完成。有关第三方资源许可，见 [THIRD_PARTY.md](../THIRD_PARTY.md)。

## GitHub 自动发布

推送 `v*` 标签（例如 `v1.5.0`）后，[Release APK](../.github/workflows/release.yml) 工作流会构建完整 ARM64 正式包并发布到 GitHub Releases。运行资源从上一份完整安装包中按校验和提取，不把专有二进制放进 Git。正式签名密钥保存在仓库的 Actions Secrets 中，不会进入源码。

若新版本更换了签名证书，已安装的旧包不能直接覆盖升级，需要先备份再卸载后安装。
