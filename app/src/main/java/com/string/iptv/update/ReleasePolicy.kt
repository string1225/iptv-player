package com.string.iptv.update

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    val code: Int get() = major * 1_000_000 + minor * 1_000 + patch
    override fun compareTo(other: AppVersion) = code.compareTo(other.code)
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value) ?: return null
            val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            if (numbers[0] !in 0..2000 || numbers[1] !in 0..999 || numbers[2] !in 0..999) return null
            return AppVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String)
data class AppRelease(val tag: String, val version: AppVersion, val asset: ReleaseAsset, val notes: String)

object ReleasePolicy {
    const val REPOSITORY = "string1225/iptv-player"
    const val APK_NAME = "iptv-player.apk"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 150L * 1024 * 1024

    fun select(tag: String, draft: Boolean, prerelease: Boolean, assets: List<ReleaseAsset>, notes: String): AppRelease {
        require(!draft && !prerelease) { "只接受正式 Release" }
        val version = requireNotNull(AppVersion.parse(tag)) { "Release 标签应为 v主版本.次版本.修订号" }
        val asset = assets.firstOrNull { it.name == APK_NAME } ?: error("Release 缺少 $APK_NAME")
        require(asset.url == "https://github.com/$REPOSITORY/releases/download/$tag/$APK_NAME") { "APK 下载地址不属于本仓库 Release" }
        require(asset.size in 1..MAX_APK_BYTES) { "APK 文件大小无效" }
        require(asset.digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "Release 缺少有效的 SHA-256 校验值" }
        return AppRelease(tag, version, asset, notes.take(2000))
    }

    fun downloadUrls(original: String) = listOf("https://gh-proxy.org/$original", original)
}
