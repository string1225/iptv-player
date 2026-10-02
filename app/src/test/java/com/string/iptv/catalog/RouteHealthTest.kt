package com.string.iptv.catalog

import org.junit.Assert.*
import org.junit.Test

class RouteHealthTest {
    private val first = Stream("https://example.com/one", "zbds4txt")
    private val second = Stream("https://example.com/two", "iptvorg")
    private fun channel(id: String, streams: List<Stream> = listOf(first, second)) = Channel(id, id, "group", streams)

    @Test fun observationsSurviveReloadAndHeadersIdentifyDifferentRoutes() {
        val disk = mutableMapOf<String, RouteState>()
        val health = RouteHealth(persist = { key, value -> disk[key] = value })
        health.record(first, RouteState.UNAVAILABLE)
        val reloaded = RouteHealth(disk.toMutableMap())
        assertEquals(RouteState.UNAVAILABLE, reloaded.state(first.copy(sourceId = "zbds4m3u")))
        assertEquals(RouteState.UNKNOWN, reloaded.state(first.copy(headers = mapOf("User-Agent" to "TV"))))
        assertEquals(RouteState.UNKNOWN, reloaded.state(first.copy(url = "https://example.com/new")))
        val headers = mapOf("User-Agent" to "TV", "Referer" to "https://example.com/")
        assertEquals(first.copy(headers = headers).healthKey, first.copy(headers = headers.toList().reversed().toMap()).healthKey)
    }

    @Test fun unknownRoutesPreventExclusionAndPlaybackRestoresFailedChannels() {
        val health = RouteHealth()
        val station = channel("station")
        health.record(first, RouteState.UNAVAILABLE)
        assertFalse(health.allUnavailable(station))
        health.record(second, RouteState.UNAVAILABLE)
        assertTrue(health.allUnavailable(station))
        health.record(second, RouteState.AVAILABLE)
        assertFalse(health.allUnavailable(station))
        assertTrue(health.allUnavailable(channel("empty", emptyList())))
    }

    @Test fun knownGoodThenUnknownThenFailedRoutesAreAllRetriedOnce() {
        val third = Stream("https://example.com/three", "zbds4txt")
        val health = RouteHealth()
        health.record(first, RouteState.UNAVAILABLE)
        health.record(third, RouteState.AVAILABLE)
        val cycle = RouteCycle(health.order(channel("station", listOf(first, second, third)), first.url))
        assertEquals(2, cycle.current)
        assertEquals(1, cycle.next())
        assertEquals(0, cycle.next())
        assertNull(cycle.next())
    }

    @Test fun arrowsSkipAllFailedAndManuallyDisabledChannelsInBothDirections() {
        val health = RouteHealth()
        val stations = listOf(channel("a", listOf(second)), channel("b", listOf(first)), channel("c"), channel("d"))
        health.record(first, RouteState.UNAVAILABLE)
        val disabled = setOf("c")
        val eligible: (Channel) -> Boolean = { it.id !in disabled && !health.allUnavailable(it) }
        assertEquals("d", ChannelNavigation.next(stations, "a", 1, eligible)?.id)
        assertEquals("a", ChannelNavigation.next(stations, "d", -1, eligible)?.id)
        assertEquals("a", ChannelNavigation.next(stations, "d", 1, eligible)?.id)
        assertNull(ChannelNavigation.next(stations, "a", 1) { it.id == "a" })
        assertNull(ChannelNavigation.next(emptyList(), "a", -1, eligible))
        assertEquals("a", ChannelNavigation.next(stations, "missing", 1, eligible)?.id)
    }
}
