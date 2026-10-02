package com.string.iptv.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.string.iptv.BuildConfig
import com.string.iptv.data.Preferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, CURRENT, NO_RELEASE, ERROR }
data class UpdateState(val phase: UpdatePhase, val message: String, val release: AppRelease? = null, val file: File? = null)

/** Runs only while this app is in use; no install or permission dialog interrupts playback. */
class UpdateManager(context: Context, private val preferences: Preferences, private val onUpdate: (UpdateState) -> Unit) {
    private val context = context.applicationContext
    private val directory = File(context.filesDir, "updates").apply { mkdirs() }
    private val metadata = AtomicFile(File(directory, "release.json"))
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val fixture = BuildConfig.TEST_UPDATE_BASE_URL.takeIf { BuildConfig.DEBUG && it.isNotEmpty() }
    private val downloader = ReleaseDownloader(urls = { url ->
        if (fixture != null) listOf("$fixture/proxy.apk", "$fixture/direct.apk") else ReleasePolicy.downloadUrls(url)
    })
    @Volatile private var closed = false
    var state = UpdateState(UpdatePhase.IDLE, "当前版本 ${BuildConfig.VERSION_NAME}，可手动检查更新")
        private set
    private val autoCheck = object : Runnable {
        override fun run() {
            if (preferences.autoUpdate && System.currentTimeMillis() - preferences.lastUpdateCheck >= CHECK_INTERVAL) check(false)
            main.postDelayed(this, 60_000)
        }
    }

    init {
        busy.set(true)
        worker.execute {
            var retained: File? = null
            try {
                val json = metadata.openRead().bufferedReader().use { it.readText() }
                val release = parseRelease(json)
                val file = fileFor(release)
                if (isNewer(release) && file.isFile) {
                    verifyDigest(file, release.asset.digest)
                    validateApk(file, release)
                    retained = file
                    publish(UpdateState(UpdatePhase.READY, "新版 ${release.version} 已下载，可安装", release, file))
                } else { file.delete(); metadata.delete() }
            } catch (_: Exception) { metadata.delete() }
            finally { pruneFiles(retained); busy.set(false) }
        }
    }

    fun start() { main.removeCallbacks(autoCheck); main.postDelayed(autoCheck, 10_000) }
    fun stop() { main.removeCallbacks(autoCheck) }

    fun check(manual: Boolean = true) {
        if (closed || !busy.compareAndSet(false, true)) return
        val downloaded = state.takeIf { it.phase == UpdatePhase.READY && it.file?.isFile == true }
        publish(UpdateState(UpdatePhase.CHECKING, "正在检查 GitHub Release…"))
        worker.execute {
            try {
                val request = Request.Builder().url(fixture?.let { "$it/latest" } ?: ReleasePolicy.API)
                    .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "IPTVPlayer-AndroidTV/${BuildConfig.VERSION_NAME}").build()
                val json = client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        publish(UpdateState(UpdatePhase.NO_RELEASE, "仓库尚未发布正式版本"))
                        return@execute
                    }
                    if (response.code == 403 || response.code == 429) throw IOException("GitHub 检查频率受限，请稍后重试")
                    if (!response.isSuccessful) throw IOException("检查更新失败：HTTP ${response.code}")
                    val body = response.body ?: throw IOException("空响应")
                    val bytes = body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 256 * 1024) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size > 256 * 1024) throw IOException("Release 信息过大")
                    bytes.toString(Charsets.UTF_8)
                }
                val release = parseRelease(json)
                if (!isNewer(release)) {
                    publish(UpdateState(UpdatePhase.CURRENT, "当前已是最新正式版本（${BuildConfig.VERSION_NAME}）"))
                    return@execute
                }
                val file = fileFor(release)
                if (file.exists()) {
                    val valid = runCatching { verifyDigest(file, release.asset.digest); validateApk(file, release) }.isSuccess
                    if (valid) { saveMetadata(json); publish(UpdateState(UpdatePhase.READY, "新版 ${release.version} 已下载，可安装", release, file)); return@execute }
                    file.delete()
                }
                saveMetadata(json)
                publish(UpdateState(UpdatePhase.AVAILABLE, "发现新版 ${release.version}", release))
                if (!manual && preferences.autoUpdate) downloadWork(release)
            } catch (error: Exception) {
                val reason = error.message?.take(500) ?: "检查更新失败"
                publish(downloaded?.copy(message = "$reason\n已有校验通过的 ${downloaded.release?.version} 更新，可离线安装")
                    ?: UpdateState(UpdatePhase.ERROR, reason))
            } finally {
                preferences.lastUpdateCheck = System.currentTimeMillis()
                busy.set(false)
            }
        }
    }

    fun download() {
        val release = state.release ?: return
        if (closed || !busy.compareAndSet(false, true)) return
        worker.execute { try { downloadWork(release) } finally { busy.set(false) } }
    }

    private fun downloadWork(release: AppRelease) {
        try {
            val file = fileFor(release)
            pruneFiles(file)
            downloader.download(release.asset, file, { validateApk(it, release) }) { percent, accelerated ->
                publish(UpdateState(UpdatePhase.DOWNLOADING,
                    "正在后台下载 ${release.version} · $percent% · ${if (accelerated) "加速站" else "GitHub 直连"}", release))
            }
            publish(UpdateState(UpdatePhase.READY, "新版 ${release.version} 已下载并校验，可安装", release, file))
        } catch (error: Exception) {
            publish(UpdateState(UpdatePhase.ERROR, error.message?.take(500) ?: "下载失败", release))
        }
    }

    private fun isNewer(release: AppRelease) = release.version.code > BuildConfig.VERSION_CODE
    private fun fileFor(release: AppRelease) = File(directory, "update-${release.version.code}.apk")

    private fun pruneFiles(keep: File?) {
        directory.listFiles()?.filter { it.name.matches(Regex("update-\\d+(?:\\.part)?\\.apk")) && it != keep }?.forEach { it.delete() }
    }

    private fun saveMetadata(json: String) {
        val output = metadata.startWrite()
        try { output.write(json.toByteArray(Charsets.UTF_8)); metadata.finishWrite(output) }
        catch (error: Exception) { metadata.failWrite(output); throw error }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: AppRelease) {
        val manager = context.packageManager
        // Android 10's archive parser only collects certificates when GET_SIGNATURES
        // is set; request both flags so signingInfo is populated and cryptographically verified.
        val archive = manager.getPackageArchiveInfo(file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
            ?: throw IOException("不是有效的 APK")
        if (archive.packageName != BuildConfig.APPLICATION_ID) throw IOException("APK 包名不匹配")
        if (archive.longVersionCode != release.version.code.toLong() || archive.longVersionCode <= BuildConfig.VERSION_CODE) throw IOException("APK 版本号与 Release 不符")
        if ((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) throw IOException("新版不支持当前 Android 版本")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            ?: throw IOException("无法读取当前签名")
        val incoming = archive.signingInfo ?: throw IOException("APK 未签名")
        val current = installed.apkContentsSigners.map { it.toCharsString() }.toSet()
        val accepted = if (installed.hasMultipleSigners() || incoming.hasMultipleSigners())
            current == incoming.apkContentsSigners.map { it.toCharsString() }.toSet()
        else incoming.signingCertificateHistory.map { it.toCharsString() }.toSet().containsAll(current)
        if (!accepted) throw IOException("APK 签名与当前安装不一致，不能覆盖更新")
    }

    private fun verifyDigest(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        if ("sha256:" + digest.digest().joinToString("") { "%02x".format(it) } != expected.lowercase()) throw IOException("APK SHA-256 校验失败")
    }

    private fun publish(value: UpdateState) {
        main.post { if (!closed) { state = value; onUpdate(value) } }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        client.dispatcher.cancelAll()
        downloader.close()
    }

    companion object {
        private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
        fun parseRelease(text: String): AppRelease {
            val json = JSONObject(text)
            val assets = json.getJSONArray("assets")
            return ReleasePolicy.select(json.getString("tag_name"), json.optBoolean("draft"), json.optBoolean("prerelease"),
                (0 until assets.length()).map { i -> assets.getJSONObject(i).let {
                    ReleaseAsset(it.getString("name"), it.getString("browser_download_url"), it.getLong("size"), it.optString("digest"))
                } }, json.optString("body"))
        }
    }
}
