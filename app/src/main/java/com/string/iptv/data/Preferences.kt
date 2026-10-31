package com.string.iptv.data

import android.content.Context
import com.string.iptv.catalog.Channel
import com.string.iptv.catalog.ChannelIdentity
import com.string.iptv.catalog.RouteHealth
import com.string.iptv.catalog.RouteState
import com.string.iptv.catalog.Stream
import org.json.JSONArray
import org.json.JSONObject

class Preferences(context: Context) {
    private val store = context.getSharedPreferences("tv_preferences", Context.MODE_PRIVATE)
    private val healthStore = context.getSharedPreferences("route_health", Context.MODE_PRIVATE)
    val routeHealth = RouteHealth(healthStore.all.mapNotNull { (key, value) ->
        runCatching { key to RouteState.valueOf(value as String) }.getOrNull()
    }.toMap().toMutableMap()) { key, state -> healthStore.edit().putString(key, state.name).apply() }
    init {
        // New feeds use CCTV-1 综合 aliases. Keep older defaults/favorites/disabled flags.
        val edit = store.edit()
        store.getString("default_channel", null)?.let { id ->
            val canonical = ChannelIdentity.key(id)
            if (canonical != id) edit.putString("default_channel", canonical)
        }
        listOf("favorites", "disabled_channels").forEach { key ->
            val old = store.getStringSet(key, emptySet()).orEmpty()
            val canonical = old.map(ChannelIdentity::key).toSet()
            if (canonical != old) edit.putStringSet(key, canonical)
        }
        store.all.forEach { (key, value) ->
            if (key.startsWith("route_") && value is String) {
                val canonical = "route_${ChannelIdentity.key(key.removePrefix("route_"))}"
                if (canonical != key) {
                    if (!store.contains(canonical)) edit.putString(canonical, value)
                    edit.remove(key)
                }
            }
        }
        edit.apply()
    }
    val defaultId: String? get() = store.getString("default_channel", null)
    val defaultName: String? get() = store.getString("default_name", null)
    val favorites: Set<String> get() = store.getStringSet("favorites", emptySet()).orEmpty().toSet()
    val disabledChannels: Set<String> get() = store.getStringSet("disabled_channels", emptySet()).orEmpty().toSet()
    var autoUpdate: Boolean
        get() = store.getBoolean("auto_update", true)
        set(value) { store.edit().putBoolean("auto_update", value).apply() }
    var lastUpdateCheck: Long
        get() = store.getLong("last_update_check", 0L)
        set(value) { store.edit().putLong("last_update_check", value).apply() }

    fun setDefault(channel: Channel) {
        val streams = JSONArray()
        channel.streams.forEach { stream ->
            streams.put(JSONObject().put("url", stream.url).put("source", stream.sourceId)
                .put("headers", JSONObject(stream.headers)))
        }
        val startup = JSONObject().put("id", channel.id).put("name", channel.name).put("group", channel.group)
            .put("streams", streams)
        store.edit().putString("default_channel", channel.id).putString("default_name", channel.name)
            .putString("instant_channel", startup.toString()).apply()
    }

    /** First upgrade adopts 东方卫视; later user-chosen defaults keep their own tiny route record. */
    fun instantChannel(fallback: Channel): Channel {
        val restored = runCatching {
            val json = JSONObject(store.getString("instant_channel", null) ?: return@runCatching null)
            if (json.getString("id") != defaultId) return@runCatching null
            val streams = json.getJSONArray("streams")
            val routes = (0 until streams.length()).map { index ->
                val item = streams.getJSONObject(index)
                val headers = item.getJSONObject("headers")
                Stream(item.getString("url"), item.getString("source"), headers.keys().asSequence()
                    .associateWith { headers.getString(it) })
            }.filter { runCatching { java.net.URI(it.url).scheme in setOf("http", "https") }.getOrDefault(false) }
            if (routes.isEmpty()) null else Channel(json.getString("id"), json.getString("name"), json.getString("group"), routes)
        }.getOrNull()
        return restored ?: fallback.also(::setDefault)
    }

    fun toggleFavorite(channel: Channel): Boolean {
        val updated = favorites.toMutableSet()
        val added = if (channel.id in updated) { updated.remove(channel.id); false } else { updated.add(channel.id); true }
        store.edit().putStringSet("favorites", updated).apply()
        return added
    }

    fun goodRoute(channelId: String): String? = store.getString("route_$channelId", null)
    fun rememberRoute(channelId: String, url: String) { store.edit().putString("route_$channelId", url).apply() }

    fun toggleDisabled(channel: Channel): Boolean {
        val updated = disabledChannels.toMutableSet()
        val disabled = if (channel.id in updated) { updated.remove(channel.id); false } else { updated.add(channel.id); true }
        store.edit().putStringSet("disabled_channels", updated).apply()
        return disabled
    }

    fun unavailableLabel(channel: Channel): String? = when {
        channel.id in disabledChannels -> "手动停用"
        routeHealth.allUnavailable(channel) -> "全部线路失败"
        else -> null
    }
}
