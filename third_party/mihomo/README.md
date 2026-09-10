# mihomo（内嵌代理内核）

本目录记录 WebHTV 内嵌的 mihomo 内核的来源、版本与许可证信息。

## 版本信息

| 项 | 值 |
|:--|:--|
| 上游项目 | [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) |
| 版本 tag | **v1.19.30** |
| commit | `ac017cdd246ce8bd547653d927e7bf77d7ee73d5`（2026-08-16） |
| 修改情况 | **未修改**，原样编译 |
| 许可证 | **GPL-3.0**（该 tag 的 LICENSE，副本见本目录 `LICENSE`） |
| 分发形式 | C 共享库 `.so`，内嵌进 APK |

> 注意：上游 master 分支在此版本之后已将许可证从 GPL-3.0 改为 MIT。**本项目内嵌的二进制来自 v1.19.30**，因此按 GPL-3.0 分发，并随附对应源码获取方式（见根目录 `THIRD_PARTY_NOTICES.md`）。

## 编译方式（可复现）

```bash
# 目标：Android arm64-v8a / armeabi-v7a，NDK 工具链交叉编译
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
  go build -tags "cmfa with_gvisor" -trimpath -ldflags="-s -w" \
  -buildmode=c-shared -o libmihomo-arm64.so .
```

产物经 strip 后放入：

- `app/src/main/jniLibs/arm64-v8a/libmihomo.so`
- `app/src/main/jniLibs/armeabi-v7a/libmihomo.so`

## 对应源码

GPL-3.0 要求分发二进制时提供对应源码：

- <https://github.com/MetaCubeX/mihomo/tree/v1.19.30>
- 或 `git clone --branch v1.19.30 https://github.com/MetaCubeX/mihomo`

本项目未对源码做任何修改，上述源码 + 上面一条编译命令即可完整复现内嵌的 `.so`。
