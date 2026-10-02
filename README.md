# 看电视 · Android TV IPTV

面向 Android 10 及以上电视 / 盒子的原生 IPTV 客户端。打开应用即播放默认频道，使用遥控器选台。

## 功能

- 启动自动播放指定默认频道；首次优先 CCTV1，没有 CCTV1 时播放列表首个频道。
- 按分组浏览，移除“全部频道”；大分组每页最多 100 台，避免上万频道一次提交给界面。
- 来源分组分别显示 `Z · 分类名`（zbds）和 `I · 分类名`（iptv-org），Z 在前、I 在后。同名频道保留各来源分组入口，共用默认设置和备用线路。
- 全局频道名称查询（中文、大小写、空格 / 连字符规范化）、收藏、默认频道标记和数字编号选台。
- 同一频道的重复条目合并为播放线路；支持手动切换、错误 / 缓冲超时自动换线路。
- 按 URL + 请求头缓存各线路的实际播放可用性。上下换台跳过全部线路失败或手动标为不可用的频道；菜单仍可选择重试，手动停用可取消。
- M3U / TXT 解析，支持 `#genre#` 分类、IPv6 地址、User-Agent / Referer 请求头。
- 五个源并行更新，各自缓存；某个源失败时保留它上次成功的频道列表。
- 缓存优先启动，随后异步静默更新；索引、解析和搜索均在后台执行，源状态变化复用频道索引。源下载失败不覆盖默认频道设置。
- GitHub Release 手动 / 自动更新：默认开启后台检查和下载，加速站优先、GitHub 回退；校验通过后在菜单中安装。
- Media3 / ExoPlayer 播放 HLS、DASH 和普通 HTTP 视频，优先使用设备解码器并允许解码器回退。

“自动播放”指打开应用后的行为。缓存只保存频道列表，观看仍需联网。IPv6 线路需设备网络支持 IPv6。

## 遥控器

| 按键 | 全屏播放 | 频道列表 |
| --- | --- | --- |
| 确定 | 打开列表 | 播放选中频道 |
| 上 / 下 | 当前分组上一台 / 下一台，跳过不可用台 | 分类列连续切分类，频道列移动焦点 |
| 左 / 右 | 上一条 / 下一条线路 | 切换分类 / 频道列 |
| 菜单 | 当前频道设置 | 选中频道设置 |
| 长按确定 | 打开列表 | 默认频道、收藏、线路等操作 |
| 数字键 | 输入频道编号后跳转 | 搜索框输入 |
| 频道 + / - | 当前分组上一台 / 下一台 | 上一页 / 下一页 |
| 搜索键 / 搜索按钮 | — | 输入名称查询所有分组 |
| 播放 / 暂停 | 暂停或继续 | — |
| 返回 | 连按两次退出 | 关闭列表 |

设置默认频道：确定打开列表 → 选中频道 → 菜单 / 长按确定 → **设为默认频道**。之后重新打开应用会自动播放该频道，列表中显示 `★`。默认频道和收藏为独立设置。

仅有方向键的遥控器：列表首个频道 / 首个分类按 **上** → 聚焦 **搜索** → 确定输入名称。搜索框向下返回分类或查询结果。分类获得焦点就显示对应频道，可连续上下切换，按右或确定进入频道列。

不可用状态来自播放错误 / 连接超时；未测试线路仍会尝试，只有全部线路失败才自动跳过。菜单手动选择会按“已可用 → 未测试 → 已失败”尝试全部线路；连续播放 10 秒记为可用，自动失败标记随之恢复。原 URL / 请求头改变后视为新线路。手动停用独立保存，即使重试成功也需菜单取消，不改变已设置的默认频道。数字键输入不可用台编号会打开列表，不直接播放，需在菜单选择重试。

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

## 软件更新与发布

菜单 → **软件更新**：检查更新 → 下载新版 → 安装新版。自动更新默认开启；应用处于前台时每 6 小时检查一次，第一次打开会延迟 10 秒检查。自动下载不弹安装界面、不打断直播；准备好后提示到菜单安装，可在更新界面关闭自动更新。关闭后仍可手动检查和下载。

正式更新查询 `string1225/iptv-player` 的 GitHub `releases/latest`，只接受正式版本 `v主版本.次版本.修订号` 中的 `iptv-player.apk`。下载首先使用 `https://gh-proxy.org/https://github.com/.../releases/download/.../iptv-player.apk`，HTTP 失败、截断、校验失败均回退原下载地址。校验 GitHub 返回的 SHA-256、实际大小、APK 包名、版本、最低系统版本和签名；不同签名不能覆盖更新。

Android 10 安装需要用户允许“安装未知应用”，然后在系统安装界面确认。APK 保存在应用私有目录，通过 FileProvider 授权安装器读取，重启后仍可继续安装。详细签名和发布步骤见 [Release 发布配置](docs/releasing.md)。Release 工作流需要仓库签名 Secrets，推送代码本身不会发布版本。

## 验证

单元测试覆盖 M3U 属性 / 请求头、TXT 多线路、非法条目、频道合并、稳定身份、默认频道选择、公告过滤和有限线路重试。

单元测试还覆盖 12k 频道分组 / 分页、跨分组名称查询、Release 版本 / 资产规则、下载回退、损坏文件和安装前拒绝。

0.2.0 已通过 16 项单元测试、Debug / Release Lint 和构建（Lint 0 errors）；API 29 模拟器通过 13 项原有回归及 16 项新增功能检查，包括 12,348 台频道、实际签名 APK 的下载 / 安装交接与异签名拒绝，无 AndroidRuntime 崩溃。

0.2.1 已通过 21 项单元测试，Debug / Release Lint 0 errors（16 条建议性警告）及构建；API 29 通过 31 项遥控器 / 可用性回归和 16 项分页 / 软件更新回归，无 AndroidRuntime 崩溃。

`tools/tv_smoke.py` 在专用模拟器里通过遥控器按键测试真实播放器、默认频道重启、收藏、换线路、源失败缓存和空列表。测试会清除该模拟器中的本应用数据，要求一个可解码、时长至少两分钟的本地 MP4 文件（建议 faststart）：

```powershell
./gradlew.bat :app:assembleDebug '-PtestPlaylistBaseUrl=http://10.0.2.2:8877'
python tools/tv_smoke.py --adb <adb完整路径> --serial emulator-5556 --media <本地MP4路径>
```

`tools/tv_smoke.py` 还验证方向键进入搜索、连续切分类、Z / I 顺序及选中分组上下换台、线路状态跨进程缓存、手动停用 / 恢复、菜单重试和成功后解除自动排除。

`tools/tv_update_smoke.py` 额外测试 12,348 台分页 / 搜索、慢速远端下缓存先播放、手动 / 自动更新、加速站失败回退、未知来源授权、系统安装确认、重启恢复和错误签名。需要 Debug 同时设置 `-PtestPlaylistBaseUrl` 与 `-PtestUpdateBaseUrl`，并提供 0.2.2 同签名 / 异签名测试 APK（可用 `--update-version` 指定其他更新版本）；运行参数见脚本 `--help`。

两种测试 URL 替换只在 Debug 有效，Release 始终使用真实源 / GitHub。交付调试 APK 前重新运行 **不含任何测试参数** 的正常构建。截图 / 结果在忽略的 `artifacts/` 中。

## 结构与参考

`catalog` 负责解析、频道身份 / 合并和索引 / 分页；`data` 负责列表下载、原子缓存和偏好；`playback` 负责播放及线路恢复；`update` 负责 Release 查询、校验和下载；`ui` 负责电视焦点和界面；`MainActivity` 连接生命周期与遥控器交互。

- [参考 APK 分析与源调查](docs/apk-research.md)
- [首版设计与取舍](docs/android-tv-design.md)

首版不含节目单 EPG、回看、P2P 私有协议、设备开机自启动或账号系统。不申请电话状态和共享存储权限；使用网络权限、更新安装请求权限，以及 Media3 合并的普通 WAKE_LOCK 权限。
