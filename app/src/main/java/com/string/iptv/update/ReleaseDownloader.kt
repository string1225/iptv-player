package com.string.iptv.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A failed accelerator, corrupt body or interrupted transfer is retried at the origin. */
class ReleaseDownloader(
    private val urls: (String) -> List<String> = ReleasePolicy::downloadUrls,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build(),
) {
    fun close() { client.dispatcher.cancelAll() }
    fun download(asset: ReleaseAsset, target: File, validateApk: (File) -> Unit, progress: (Int, Boolean) -> Unit) {
        require(asset.size in 1..ReleasePolicy.MAX_APK_BYTES)
        val expected = asset.digest.removePrefix("sha256:").lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val part = File(target.parentFile, "${target.nameWithoutExtension}.part.apk")
        val failures = mutableListOf<String>()
        for ((attempt, url) in urls(asset.url).withIndex()) {
            try {
                progress(0, attempt == 0)
                client.newCall(Request.Builder().url(url).header("User-Agent", "IPTVPlayer-AndroidTV").build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("空响应")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    var lastProgress = 0L
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > asset.size) throw IOException("APK 大小不符")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgress > 1_000_000_000L) {
                                progress((received * 100 / asset.size).toInt(), attempt == 0)
                                lastProgress = now
                            }
                        }
                    } }
                    if (received != asset.size) throw IOException("APK 下载不完整")
                    if (digest.digest().joinToString("") { "%02x".format(it) } != expected) throw IOException("APK SHA-256 校验失败")
                }
                validateApk(part)
                if (!part.renameTo(target)) throw IOException("无法保存更新文件")
                return
            } catch (error: Exception) {
                part.delete()
                failures += "${if (attempt == 0) "加速站" else "GitHub"}：${error.message?.take(150)}"
                if (Thread.currentThread().isInterrupted) throw IOException("下载已取消", error)
            }
        }
        throw IOException(failures.joinToString("\n"))
    }
}
