# 看电视 · Android TV IPTV

面向 Android 10 及以上电视 / 盒子的原生 IPTV 客户端。打开应用即播放默认频道，使用遥控器选台。

## 功能

- 启动自动播放指定默认频道；首次优先 CCTV1，没有 CCTV1 时播放列表首个频道。
- 频道分类、搜索、收藏、默认频道标记和数字编号选台。
- 同一频道的重复条目合并为播放线路；支持手动切换、错误 / 缓冲超时自动换线路。
- M3U / TXT 解析，支持 `#genre#` 分类、IPv6 地址、User-Agent / Referer 请求头。
- 五个源并行更新，各自缓存；某个源失败时保留它上次成功的频道列表。
- 缓存优先启动、源状态展示、后台刷新。源下载失败不覆盖默认频道设置。
- Media3 / ExoPlayer 播放 HLS、DASH 和普通 HTTP 视频，优先使用设备解码器并允许解码器回退。

“自动播放”指打开应用后的行为。缓存只保存频道列表，观看仍需联网。IPv6 线路需设备网络支持 IPv6。

## 遥控器

| 按键 | 全屏播放 | 频道列表 |
| --- | --- | --- |
| 确定 | 打开列表 | 播放选中频道 |
| 上 / 下 | 上一台 / 下一台 | 移动焦点 |
| 左 / 右 | 上一条 / 下一条线路 | 切换分类 / 频道列 |
| 菜单 | 当前频道设置 | 选中频道设置 |
| 长按确定 | 打开列表 | 默认频道、收藏、线路等操作 |
| 数字键 | 输入频道编号后跳转 | 搜索框输入 |
| 播放 / 暂停 | 暂停或继续 | — |
| 返回 | 连按两次退出 | 关闭列表 |

设置默认频道：确定打开列表 → 选中频道 → 菜单 / 长按确定 → **设为默认频道**。之后重新打开应用会自动播放该频道，列表中显示 `★`。默认频道和收藏为独立设置。

## 内置频道源

1. https://live.zbds.top/tv/iptv4.m3u
2. https://live.zbds.top/tv/iptv4.txt
3. https://live.zbds.top/tv/iptv6.m3u
4. https://live.zbds.top/tv/iptv6.txt
5. https://iptv-org.github.io/iptv/index.m3u

最后一个用户输入链接尾部的中文句号已去除。播放线路按上列顺序优先合并；全球列表规模较大，后台更新不会阻塞已有缓存播放。下载成功不等于每条直播线路都能播放，可在菜单中查看源状态 / 选择线路。

## 构建

- Android Studio，JDK 25（本项目验证版本）、Android SDK 36 / Build Tools 36.0.0。
- Gradle Wrapper 9.5.0，Android Gradle Plugin 9.3.0（内置 Kotlin），Media3 1.11.1。
- `minSdk = 29`，`compileSdk = targetSdk = 36`。最低版本不决定硬解能力，实际播放能力取决于设备芯片和系统解码器。

用 Android Studio 打开仓库。命令行设置 `JAVA_HOME`、`ANDROID_HOME`，或者在未跟踪的 `local.properties` 中设置 SDK 路径：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS / Linux 使用 `./gradlew`。可安装的调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。发布版需要自行配置签名；仓库不保存私钥或签名证书。

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 对提交执行单元测试、Lint 和 APK 构建，成功后提供 APK 工件。

## 验证

单元测试覆盖 M3U 属性 / 请求头、TXT 多线路、非法条目、频道合并、稳定身份、默认频道选择、公告过滤和有限线路重试。

已通过 8 项单元测试、Lint 和 APK 构建；Android 10 / API 29 模拟器的 13 项遥控器检查全部通过。

`tools/tv_smoke.py` 在专用模拟器里通过遥控器按键测试真实播放器、默认频道重启、收藏、换线路、源失败缓存和空列表。测试会清除该模拟器中的本应用数据，要求一个可解码、时长至少两分钟的本地 MP4 文件（建议 faststart）：

```powershell
./gradlew.bat :app:assembleDebug -PtestPlaylistBaseUrl=http://10.0.2.2:8877
python tools/tv_smoke.py --adb <adb完整路径> --serial emulator-5556 --media <本地MP4路径>
```

测试源替换只在 Debug 构建有效，Release 始终使用真实内置源。交付调试 APK 前重新运行 **不含** `-PtestPlaylistBaseUrl` 的正常构建。截图 / 结果在忽略的 `artifacts/smoke` 中。

## 结构与参考

`catalog` 负责解析和频道身份 / 合并；`data` 负责列表下载、原子缓存和偏好；`playback` 负责播放及线路恢复；`ui` 负责电视焦点和界面；`MainActivity` 连接生命周期与遥控器交互。

- [参考 APK 分析与源调查](docs/apk-research.md)
- [首版设计与取舍](docs/android-tv-design.md)

首版不含节目单 EPG、回看、P2P 私有协议、设备开机自启动或账号系统。不申请电话状态和共享存储权限；使用网络权限，以及 Media3 合并的普通 WAKE_LOCK 权限。
