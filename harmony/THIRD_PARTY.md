# 鸿蒙运行层来源

鲸语原创源代码沿用根目录 MIT 许可。下列第三方组件按各自许可分发；根目录 MIT 不替代它们的条款。

| 组件 | 来源与说明 |
| --- | --- |
| HiSH | https://github.com/harmoninux/HiSH ，MIT，Copyright (c) 2025 hackeris。执行核结构和 QEMU 工具链参考项目，固定提交见锁文件。 |
| QEMU | https://github.com/harmoninux/qemu ，固定 `hish-libqemu` 源码；GPL-2.0 及其各目录条款，保留 COPYING/COPYING.LIB 等原文件。 |
| Linux 内核 | https://github.com/harmoninux/linux-config 与 https://github.com/torvalds/linux ，内核资源固定摘要，GPL-2.0；构建配置及内核版本按上游资源发布说明。 |
| GLib、libslirp、zlib 等 | 来自 HiSH 固定工具链，适用 LGPL／BSD／zlib 等各自条款。云编译保留来源与构建记录，不将其改标为鲸语原创。 |
| Ubuntu、Python、AstrBot 与陪伴插件 | 从鲸语 1.7.0 完整 APK 按摘要提取，原有 `third-party`、运行资源、插件来源及许可材料一并保留在 guest 的 `/usr/share/whaletalk/assets`。 |
| Linux QQ、SnowLuma | 专有程序，来源与摘要按既有鲸语正式发布 APK 的锁文件。不是本仓库拥有的源码。 |

HiSH MIT 许可原文见 `licenses/HiSH-MIT.txt`。QEMU、Linux 和其他第三方源码、许可证及编译修改以锁定的对应上游源码和构建脚本为准。分发构建时保留对应源码取得方式与许可材料。
