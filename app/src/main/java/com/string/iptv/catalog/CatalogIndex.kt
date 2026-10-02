package com.string.iptv.catalog

import java.util.Locale

/** Immutable; construct on the repository worker, share between playback and the drawer. */
class CatalogIndex(val channels: List<Channel>) {
    val byId = channels.associateBy { it.id }
    val numbers = channels.mapIndexed { index, channel -> channel.id to index + 1 }.toMap()
    val byGroup: Map<String, List<Channel>> = buildMap<String, MutableList<Channel>> {
        channels.forEach { channel ->
            channel.groups.distinct().forEach { getOrPut(it) { mutableListOf() }.add(channel) }
        }
    }.entries.sortedBy { SourceGroups.rank(it.key) }.associate { it.key to it.value.toList() }
    val groups = byGroup.keys.toList()
    private val names = channels.map { normalize(it.name) + "\n" + normalize(it.id) }

    /** Searches all groups, without rebuilding normalized names on each keystroke. */
    fun search(query: String): List<Channel> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        return channels.filterIndexed { index, _ -> names[index].contains(needle) }
    }

    fun favorites(ids: Set<String>): List<Channel> = ids.mapNotNull(byId::get).sortedBy { numbers[it.id] }

    companion object {
        val EMPTY = CatalogIndex(emptyList())
        private val separators = Regex("[\\s\\-‐‑–—]+")
        fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace(separators, "")
    }
}

data class ChannelPage(val channels: List<Channel>, val number: Int, val pages: Int, val total: Int) {
    companion object {
        const val SIZE = 100
        fun from(channels: List<Channel>, requestedPage: Int): ChannelPage {
            val pages = ((channels.size + SIZE - 1) / SIZE).coerceAtLeast(1)
            val number = requestedPage.coerceIn(0, pages - 1)
            val start = number * SIZE
            return ChannelPage(channels.subList(start, (start + SIZE).coerceAtMost(channels.size)), number, pages, channels.size)
        }
    }
}
