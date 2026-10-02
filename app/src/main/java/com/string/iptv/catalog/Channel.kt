package com.string.iptv.catalog

import java.util.Locale

data class PlaylistSource(val id: String, val name: String, val url: String)

object BuiltInSources {
    val all = listOf(
        PlaylistSource("zbds4m3u", "直播电视 · IPv4 M3U", "https://live.zbds.top/tv/iptv4.m3u"),
        PlaylistSource("zbds4txt", "直播电视 · IPv4 TXT", "https://live.zbds.top/tv/iptv4.txt"),
        PlaylistSource("zbds6m3u", "直播电视 · IPv6 M3U", "https://live.zbds.top/tv/iptv6.m3u"),
        PlaylistSource("zbds6txt", "直播电视 · IPv6 TXT", "https://live.zbds.top/tv/iptv6.txt"),
        PlaylistSource("iptvorg", "IPTV.org · 全球频道", "https://iptv-org.github.io/iptv/index.m3u"),
    )
}

data class Stream(val url: String, val sourceId: String, val headers: Map<String, String> = emptyMap())

data class PlaylistEntry(
    val name: String,
    val group: String,
    val stream: Stream,
    val tvgId: String = "",
    val logo: String = "",
)

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val streams: List<Stream>,
    val logo: String = "",
)

object ChannelIdentity {
    private val quality = Regex("\\s*[（(](?:\\d{3,4}p|[248]K|HD|SD|高清|标清)[）)]", RegexOption.IGNORE_CASE)
    private val cctv = Regex("^cctv[- _]*(\\d{1,2})(\\+?)(?:[- _]*(?:hd|sd|高清|标清|超清))?$", RegexOption.IGNORE_CASE)

    fun key(name: String): String {
        val cleaned = name.replace(quality, "").trim().lowercase(Locale.ROOT)
        val match = cctv.matchEntire(cleaned)
        return if (match != null) "cctv${match.groupValues[1].toInt()}${match.groupValues[2]}"
        else cleaned.replace(Regex("\\s+"), " ")
    }
}

object CatalogMerger {
    fun merge(entries: List<PlaylistEntry>): List<Channel> {
        val groups = entries.groupBy { ChannelIdentity.key(it.name) }
        return groups.map { (key, variants) ->
            val first = variants.first()
            Channel(key, first.name, first.group, variants.map { it.stream }.distinctBy { it.url to it.headers },
                variants.firstOrNull { it.logo.isNotBlank() }?.logo.orEmpty())
        }.sortedWith(compareBy<Channel> { if (it.id.matches(Regex("cctv\\d+\\+?"))) 0 else 1 }
            .thenBy { Regex("^cctv(\\d+)").find(it.id)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { if (it.id.endsWith("+")) 1 else 0 })
    }
}

object StartupChannel {
    fun choose(channels: List<Channel>, defaultId: String?): Channel? =
        channels.firstOrNull { it.id == defaultId }
            ?: channels.firstOrNull { it.id == "cctv1" }
            ?: channels.firstOrNull()
}

/** Tries each route at most once per recovery cycle, including a remembered good route. */
class RouteCycle(private val size: Int, preferredIndex: Int = 0) {
    private val tried = mutableSetOf<Int>()
    var current = if (size > 0) ((preferredIndex % size) + size) % size else -1
        private set
    init { if (current >= 0) tried += current }

    fun next(): Int? {
        if (size <= 0) return null
        val candidate = (1..size).map { (current + it) % size }.firstOrNull { it !in tried } ?: return null
        current = candidate
        tried += candidate
        return current
    }

    fun recovered() { tried.clear(); if (current >= 0) tried += current }
}
