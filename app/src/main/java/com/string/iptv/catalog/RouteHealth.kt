package com.string.iptv.catalog

enum class RouteState { UNKNOWN, AVAILABLE, UNAVAILABLE }

/** Playback observations persist until the same route succeeds or its identity changes. */
class RouteHealth(
    private val states: MutableMap<String, RouteState> = mutableMapOf(),
    private val persist: (String, RouteState) -> Unit = { _, _ -> },
) {
    fun state(stream: Stream): RouteState = states[stream.healthKey] ?: RouteState.UNKNOWN

    fun record(stream: Stream, value: RouteState) {
        if (state(stream) == value) return
        states[stream.healthKey] = value
        persist(stream.healthKey, value)
    }

    fun allUnavailable(channel: Channel): Boolean =
        channel.streams.isEmpty() || channel.streams.all { state(it) == RouteState.UNAVAILABLE }

    /** Try the preferred feed first unless failed, then good/untested backups, then failed routes. */
    fun order(channel: Channel, rememberedUrl: String?): List<Int> = channel.streams.indices.sortedWith(
        compareBy<Int> {
            val stream = channel.streams[it]
            when {
                state(stream) == RouteState.UNAVAILABLE -> 2
                stream.sourceId == BuiltInSources.preferred.id -> 0
                else -> 1
            }
        }.thenBy { when (state(channel.streams[it])) {
            RouteState.AVAILABLE -> 0
            RouteState.UNKNOWN -> 1
            RouteState.UNAVAILABLE -> 2
        } }.thenBy { if (channel.streams[it].url == rememberedUrl) 0 else 1 }.thenBy { it })
}

object ChannelNavigation {
    fun next(channels: List<Channel>, currentId: String?, direction: Int, eligible: (Channel) -> Boolean): Channel? {
        if (channels.isEmpty()) return null
        val current = channels.indexOfFirst { it.id == currentId }
        val start = if (current >= 0) current else if (direction > 0) -1 else 0
        for (step in 1..channels.size) {
            val channel = channels[Math.floorMod(start + step * direction, channels.size)]
            if (channel.id != currentId && eligible(channel)) return channel
        }
        return null
    }
}
