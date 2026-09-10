# GeoIP / GeoSite 数据文件

WebHTV 内置以下数据文件，用于 mihomo 内核的域名/IP 分流：

| 文件 | 位置 | 用途 |
|:--|:--|:--|
| `geoip.dat` | `app/src/main/assets/mihomo/geoip.dat` | IP 段归属分流 |
| `geosite.dat` | `app/src/main/assets/mihomo/geosite.dat` | 域名分类分流 |

## 来源与许可证

| 项 | 值 |
|:--|:--|
| 上游项目 | [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) |
| 获取渠道 | 上游 Release 的 `geoip.dat` / `geosite.dat`（latest） |
| 许可证 | **GNU GPL-3.0** |
| 修改情况 | 未修改，原样内置 |

## 对应源码 / 数据生成方式

数据文件由上游仓库的规则源与构建脚本生成，详见：

- <https://github.com/MetaCubeX/meta-rules-dat>
- <https://github.com/MetaCubeX/meta-rules-dat/releases>

GPL-3.0 完整许可文本见根目录 [`LICENSE.md`](../../LICENSE.md)；第三方组件总览见根目录 [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md)。
