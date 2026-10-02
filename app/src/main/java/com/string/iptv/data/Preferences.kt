package com.string.iptv.data

import android.content.Context
import com.string.iptv.catalog.Channel

class Preferences(context: Context) {
    private val store = context.getSharedPreferences("tv_preferences", Context.MODE_PRIVATE)
    val defaultId: String? get() = store.getString("default_channel", null)
    val defaultName: String? get() = store.getString("default_name", null)
    val favorites: Set<String> get() = store.getStringSet("favorites", emptySet()).orEmpty().toSet()
    var autoUpdate: Boolean
        get() = store.getBoolean("auto_update", true)
        set(value) { store.edit().putBoolean("auto_update", value).apply() }
    var lastUpdateCheck: Long
        get() = store.getLong("last_update_check", 0L)
        set(value) { store.edit().putLong("last_update_check", value).apply() }

    fun setDefault(channel: Channel) {
        store.edit().putString("default_channel", channel.id).putString("default_name", channel.name).apply()
    }

    fun toggleFavorite(channel: Channel): Boolean {
        val updated = favorites.toMutableSet()
        val added = if (channel.id in updated) { updated.remove(channel.id); false } else { updated.add(channel.id); true }
        store.edit().putStringSet("favorites", updated).apply()
        return added
    }

    fun goodRoute(channelId: String): String? = store.getString("route_$channelId", null)
    fun rememberRoute(channelId: String, url: String) { store.edit().putString("route_$channelId", url).apply() }
}
