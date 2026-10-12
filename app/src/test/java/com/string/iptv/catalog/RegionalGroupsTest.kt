package com.string.iptv.catalog

import org.junit.Assert.*
import org.junit.Test

class RegionalGroupsTest {
    private fun entry(name: String, group: String = "地方频道", source: String = "zbds4txt") =
        PlaylistEntry(name, group, Stream("https://example.com/${name.hashCode()}", source))

    @Test fun localCitiesAndCountiesMapToTheirProvinceAndUnknownNamesStayVisible() {
        listOf("七台河新闻综合" to "黑龙江", "张家港新闻" to "江苏", "余姚新闻综合" to "浙江",
            "上虞文化影院" to "浙江", "东丰综合" to "吉林", "东莞新闻综合" to "广东",
            "仁寿综合" to "四川", "任丘综合" to "河北", "伊犁哈萨克" to "新疆",
            "天祝综合" to "甘肃", "石柱新闻" to "重庆", "清原新闻" to "辽宁", "兵团五师双河新闻" to "新疆",
            "西安新闻" to "陕西",
            "第一财经" to "上海", "中国蓝新闻" to "浙江").forEach { (name, region) ->
            assertEquals("地方频道 / $region", RegionalGroups.category(entry(name)))
        }
        assertEquals("地方频道 / 其他地区", RegionalGroups.category(entry("测试地方台")))
        assertEquals("地方频道 / 其他地区", RegionalGroups.category(entry("鼓楼新闻")))
    }

    @Test fun explicitRegionsWinAndCctvSatelliteAndForeignCategoriesStayUnchanged() {
        assertEquals("地方频道 / 江苏", RegionalGroups.category(entry("新闻综合", "江苏频道")))
        assertEquals("地方频道 / 浙江", RegionalGroups.category(entry("北京新闻", "浙江地面")))
        assertEquals("地方频道 / 河北", RegionalGroups.category(entry("河北新闻")))
        assertEquals("地方频道 / 海南", RegionalGroups.category(entry("海南新闻")))
        assertEquals("卫视频道", RegionalGroups.category(entry("湖南卫视", "卫视频道")))
        assertEquals("央视频道", RegionalGroups.category(entry("CCTV1", "央视频道")))
        assertEquals("News", RegionalGroups.category(entry("Local News", "News", "iptvorg")))
        assertEquals("地方频道 / 台湾", RegionalGroups.category(entry("臺北新聞", "地方频道")))
    }

    @Test fun twoLevelMenusKeepSourcesSeparateAndRetainChannelIdentityRoutesAndGlobalSearch() {
        val index = CatalogIndex(CatalogMerger.merge(listOf(entry("广州新闻"), entry("余姚新闻"),
            entry("北京新闻"), entry("地方测试"), entry("广州新闻", "广东", "dzbefine"),
            entry("广州新闻", "地方频道", "iptvorg"))))
        assertEquals(listOf("Z · 地方频道", "D · 地方频道", "I · 地方频道"), index.menuGroups)
        assertEquals(listOf("Z · 地方频道 / 北京", "Z · 地方频道 / 浙江", "Z · 地方频道 / 广东", "Z · 地方频道 / 其他地区"),
            index.childrenOf("Z · 地方频道"))
        assertEquals("Z · 地方频道 / 北京", index.firstLeaf("Z · 地方频道"))
        assertEquals("广州新闻", index.search("广州").single().id)
        assertEquals(3, index.byId.getValue("广州新闻").groups.size)
        assertEquals(1, index.byId.getValue("广州新闻").streams.size) // Same URL appears in all three feeds.
        assertEquals("广州新闻", StartupChannel.choose(index.channels, "广州新闻")?.id)
        assertTrue(index.childrenOf("Z · 央视频道").isEmpty())
    }

    @Test fun descriptiveCctvNamesMergeWithoutCollapsingDifferentInternationalEditions() {
        assertEquals("cctv1", ChannelIdentity.key("CCTV-1 综合"))
        assertEquals("cctv5+", ChannelIdentity.key("CCTV-5+ 体育赛事 HD"))
        assertEquals("cctv17", ChannelIdentity.key("CCTV17农业农村"))
        assertNotEquals("cctv4", ChannelIdentity.key("CCTV4欧洲"))
    }

    @Test fun githubDownloadsTryAcceleratorOriginalThenDeduplicatedMirror() {
        val url = "https://raw.githubusercontent.com/owner/repo/main/tv.m3u"
        assertEquals(listOf("https://gh-proxy.org/$url", url, "https://example.com/tv.m3u"),
            PlaylistDownloads.candidates(PlaylistSource("test", "test", url, listOf(url, "https://example.com/tv.m3u"))))
        assertEquals(listOf("https://example.com/tv.m3u"), PlaylistDownloads.candidates(
            PlaylistSource("test", "test", "https://example.com/tv.m3u")))
    }

    @Test fun refreshReusesUnchangedChannelsAndReplacesOnlyUpdatedRoutes() {
        val entries = listOf(entry("北京新闻"), entry("广州新闻"))
        val old = CatalogIndex(CatalogMerger.merge(entries))
        val same = CatalogMerger.merge(entries, old.byId)
        assertSame(old.byId["北京新闻"], same.first())
        assertSame(old.byId["广州新闻"], same.last())
        val changed = CatalogMerger.merge(listOf(entries.first(), entries.last().copy(stream = Stream("https://example.com/new", "zbds4txt"))), old.byId)
        assertSame(old.byId["北京新闻"], changed.first())
        assertNotSame(old.byId["广州新闻"], changed.last())
        assertEquals("https://example.com/new", changed.last().streams.single().url)
    }
}
