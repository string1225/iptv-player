package com.string.iptv.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.string.iptv.BuildConfig
import com.string.iptv.catalog.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SourceStatus(
    val source: PlaylistSource,
    val entryCount: Int = 0,
    val cached: Boolean = false,
    val error: String? = null,
    val updatedAt: Long? = null,
    val loading: Boolean = true,
)

data class CatalogSnapshot(val channels: List<Channel>, val sources: List<SourceStatus>, val refreshing: Boolean)

/** Reads cache first; five independent downloads cannot overwrite each other's snapshots. */
class PlaylistRepository(context: Context, private val onUpdate: (CatalogSnapshot) -> Unit) {
    private val directory = File(context.filesDir, "playlists").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private val coordinator = Executors.newSingleThreadExecutor()
    private val downloads = Executors.newFixedThreadPool(5)
    private val busy = AtomicBoolean(false)
    private val sources = if (BuildConfig.DEBUG && BuildConfig.TEST_PLAYLIST_BASE_URL.isNotEmpty()) {
        BuiltInSources.all.map { it.copy(url = "${BuildConfig.TEST_PLAYLIST_BASE_URL}/${it.id}.playlist") }
    } else BuiltInSources.all
    @Volatile private var closed = false
    private val sourceEntries = linkedMapOf<String, List<PlaylistEntry>>()
    private val statuses = linkedMapOf<String, SourceStatus>()

    fun refresh() {
        if (closed || !busy.compareAndSet(false, true)) return
        coordinator.execute {
            try {
                for (source in sources) {
                    if (source.id !in sourceEntries) {
                        val file = File(directory, "${source.id}.playlist")
                        val parsed = runCatching { PlaylistParser.parse(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }, source.id) }.getOrDefault(emptyList())
                        sourceEntries[source.id] = parsed
                        statuses[source.id] = SourceStatus(source, parsed.size, parsed.isNotEmpty(), updatedAt = file.lastModified().takeIf { it > 0 })
                    } else statuses[source.id] = statuses.getValue(source.id).copy(loading = true, error = null)
                }
                publish(true)
                val completions = ExecutorCompletionService<DownloadResult>(downloads)
                sources.forEach { source -> completions.submit { download(source) } }
                repeat(sources.size) { index ->
                    if (closed) return@execute
                    val result = completions.take().get()
                    val source = result.source
                    if (result.text != null && result.entries.isNotEmpty()) {
                        sourceEntries[source.id] = result.entries
                        val file = AtomicFile(File(directory, "${source.id}.playlist"))
                        val saved = runCatching {
                            val stream = file.startWrite()
                            try { stream.write(result.text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                            catch (error: Exception) { file.failWrite(stream); throw error }
                        }.isSuccess
                        statuses[source.id] = SourceStatus(source, result.entries.size, updatedAt = System.currentTimeMillis(),
                            error = if (saved) null else "频道可用，但缓存写入失败", loading = false)
                    } else {
                        val cachedEntries = sourceEntries[source.id].orEmpty()
                        statuses[source.id] = statuses.getValue(source.id).copy(entryCount = cachedEntries.size,
                            cached = cachedEntries.isNotEmpty(), error = result.error ?: "未找到可播放的 HTTP 频道", loading = false)
                    }
                    publish(index != sources.lastIndex)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally { busy.set(false) }
        }
    }

    private fun publish(refreshing: Boolean) {
        if (closed) return
        val entries = sources.flatMap { sourceEntries[it.id].orEmpty() }
        val snapshot = CatalogSnapshot(CatalogMerger.merge(entries), sources.map { statuses.getValue(it.id) }, refreshing)
        main.post { if (!closed) onUpdate(snapshot) }
    }

    private fun download(source: PlaylistSource): DownloadResult {
        val connection = URL(source.url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            val deadline = System.nanoTime() + 25_000_000_000L
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (System.nanoTime() > deadline) throw IOException("下载超时")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) throw IOException("频道列表超过 8 MB")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = bytes.toString(Charsets.UTF_8)
            DownloadResult(source, text, PlaylistParser.parse(text, source.id), null)
        } catch (error: Exception) {
            DownloadResult(source, null, emptyList(), when (error) {
                is java.net.SocketTimeoutException -> "连接或读取超时"
                is java.net.UnknownHostException -> "无法解析服务器地址"
                else -> error.message?.take(120) ?: "下载失败"
            })
        } finally { connection.disconnect() }
    }

    fun close() {
        closed = true
        coordinator.shutdownNow()
        downloads.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private data class DownloadResult(val source: PlaylistSource, val text: String?, val entries: List<PlaylistEntry>, val error: String?)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Android TV) IPTVPlayer/0.1"
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}
