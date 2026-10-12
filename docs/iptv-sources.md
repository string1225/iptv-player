# IPTV 源整理

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
