package com.string.iptv.catalog

import java.security.MessageDigest

object PlaylistDownloads {
    fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return String(CharArray(bytes.size * 2) { index ->
            val value = bytes[index / 2].toInt() and 0xff
            digits[if (index % 2 == 0) value ushr 4 else value and 15]
        })
    }

    fun candidates(source: PlaylistSource): List<String> = buildList {
        if (source.url.startsWith("https://raw.githubusercontent.com/")) add("https://gh-proxy.org/${source.url}")
        add(source.url)
        addAll(source.mirrors)
    }.distinct()
}
