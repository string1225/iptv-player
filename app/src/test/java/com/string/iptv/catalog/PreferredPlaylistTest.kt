package com.string.iptv.catalog

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class PreferredPlaylistTest {
    private fun bundledEntries(): List<PlaylistEntry> {
        val source = BuiltInSources.preferred
        val text = checkNotNull(javaClass.classLoader!!.getResourceAsStream(source.assetPath!!))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return PlaylistParser.parse(text, source.id)
    }

    @Test fun suppliedPlaylistParsesAllIpv6RoutesAndCleansOnlyGenreSuffixes() {
        val entries = bundledEntries()
        assertEquals(142, entries.size)
        assertEquals(128, entries.count { URI(it.stream.url).host.contains(':') })
        assertEquals(14, entries.count { !URI(it.stream.url).host.contains(':') })
        assertTrue(entries.none { "#genre#" in it.group })
        assertEquals("央视频道", entries.first().group)
        assertEquals("CCTV-1 综合", entries.first().name)
        assertEquals("cctv16", ChannelIdentity.key(entries.first { it.name == "CCTV-16 奥林匹克" }.name))
        assertEquals("News, China", PlaylistParser.parse(
            "#EXTINF:-1 group-title=\"News, China\",News\nhttps://example.com/news", "test").single().group)
        assertEquals("地方频道 / 上海", RegionalGroups.category(entries.first { it.group == "上海频道" }))
    }

    @Test fun bundledSourceAndSharedChannelsComeBeforeAllBackupFamilies() {
        assertEquals(BuiltInSources.preferred, BuiltInSources.all.first())
        assertTrue(PlaylistDownloads.candidates(BuiltInSources.preferred).isEmpty())
        val backups = listOf("iptvorg", "dmyernestlu", "zbds4txt").flatMap { source ->
            PlaylistParser.parse("央视频道,#genre#\nCCTV1,https://example.com/$source\nCCTV16,https://example.com/$source/16", source)
        }
        val index = CatalogIndex(CatalogMerger.merge(backups + bundledEntries()))
        assertEquals("IPv6 · 央视频道", index.groups.first())
        val cctv1 = index.byId.getValue("cctv1")
        assertEquals(listOf("preferredipv6", "zbds4txt", "dmyernestlu", "iptvorg"), cctv1.streams.map { it.sourceId })
        assertSame(cctv1, index.byGroup.getValue("IPv6 · 央视频道").first())
        assertSame(cctv1, index.byGroup.getValue("Z · 央视频道").first())
        assertEquals(4, index.byId.getValue("cctv16").streams.size)
        assertEquals(1, index.search("cctv16").size)
    }

    @Test fun preferredFeedOverridesRememberedGoodBackupUntilItFails() {
        val preferred = bundledEntries().first().stream
        val backup = Stream("https://example.com/backup", "zbds4txt")
        val untestedBackup = Stream("https://example.com/untested", "iptvorg")
        val station = Channel("cctv1", "CCTV1", "央视频道", listOf(backup, untestedBackup, preferred))
        val health = RouteHealth()
        health.record(backup, RouteState.AVAILABLE)
        assertEquals(listOf(2, 0, 1), health.order(station, backup.url))
        health.record(preferred, RouteState.UNAVAILABLE)
        assertEquals(listOf(0, 1, 2), health.order(station, preferred.url))
        health.record(preferred, RouteState.AVAILABLE)
        assertEquals(listOf(2, 0, 1), health.order(station, backup.url))
    }
}
