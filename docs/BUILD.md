# 源码构建

## 准备

安装 JDK 21、Android SDK Platform 35、Build Tools 35.0.0 和 Python 3；项目使用 Gradle Wrapper 8.11.1。仓库包含应用源码、锁文件、构建脚本及许可材料；完整运行镜像、QQ 与 SnowLuma 的专有二进制依赖不作为源码提供。

仅编译应用与核心源码：

```bash
printf 'sdk.dir=/你的/Android/Sdk\n' > local.properties
python3 tools/bigfish/run_gradle.py :core:compileKotlin :app:compileBootstrapDebugKotlin
```

## 完整 ARM64 包

从可信来源取得与源码对应的 **1.3.8-qqfix 完整 APK**，并核对其 SHA-256（`839c4255da9bc93ce5e26a39aa67eaf9eef1d3e65dbbffe5dac60604df145312`）。解压安装包 ZIP 后执行：

```bash
python3 tools/bigfish/prepare_release_inputs.py \
  --apk /绝对路径/BigFish-Android-1.3.8-qqfix-arm64-v8a.apk \
  --sha256 839c4255da9bc93ce5e26a39aa67eaf9eef1d3e65dbbffe5dac60604df145312
```

脚本仅在输出目录不存在时提取所需运行资源。复制 `docs/examples/bigfish.local.properties.example` 为 `local.properties`，将 SDK、`.local/inputs/assets` 和 `.local/inputs/native` 的路径改成自己的绝对路径，然后执行：

```bash
python3 tools/bigfish/run_gradle.py :core:test :app:testBootstrapDebugUnitTest
python3 tools/bigfish/run_gradle.py :app:assembleBundledRelease -PbundledRuntime=true
```

正式签名配置由 `app/build.gradle.kts` 从本机 `.local/signing/release.properties` 读取。仓库不包含原版本签名私钥。不同签名的 APK 不能覆盖同包名的已安装版本；请使用你自己的密钥并妥善处理备份与迁移，不要上传密钥或密码。未配置签名时，release 产物可能需要另行签名。

可运行 `tools/bigfish/tests/` 下与变更相关的专项检查；涉及真实 QQ 的验证应在自己的设备和账号上完成。有关上游和资源许可，见 [FORK.md](../FORK.md)。
