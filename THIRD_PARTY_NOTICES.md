# 第三方组件与开源许可声明 (Third-Party Notices)

本仓库（`yydfys/webhtv-bd`）是 WebHomeTV 生态的二次开发分支，**整体以 GNU GPL-3.0 许可发布**，完整许可文本见根目录 [`LICENSE.md`](LICENSE.md)。

本文件用于说明随 **Release APK 一起分发**的第三方组件、其许可证、以及 GPL 组件「对应源码（Corresponding Source）」的获取方式。

---

## 1. 本项目本体

| 项目 | 说明 |
|:--|:--|
| 名称 | WebHTV（基于 webhome-devkit，`com.silent.android.webhtv`） |
| 许可证 | **GNU GPL-3.0**（见 `LICENSE.md`） |
| 上游链 | [fish2018/webhtv](https://github.com/fish2018/webhtv) → [Silent1566/webhtv](https://github.com/Silent1566/webhtv) → 本仓库 |
| 完整源码 | 本仓库即为完整源码（含本分支的全部修改） |

依据 GPL-3.0，本仓库的所有修改均以同一许可证开放，**源码随二进制一并提供**。

---

## 2. 随 APK 内嵌的第三方组件

| 组件 | 版本 / 标识 | 许可证 | 在本仓库中的位置 | 对应源码 |
|:--|:--|:--|:--|:--|
| **mihomo**（内嵌代理内核，以 C 共享库形式集成） | **v1.19.30**，commit `ac017cdd246ce8bd547653d927e7bf77d7ee73d5` | **GPL-3.0**（该 tag 的 LICENSE；上游 master 之后已改为 MIT，本内嵌版本仍按 GPL-3.0 分发） | `app/src/main/jniLibs/arm64-v8a/libmihomo.so`、`app/src/main/jniLibs/armeabi-v7a/libmihomo.so` | <https://github.com/MetaCubeX/mihomo/tree/v1.19.30> |
| **GeoIP / GeoSite 数据** | `geoip.dat`、`geosite.dat`（随发布内置） | **GPL-3.0** | `app/src/main/assets/mihomo/` | <https://github.com/MetaCubeX/meta-rules-dat> |
| AndroidX Media3（FongMi 定制构建） | `1.11.0-alpha01-fongmi` | Apache-2.0 | `third_party/maven/androidx/media3/` | 见 `third_party/fongmi-repositories-lock.json` |
| libdovi / libplacebo / shaderc（DoVi 播放链） | 见锁文件 | 各自许可证文本已随仓库提供 | `third_party/exo-dv5-native/`（`licenses/`、`MANIFEST.sha256`） | 见同目录 `README.md` |
| ijkplayer 原生库 | 见锁文件 | 见锁文件 | `third_party/ijk-native-lock.json` | 见锁文件 |
| mpv / FFmpeg、Python、Node.js、QuickJS、catvod runtime | 随工程内置 | 各自上游许可证 | `app/src/main/assets/`、`nodejs/`、`quickjs/`、`catvod/`、`serverless/` | 各上游项目 |

> 说明：pinned 依赖的精确版本以仓库中的 `third_party/*-lock.json` 与 `third_party/*/MANIFEST.sha256` 为准。

---

## 3. GPL 组件「对应源码」获取方式

GPL-3.0 第 6 条要求：以目标代码形式分发时，须同时提供**对应源码**。本项目的提供方式如下：

1. **本应用本体**：源码即本仓库（含全部修改）。获取：`git clone https://github.com/yydfys/webhtv-bd`（分支 `vpn-mihomo-tun`）。
2. **mihomo v1.19.30**（内嵌 `libmihomo.so`）：
   - 本仓库内置的 `.so` 由**未经修改**的上游 v1.19.30 源码编译而成（对应 commit `ac017cdd246ce8bd547653d927e7bf77d7ee73d5`）；
   - 对应源码：<https://github.com/MetaCubeX/mihomo/tree/v1.19.30>（或该 tag 的源码包 / `git archive`）；
   - 编译方式（可完整复现）：
     ```
     go build -tags "cmfa with_gvisor" -trimpath -ldflags="-s -w" \
       -buildmode=c-shared -o libmihomo.so .
     ```
     （目标平台 `GOOS=android GOARCH=arm64 / arm`，NDK 工具链）
   - 该 tag 的 GPL-3.0 许可文本副本见 [`third_party/mihomo/LICENSE`](third_party/mihomo/LICENSE)。
3. **GeoIP / GeoSite 数据**：数据文件及其生成脚本见 <https://github.com/MetaCubeX/meta-rules-dat>，许可文本同为 GPL-3.0（见 `LICENSE.md`）。

若需以其他方式获取上述对应源码，可通过本仓库 Issue 提出，我们会一并提供。

---

## 4. 免责与声明

- 本项目为技术学习与研究用途的免费开源软件，**不提供、不存储、不分发任何影视内容、接口源、直播源或资源**；
- 用户在应用内自行配置的任何第三方源、接口与内容，均与本项目无关，由用户自行承担相应责任；
- 本项目不含任何付费服务，也不对第三方内容的可用性、合法性作出任何承诺。

---

*本声明随项目一起以 GPL-3.0 发布；新增内嵌组件时请同步更新本文件。*
