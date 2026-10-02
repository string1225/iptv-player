package com.string.iptv.catalog

import java.util.Locale
import java.security.MessageDigest

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

data class Stream(val url: String, val sourceId: String, val headers: Map<String, String> = emptyMap()) {
    // Source lists can change order or contain the same route; health belongs to URL + headers.
    val healthKey: String by lazy {
        val identity = buildString {
            append(url.length).append(':').append(url)
            headers.toSortedMap().forEach { (name, value) ->
                append(name.length).append(':').append(name).append(value.length).append(':').append(value)
            }
        }
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

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
    val groups: List<String> = listOf(group),
)

object SourceGroups {
    fun label(entry: PlaylistEntry): String = when {
        entry.stream.sourceId.startsWith("zbds") -> "Z · ${entry.group}"
        entry.stream.sourceId == "iptvorg" -> "I · ${entry.group}"
        else -> entry.group
    }

    fun rank(group: String): Int = when {
        group.startsWith("Z · ") -> 0
        group.startsWith("I · ") -> 1
        else -> 2
    }
}

object ChannelIdentity {
    private val quality = Regex("\\s*[（(](?:\\d{3,4}p|[248]K|HD|SD|高清|标清)[）)]", RegexOption.IGNORE_CASE)
    private val cctv = Regex("^cctv[- _]*(\\d{1,2})(\\+?)(?:[- _]*(?:hd|sd|高清|标清|超清))?$", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")

    fun key(name: String): String {
        val cleaned = name.replace(quality, "").trim().lowercase(Locale.ROOT)
        val match = cctv.matchEntire(cleaned)
        return if (match != null) "cctv${match.groupValues[1].toInt()}${match.groupValues[2]}"
        else cleaned.replace(whitespace, " ")
    }
}

object CatalogMerger {
    private val cctvKey = Regex("cctv\\d+\\+?")
    private val cctvNumber = Regex("^cctv(\\d+)")
    fun merge(entries: List<PlaylistEntry>): List<Channel> {
        val groups = entries.groupBy { ChannelIdentity.key(it.name) }
        return groups.map { (key, variants) ->
            val ordered = variants.sortedBy { SourceGroups.rank(SourceGroups.label(it)) }
            val first = ordered.first()
            val memberships = ordered.map(SourceGroups::label).distinct()
            Channel(key, first.name, memberships.first(), ordered.map { it.stream }.distinctBy { it.url to it.headers },
                ordered.firstOrNull { it.logo.isNotBlank() }?.logo.orEmpty(), memberships)
        }.sortedWith(compareBy<Channel> { if (it.id.matches(cctvKey)) 0 else 1 }
            .thenBy { cctvNumber.find(it.id)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
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
class RouteCycle(private val order: List<Int>) {
    constructor(size: Int, preferredIndex: Int = 0) : this(
        if (size > 0) (0 until size).map { (Math.floorMod(preferredIndex, size) + it) % size } else emptyList())
    private val tried = mutableSetOf<Int>()
    var current = order.firstOrNull() ?: -1
        private set
    init { if (current >= 0) tried += current }

    fun next(): Int? {
        val candidate = order.firstOrNull { it !in tried } ?: return null
        current = candidate
        tried += candidate
        return current
    }

    fun recovered() { tried.clear(); if (current >= 0) tried += current }
}
