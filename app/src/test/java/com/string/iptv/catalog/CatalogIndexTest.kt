package com.string.iptv.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogIndexTest {
    private fun channel(number: Int, group: String = "测试分组") =
        Channel("station$number", "Station $number", group, listOf(Stream("https://example.com/$number", "test")))

    @Test fun twelveThousandChannelsStayInGroupsAndHundredRowPages() {
        val index = CatalogIndex((0 until 12_345).map { channel(it, if (it < 3) "央视频道" else "海外频道") })
        assertEquals(listOf("央视频道", "海外频道"), index.groups)
        assertEquals(3, ChannelPage.from(index.byGroup.getValue("央视频道"), 0).channels.size)
        val large = index.byGroup.getValue("海外频道")
        assertEquals(100, ChannelPage.from(large, 0).channels.size)
        assertEquals(124, ChannelPage.from(large, 0).pages)
        val last = ChannelPage.from(large, 999)
        assertEquals(123, last.number)
        assertEquals(42, last.channels.size)
        assertEquals("station12344", last.channels.last().id)
        assertEquals(12_345, index.numbers["station12344"])
    }

    @Test fun namesAreSearchedAcrossGroupsIgnoringCaseSpacesAndHyphens() {
        val index = CatalogIndex(listOf(channel(1, "A"), Channel("cctv1", "CCTV-1", "B", emptyList()),
            Channel("湖南卫视", "湖南卫视", "C", emptyList())))
        assertEquals("cctv1", index.search("cctv 1").single().id)
        assertEquals("湖南卫视", index.search("湖南").single().id)
        assertEquals("station1", index.search("STATION-1").single().id)
        assertTrue(index.search("不存在").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test fun favoritesUseCatalogOrderAndMissingFavoritesAreOmitted() {
        val index = CatalogIndex(listOf(channel(1), channel(2), channel(3)))
        assertEquals(listOf("station1", "station3"), index.favorites(setOf("station3", "missing", "station1")).map { it.id })
        assertEquals(1, ChannelPage.from(emptyList(), -3).pages)
    }
}
