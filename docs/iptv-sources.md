# IPTV 源整理

2026-10-31（0.3.3）逐条复核用户 [IPV6.m3u](../app/src/main/assets/playlists/IPV6.m3u) 与 Z·直播电视（zbds），审查记录见 [channel-audit-20261031.json](channel-audit-20261031.json)。用户源仍优先于 Z、D、I；东方卫视即时启动使用独立的两条 IPv4 线路，完整目录到达不重连当前播放。之后选台继续遵循源优先级。

| 来源 | 原记录 | 删除未通过记录 | 去重 | 保留 |
| --- | ---: | ---: | ---: | ---: |
| 用户 IPV6.m3u | 142 | 65 | 0 | 77 |
| Z IPv4 M3U | 444 | 33 | 1 | 410 |
| Z IPv4 TXT | 572 | 48 | 6 | 518 |
| Z IPv6 M3U / TXT | 0 | 0 | 0 | 0 |

Z 两个 IPv6 列表只有更新公告，真实应用解析器过滤后为零直播条目，已从源配置移除。其余三份清理结果为内置快照，随版本更新；刷新不下载未经审查的 Z 列表，也不读取旧 Z 缓存，避免坏线路重新进入目录。D 的 7 个补充源及 I 全球源继续按原方式后台刷新，本轮未逐台检查。

1,158 条原记录共有 703 条不同的 URL＋请求头线路，全部经过直播媒体检测；590 条通过，113 条多轮未通过。失败原因：连接 / 媒体超时 58、DNS 失败 13、HTTP 404 为 6、HTTP 403 为 2、证书过期 33、证书域名不符 1。用户源保留的 77 条均为 IPv6 字面地址；Z 保留条目均使用 IPv4 / 域名。另将 34 条实测只有音频的不同线路标注“（广播）”并归入广播分类，避免合并成同名电视台的备用线路；未核验广播内容与上游名称是否相符。

检测使用应用 `PlaylistParser` 导出，不运行列表内的任何内容；直连拉取 HLS 列表、子列表和真实媒体分片，用 FFmpeg 解码音频及视频，广播只需音频。所有失败项至少复测一次，保留 TLS 校验；媒体超时但已取得足量数据时，仍必须实际解码通过。IPv6 探测绑定已验证可用的本机 IPv6 地址，避免系统选中失效前缀造成误判，未修改系统网络设置。结果只描述检测网络与时刻，不代表其他地区或未来永久无效。

复用工具：`tools/ExportPlaylists.java` 通过编译后的应用解析器导出记录；`tools/audit_channels.py` 对导出记录全量检测并复测失败项，`--resume` 仅重测未通过项；`tools/curate_channels.py` 从审核结果生成三份快照与公开报告。具体参数见各脚本 `--help`。输入原文摘要、逐线路判定、保留文件摘要均在审查记录；媒体样本留在忽略的 `artifacts/`，不提交节目内容。更新快照后需运行单元测试、启动回归并发布新版。

以下为 2026-10-12 的补充源收集记录；用户提供的文件不属于这 30 个公开调查 URL。

检查日期：2026-10-12。索引入口为 [dongyubin/IPTV](https://github.com/dongyubin/IPTV)，参考提交 `d12ec1b907d1c63067a66c8d7a5b445d8fa80dd5`。仓库同时列出播放器、网页、订阅套餐和播放列表；这里收集可供客户端读取的公开 M3U / TXT 地址。

[机器可读清单](iptv-sources.json) 共 30 个地址，记录规范地址、镜像关系、上游废弃标记、本次检查结果及默认接入状态。本次 16 个返回可解析的 HTTP(S) 播放列表，14 个未读取到支持格式。检查的是列表读取与解析，不能据此保证每条直播线路可播放。

## 默认接入的 7 个补充源

0.3.0 在既有四个 zbds 列表与 iptv-org 全球列表之外增加以下源。显示前缀 `D`，分类在 `Z` 之后、`I` 之前。同名频道共用线路、默认设置和收藏；地方类目合并到各来源自己的“地方频道 → 地区”菜单。

| 名称 | 规范地址 | 本次 HTTP(S) 条目数 | 说明 |
| --- | --- | ---: | --- |
| myernestlu | [zby.txt](https://myernestlu.github.io/zby.txt) | 632 | TXT 综合列表 |
| zbefine | [iptv.m3u](https://raw.githubusercontent.com/zbefine/iptv/main/iptv.m3u) | 1,286 | 含独立省级类目，地方频道补充较多 |
| 茶客 | [M3U](https://raw.githubusercontent.com/vamoschuck/TV/main/M3U) | 788 | 部分线路限制地区或运营商 |
| BigBigGrandG | [Gather.m3u](https://raw.githubusercontent.com/BigBigGrandG/IPTV-URL/release/Gather.m3u) | 1,955 | 综合列表，含 IPv6 地址 |
| Kimentanm | [iptv.m3u](https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u) | 119 | 央视、卫视等 |
| YanG | [Gather.m3u](https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u) | 123 | 影视、咪咕及赛事类目 |
| 范明明 | [ipv6.m3u](https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u) | 82 | 含 55 条使用 IPv6 字面地址的线路 |

7 个列表另外经过本项目的 `PlaylistParser` 复核，总计 4,985 条支持协议的条目；条目数包含同名台与重复线路。GitHub Raw 列表优先使用 `gh-proxy.org/` 加完整原地址，连接失败、HTML 错误页或无有效条目时回退原地址；范明明还保留 [国内镜像](https://live.fanmingming.cn/tv/m3u/ipv6.m3u)。镜像不作为新源重复下载。每个源仍有独立原子缓存，全部候选地址共用 25 秒预算，最多 5 个并发任务。

## 收集但未默认接入

| 地址 | 条目数 | 处理 |
| --- | ---: | --- |
| [EPG.pw 大陆](https://epg.pw/test_channels.m3u) | 2,272 | 备用列表，先保留在清单 |
| [EPG.pw 香港](https://epg.pw/test_channels_hong_kong.m3u) | 48 | 备用列表 |
| [EPG.pw 澳门](https://epg.pw/test_channels_macau.m3u) | 19 | 备用列表 |
| [EPG.pw 台湾](https://epg.pw/test_channels_taiwan.m3u) | 65 | 备用列表 |
| [EPG.pw 新加坡](https://epg.pw/test_channels_singapore.m3u) | 9 | 备用列表 |
| [EPG.pw 马来西亚](https://epg.pw/test_channels_malaysia.m3u) | 20 | 备用列表 |
| [海外赛事列表](https://kan.waiguotai.top/other.m3u) | 2 | 事件类列表，需另行验证当前赛事是否仍在播 |
| [范明明镜像](https://live.fanmingming.cn/tv/m3u/ipv6.m3u) | 82 | 作为下载回退；不新增频道源 |
| [iptv-org 台湾](https://iptv-org.github.io/iptv/countries/tw.m3u) | 25 | 已由全球列表覆盖；不重复接入 |

YueChan 原址 / CDN 及 xxy002 本次未得到可解析列表，未接入。上游已划掉的 Ftindy 五个地址、旧范明明 v6、whpsky 原址 / CDN、ZhangHongGuang、YIPTV 及 228088 旧地址也未接入；逐条错误记录见 JSON 清单。收费账户、Xtream 登录、Docker 示例的 localhost 地址、TVBox JSON、播放器下载和工具网站不是可直接加入本客户端的公开播放列表。

## 地区分类依据

省级类别以列表元数据为先，地方类目再按频道的省、市、区县名称前缀识别。补充处理第一财经、中国蓝、BTV、兵团等常见名称；同名区县不能可靠区分时放进“其他地区”，不丢弃频道。只有当前源实际存在的地区才出现在二级菜单，频道名称查询仍跨地区与来源。

地名别名来源为 [Administrative-divisions-of-China](https://github.com/modood/Administrative-divisions-of-China) 的 2023-06-30 数据，参考提交 `c49d495b40ac73eb1a66f6eeae5f8fd10696f035`，另补港澳台名称。该项目数据已停止更新，新的地名可以追加到生成器或别名补充表。这是频道导航规则，不用于行政区划查询。许可原文见 [china-regions-LICENSE.txt](china-regions-LICENSE.txt)。

`tools/build_region_aliases.py` 从本地 `pca-code.json` 生成别名，剔除重名歧义及已被短前缀覆盖的长名称，共 3,191 个前缀。运行时按首字索引，在目录工作线程建立地区分组。下载内容摘要相同时跳过重新解析，源无变化时复用目录；变化时复用未改变的频道对象，以减少重启和刷新时的内存占用。

## 复查

```powershell
python tools/audit_sources.py
```

默认将下载与检查结果保存到忽略的 `tools/.cache/`，不会执行远端仓库中的脚本，不逐台扫描直播流。重新检查后，需要评估结果再修改接入列表；应用不会随上游 README 的变更自动增加未知源。
