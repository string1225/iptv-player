# GitHub Release 更新与发布

客户端读取 [GitHub latest Release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)，使用资产的 `browser_download_url`、`size`、`digest`（SHA-256），不下载源代码 ZIP、不接受预发布版本。正式 APK 必须命名为 `iptv-player.apk`。

## 每次更新都发布

本项目每次更新默认完成验证、提交推送和新版正式 Release 发布。只推送源码不会产生客户端可见的更新，不能作为完整交付。先递增默认版本，再验证并推送提交和对应标签，等待发布工作流成功；最后确认公开 `releases/latest` 接口指向新版本，APK 资产及 SHA-256 有效，再报告完成。项目执行规则见 [AGENTS.md](../AGENTS.md)。

## 一次配置

持续使用同一个 Android 签名密钥，安全备份，不提交进 Git。将以下四个值配置为仓库 Actions Secrets：

| Secret | 内容 |
| --- | --- |
| `IPTV_KEYSTORE_BASE64` | 发布用 JKS / keystore 文件的 Base64 |
| `IPTV_STORE_PASSWORD` | keystore 密码 |
| `IPTV_KEY_ALIAS` | 密钥别名 |
| `IPTV_KEY_PASSWORD` | 密钥密码 |

工作流缺少任一 Secret 会明确失败，不会生成无法连续更新的临时签名，也不会发布未签名文件。密钥只在 runner 临时目录解码，最后清理。为兼容已交付的 0.3.0 安装包，v0.3.1 起沿用其现有签名密钥作为持续发布签名，Release 构建保持不可调试。后续发布继续使用仓库加密保存的同一密钥，不重新生成；旧版可以覆盖升级并保留数据。

## 发布版本

在已验证的提交上创建并推送正式标签，例如：

```powershell
git tag v0.3.3
git push origin v0.3.3
```

`.github/workflows/release.yml` 执行测试、Release Lint、签名构建和签名验证，再创建 GitHub Release，上传 `iptv-player.apk`、`SHA256SUMS` 和 `decoder-sources.zip`。后者由 `:app:packageDecoderSources` 生成，包含完整未经修改的 JLayer 1.0.1 源码 JAR 和 LGPL 许可，随每次正式发布提供。GitHub 为已上传资产提供摘要，客户端使用 API 摘要验证下载，不依赖加速站的返回值建立信任。

版本为 `major.minor.patch`，`versionCode = major × 1,000,000 + minor × 1,000 + patch`；minor / patch 为 0–999，major 为 0–2000。标签、APK 版本必须一致，发布版本必须单调增加。当前版本 0.3.3 / 3003；下一版例如 v0.3.4 / 3004。不要修改同一 Release 的 APK 来替代发布新版本。

本地有发布密钥时，可通过环境变量 `IPTV_STORE_FILE`（密钥路径）及后三个同名密码 / 别名环境变量构建：

```powershell
./gradlew.bat :app:assembleRelease :app:packageDecoderSources '-PappVersion=0.3.3'
```

未提供密钥时，本地 Release 产物未签名，只用于构建检查，不能作为更新 APK。调试和正式包的三个测试 URL 常量（列表、更新、即时启动线路）相互独立，Release 固定为空，避免将模拟器测试接口发布给用户。

## 客户端流程

- 自动更新默认启用：打开后延迟 10 秒检查，前台使用期间间隔至少 6 小时。未运行时不唤醒设备。失败仅记录在更新界面，继续播放。
- 手动检查始终可用；自动检查发现更新后后台下载，完成后提示用户进入菜单安装。可关闭自动更新，下载中的任务可以完成。
- 优先 `https://gh-proxy.org/` 加上完整 GitHub APK URL；失败或校验不符后重新从 GitHub 原地址下载。只接受本仓库 Release 的 HTTPS 地址。
- 下载到私有目录临时 APK，严格限制大小，核对 GitHub SHA-256，然后读取 APK 核对包名、单调版本、系统兼容性和安装签名。所有检查通过才保存可安装文件；清理旧版本和未完成下载。
- 重启会重新校验已有文件，保留安装入口；已校验文件在后续网络检查失败时仍可安装。网络和元数据错误不会弹窗或切换正在播放的频道。
- 安装遵循 [Android 未知来源授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()) 和系统确认，通过 [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider) 临时授权安装器读取 APK。用户可取消，普通电视应用不做静默强制安装。

## 模拟器检查

`tools/tv_update_smoke.py` 使用本地 Release API、先失败的加速入口和有效直连入口，以及两种签名的真实 APK。它只允许专用模拟器，会清除应用数据。测试验证远端等待时已缓存默认频道先播放、12k 频道分组和搜索、下载回退、自动更新不自动安装、系统授权 / 确认和异签名拒绝。测试工件保存在忽略的 `artifacts/`，密钥和测试 APK 不提交到仓库。
