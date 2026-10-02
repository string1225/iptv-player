package com.string.iptv.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    @Test fun m3uKeepsQuotedCommasHeadersAndIpv6() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="CCTV1.cn" group-title="News, China" http-user-agent="TV",CCTV-1 (1080p)
            #EXTVLCOPT:http-referrer=https://example.com/
            http://[2001:db8::1]:8080/live.m3u8|User-Agent=Living%20Room
        """.trimIndent()
        val result = PlaylistParser.parse("\uFEFF$text", "a").single()
        assertEquals("News, China", result.group)
        assertEquals("Living Room", result.stream.headers["User-Agent"])
        assertEquals("https://example.com/", result.stream.headers["Referer"])
        assertEquals("cctv1", ChannelIdentity.key(result.name))
    }

    @Test fun txtParsesGroupsMultipleRoutesAndQueryCommas() {
        val result = PlaylistParser.parse("央视频道,#genre#\nCCTV1,http://example.com/a?x=1,2\nCCTV5+,http://example.com/b\$线路1#https://example.com/c\$线路2", "a")
        assertEquals(3, result.size)
        assertEquals("央视频道", result.first().group)
        assertEquals("http://example.com/a?x=1,2", result.first().stream.url)
        assertEquals("cctv5+", ChannelIdentity.key(result.last().name))
    }

    @Test fun ignoresMalformedAndUnsupportedEntriesWithoutLeakingHeaders() {
        val text = "#EXTINF:-1 http-user-agent=\"One\",Bad\njavascript:alert(1)\n#EXTINF:-1,Good\nhttps://example.com/live.m3u8\nOther,ftp://example.com/a\nmissing,\n"
        val result = PlaylistParser.parse(text, "a")
        assertEquals(1, result.size)
        assertTrue(result.single().stream.headers.isEmpty())
    }

    @Test fun mergesRoutesAndKeepsChannelIdentityAcrossUrlChanges() {
        val first = PlaylistParser.parse("CCTV-1,http://example.com/a\nCCTV1HD,http://example.com/b\nCCTV10,http://example.com/c", "a")
        val duplicate = PlaylistParser.parse("CCTV1,http://example.com/a\nCCTV5+,http://example.com/d", "b")
        val merged = CatalogMerger.merge(first + duplicate)
        assertEquals(listOf("cctv1", "cctv5+", "cctv10"), merged.map { it.id })
        assertEquals(2, merged.first().streams.size)
        assertEquals("cctv1", CatalogMerger.merge(PlaylistParser.parse("CCTV1,https://example.com/new", "b")).single().id)
    }

    @Test fun defaultsOverrideCctvAndMissingDefaultFallsBack() {
        val channels = CatalogMerger.merge(PlaylistParser.parse("Other,http://example.com/a\nCCTV1,http://example.com/b", "a"))
        assertEquals("other", StartupChannel.choose(channels, "other")?.id)
        assertEquals("cctv1", StartupChannel.choose(channels, "missing")?.id)
        assertNull(StartupChannel.choose(emptyList(), "other"))
    }

    @Test fun routeCycleStopsAfterAllRoutesAndResetsAfterRecovery() {
        val cycle = RouteCycle(3, 2)
        assertEquals(2, cycle.current)
        assertEquals(0, cycle.next())
        assertEquals(1, cycle.next())
        assertNull(cycle.next())
        cycle.recovered()
        assertEquals(2, cycle.next())
        assertNull(RouteCycle(0).next())
        assertNull(RouteCycle(1).next())
    }

    @Test fun headersDistinguishRoutesWithSameUrl() {
        val entries = PlaylistParser.parse("#EXTINF:-1,A\nhttps://example.com/a|User-Agent=One\n#EXTINF:-1,A\nhttps://example.com/a|User-Agent=Two", "a")
        assertEquals(2, CatalogMerger.merge(entries).single().streams.size)
    }

    @Test fun updateAnnouncementsDoNotBecomeStartupChannels() {
        val text = "更新时间,#genre#\n2026-09-25 18:28:23,https://example.com/notice.mp4\nwww.example.com,https://example.com/notice.mp4"
        assertTrue(PlaylistParser.parse(text, "a").isEmpty())
    }
}
