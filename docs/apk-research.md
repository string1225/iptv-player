# v20260329.apk 分析

分析日期：2026-10-01。样本由用户提供，仅作为功能 / 交互参考；未复制其代码、图标、SO 或签名。

## 样本信息

| 项目 | 观察结果 |
| --- | --- |
| 文件 | `C:/Users/junte/Downloads/v20260329.apk` |
| 大小 | 13,569,187 字节 |
| SHA-256 | `47502a49034a369b37a32770ace1fa5b6bcf87dcd01e47f249ca9d0b66bd1ea9` |
| 应用名称 | 直播电视 |
| 包名 | com.zb.test |
| 版本 | 稳定版v20260329，versionCode 13 |
| SDK | minSdk 14，targetSdk 23 |
| 入口 | com.vv.test.SplashActivity |
| 播放页 | com.vv.test.PlayerActivity |
| SO 架构 | armeabi-v7a |
| 加固 | com.stub.StubApp、assets/libjiagu.so、assets/libjiagu_x86.so、assets/.jgapp |

使用 Android SDK `aapt dump badging`、`dump xmltree`、资源表和 ZIP 目录检查。DEX 中可见的是加固壳，不能据此还原默认频道保存逻辑、实际联网地址或全部遥控器映射。未运行样本，所以以下界面结论来自布局和资源，不能当作完整运行验证。

## 可确认的界面和组件

- `activity_main.xml`：黑底全屏，包含 XVideoView / IJKVideoView；中央缓冲提示，底部频道名称、线路 `源1/1`、设置、频道列表和节目文字区域。
- `channel_list.xml`：两列 ChannelListView，左侧分类 / 右侧频道，宽度权重 1:2，各有选中背景资源。
- `setting.xml`：设置页两列列表；还有退出确认、频道列表项和媒体信息表布局。
- 图标 / 资源命名包括频道、收藏、设置、暂停 / 播放、焦点选择背景。由这些资源可确认有相关界面元素，但不能确认加固后具体业务行为。
- `libijkffmpeg.so`、`libijkplayer.so`、`libijksdl.so` 与布局说明使用 IJK / FFmpeg 组件；另有 forcetv、mitv、p2p / p3p…p9p 私有播放组件和后台 Service。
- Manifest 含 INTERNET、网络 / Wi-Fi 状态、电话状态、读写共享存储和 BOOT_COMPLETED 权限；有开机广播接收器。声明普通 LAUNCHER，未声明 LEANBACK_LAUNCHER。
- assets/index.html / bundle.js 是旧 React / Bootstrap 页面资源，不能仅凭它们认定主播放界面使用 WebView。

对本项目最有价值的是：打开即看直播、叠加式频道信息、分类 / 频道两列、明显的遥控焦点、收藏 / 线路操作。为 Android 10 重新实现这些交互，使用标准 HTTP 源和 Media3，不引入样本的 ARMv7 私有 SO 或多组 P2P 服务。

## 用户指定源实测

同一网络上响应可能因 User-Agent / 时间变化。初次 Python 默认 User-Agent 对部分源得到 HTTP 403；以客户端 User-Agent 重试后取得下表。数值为当次原始条目数，未合并同频道 / 同地址，也不能代表可播放频道数量。

| 源 | 2026-10-01 当次结果 | 解析关注点 |
| --- | --- | --- |
| IPTV.org index.m3u | HTTP 200，2,514,408 字节，11,129 个 EXTINF | 全球频道、重复线路、分号组合分类、VLC User-Agent / Referer 属性 |
| iptv4.m3u | HTTP 200，122,421 字节，544 个 EXTINF | 央视频道、卫视、地方等，包含 HTTP 和不同线路 |
| iptv4.txt | HTTP 200，59,517 字节，711 个非分类行 | `分类,#genre#` 和 `频道,URL`，同名多条线路 |
| iptv6.m3u | HTTP 200，282 字节，1 个 EXTINF | 仅“更新时间”分组的公告 MP4 |
| iptv6.txt | HTTP 200，268 字节，2 个非分类行 | 日期 / 网站名称对应同一公告 MP4，没有电视节目 |

因此：五个源独立下载、保留各源缓存；合并 M3U / TXT 重复线路；过滤“更新时间”公告和“支持作者”条目；默认设置按频道身份保存而非暂时的播放 URL；IPv6 源恢复后自动刷新重新纳入。

本项目模拟器曾取得 CCTV1 实际视频画面；公共线路会中断、限流或有地区 / IPv6 条件。不能承诺公开列表每条线路可用，客户端以有限换线路和清晰的错误 / 源状态应对。

## 技术依据

- [Android TV 应用清单要求](https://developer.android.com/training/tv/get-started/create)
- [TV 遥控器与 Media3](https://developer.android.com/media/media3/ui/androidtv)
- [Media3 支持格式与设备解码器](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Media3 版本说明](https://developer.android.com/jetpack/androidx/releases/media3)：1.11.1 为 2026-09-10 的稳定版本；1.9 起最低 API 23。

目标硬件明确是 Android 10，因此使用 minSdk 29 和 Media3 1.11.1；提高 minSdk 不会升级电视芯片或直接改变解码性能。优先硬件解码、适当缓冲、及时释放播放器与减少无效网络重试比单独提高版本号更影响体验。
