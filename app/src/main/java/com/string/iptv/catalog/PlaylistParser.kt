package com.string.iptv.catalog

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

object PlaylistParser {
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val supported = setOf("http", "https")

    fun parse(text: String, sourceId: String): List<PlaylistEntry> {
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }
        val entries = mutableListOf<PlaylistEntry>()
        var group = "未分类"
        var pending: Metadata? = null
        val headers = linkedMapOf<String, String>()
        for (line in lines) {
            if (line.isBlank()) continue
            when {
                line.startsWith("#EXTINF:", true) -> {
                    headers.clear()
                    val separator = metadataSeparator(line)
                    if (separator < 0) { pending = null; continue }
                    val attrs = attribute.findAll(line.substring(0, separator)).associate {
                        it.groupValues[1].lowercase(Locale.ROOT) to (it.groupValues[2].ifEmpty { it.groupValues[3] })
                    }
                    pending = Metadata(line.substring(separator + 1).trim(), attrs["group-title"].orEmpty().ifEmpty { group },
                        attrs["tvg-id"].orEmpty(), attrs["tvg-logo"].orEmpty())
                    attrs["http-user-agent"]?.let { headers["User-Agent"] = it }
                    attrs["http-referrer"]?.let { headers["Referer"] = it }
                    attrs["http-referer"]?.let { headers["Referer"] = it }
                }
                line.startsWith("#EXTGRP:", true) -> {
                    group = line.substringAfter(':').trim().ifEmpty { "未分类" }
                    pending = pending?.copy(group = group)
                }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val option = line.substringAfter(':').substringBefore('=').lowercase(Locale.ROOT)
                    val value = line.substringAfter('=', "")
                    when (option) {
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                    }
                }
                line.startsWith('#') -> Unit
                pending != null -> {
                    val meta = pending
                    parseStream(line, sourceId, headers)?.let {
                        if (meta.name.isNotBlank()) entries += PlaylistEntry(meta.name, meta.group, it, meta.tvgId, meta.logo)
                    }
                    pending = null
                    headers.clear()
                }
                line.contains(',') -> {
                    val name = line.substringBefore(',').trim()
                    val address = line.substringAfter(',').trim()
                    if (address.equals("#genre#", true)) group = name.ifEmpty { "未分类" }
                    else if (name.isNotBlank()) {
                        // TXT sources use # between routes and $ for optional route labels.
                        address.split('#').forEach { route ->
                            parseStream(route.substringBefore('$').trim(), sourceId, emptyMap())?.let {
                                entries += PlaylistEntry(name, group, it)
                            }
                        }
                    }
                }
            }
        }
        // Some feeds return an update announcement MP4 when no television routes exist.
        return entries.filterNot { it.group.trim() == "更新时间" || it.name.trim() == "支持作者" }
    }

    private fun parseStream(value: String, sourceId: String, inherited: Map<String, String>): Stream? {
        val url = value.substringBefore('|').trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in supported || uri.host.isNullOrBlank()) return null
        val headers = inherited.toMutableMap()
        if ('|' in value) value.substringAfter('|').split('&').forEach { item ->
            if ('=' in item) {
                val key = item.substringBefore('=').lowercase(Locale.ROOT)
                val name = when (key) { "user-agent" -> "User-Agent"; "referer", "referrer" -> "Referer"; else -> null }
                val decoded = runCatching { URLDecoder.decode(item.substringAfter('='), "UTF-8") }.getOrNull()
                if (name != null && decoded != null && '\r' !in decoded && '\n' !in decoded) headers[name] = decoded
            }
        }
        return Stream(url, sourceId, headers.filterValues { '\r' !in it && '\n' !in it })
    }

    private fun metadataSeparator(line: String): Int {
        var quote: Char? = null
        line.forEachIndexed { index, c ->
            if (c == quote) quote = null
            else if (quote == null && (c == '\'' || c == '"')) quote = c
            else if (quote == null && c == ',') return index
        }
        return -1
    }

    private data class Metadata(val name: String, val group: String, val tvgId: String, val logo: String)
}
