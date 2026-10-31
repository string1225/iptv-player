package com.string.iptv.catalog

/** A tiny startup record: opening the app never waits for a playlist or catalog index. */
object InstantChannel {
    val dongfang = Channel(
        id = "东方卫视", name = "东方卫视", group = "Z · 卫视频道",
        streams = listOf(
            Stream("http://bp-resource-dfl.bestv.cn/155/3/video.m3u8", "zbds4txt"),
            Stream("http://bp-resource-dfl.bestv.cn/148/3/video.m3u8", "zbds4m3u"),
        ),
    )
}
