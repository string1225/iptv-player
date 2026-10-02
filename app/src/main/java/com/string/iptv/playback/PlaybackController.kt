package com.string.iptv.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.string.iptv.catalog.Channel
import com.string.iptv.catalog.RouteCycle
import com.string.iptv.data.PlaylistRepository
import com.string.iptv.data.Preferences
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class PlaybackStatus(val channel: Channel?, val routeIndex: Int, val message: String, val failed: Boolean = false, val playing: Boolean = false)

@UnstableApi
class PlaybackController(
    context: Context,
    private val view: PlayerView,
    private val preferences: Preferences,
    private val onStatus: (PlaybackStatus) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()
    private var player: ExoPlayer? = null
    private var routes = RouteCycle(0)
    var channel: Channel? = null
        private set
    private var paused = false
    private var lastMessage = "正在准备播放器"
    private var failed = false
    private val timeout = Runnable { recover("线路连接超时") }
    private val stablePlayback = Runnable {
        if (player?.isPlaying == true) {
            routes.recovered()
            channel?.let { c -> c.streams.getOrNull(routes.current)?.let { preferences.rememberRoute(c.id, it.url) } }
        }
    }

    fun start() {
        if (player != null) return
        player = ExoPlayer.Builder(appContext, DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 25_000, 800, 1_500).build())
            .build().also { p ->
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                p.setHandleAudioBecomingNoisy(true)
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        handler.removeCallbacks(timeout)
                        when (state) {
                            Player.STATE_IDLE -> Unit
                            Player.STATE_BUFFERING -> {
                                lastMessage = "正在连接 · 线路 ${routes.current + 1}/${channel?.streams?.size ?: 0}"
                                if (!paused) handler.postDelayed(timeout, 15_000)
                            }
                            Player.STATE_READY -> {
                                failed = false
                                lastMessage = if (paused) "已暂停" else "正在直播"
                            }
                            Player.STATE_ENDED -> { recover("线路已结束"); return }
                        }
                        publish()
                    }

                    override fun onPlayerError(error: PlaybackException) { recover("线路不可用") }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        handler.removeCallbacks(stablePlayback)
                        if (isPlaying) handler.postDelayed(stablePlayback, 10_000)
                        publish()
                    }
                })
                view.player = p
            }
        channel?.let { select(it) }
    }

    fun select(value: Channel, preferredRoute: Int? = null) {
        channel = value
        val remembered = value.streams.indexOfFirst { it.url == preferences.goodRoute(value.id) }.coerceAtLeast(0)
        routes = RouteCycle(value.streams.size, preferredRoute ?: remembered)
        paused = false
        failed = false
        playRoute()
    }

    private fun playRoute() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(stablePlayback)
        val p = player ?: return
        val route = channel?.streams?.getOrNull(routes.current) ?: return
        val http = OkHttpDataSource.Factory(httpClient).setUserAgent(PlaylistRepository.USER_AGENT)
            .setDefaultRequestProperties(route.headers)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http))
        lastMessage = "正在连接 · 线路 ${routes.current + 1}/${channel?.streams?.size}"
        failed = false
        val item = MediaItem.Builder().setUri(route.url).setMediaId("${channel?.id}:${routes.current}").build()
        p.setMediaSource(factory.createMediaSource(item))
        p.prepare()
        p.playWhenReady = !paused
        handler.removeCallbacks(timeout)
        if (!paused) handler.postDelayed(timeout, 15_000)
        publish()
    }

    private fun recover(reason: String) {
        handler.removeCallbacks(timeout)
        if (player == null || channel == null || paused || failed) return
        if (routes.next() == null) {
            failed = true
            lastMessage = "$reason，已尝试全部线路。按确定换台，菜单键重试。"
            player?.stop()
            publish()
        } else playRoute()
    }

    fun nextRoute(direction: Int = 1) {
        val count = channel?.streams?.size ?: return
        routes = RouteCycle(count, routes.current + direction)
        paused = false
        playRoute()
    }

    fun retry() { channel?.let { select(it) } }

    fun togglePause() {
        val p = player ?: return
        if (failed) { retry(); return }
        paused = !paused
        p.playWhenReady = !paused
        handler.removeCallbacks(timeout)
        if (!paused && p.playbackState == Player.STATE_BUFFERING) handler.postDelayed(timeout, 15_000)
        lastMessage = if (paused) "已暂停" else if (p.playbackState == Player.STATE_READY) "正在直播" else "正在连接"
        publish()
    }

    private fun publish() { onStatus(PlaybackStatus(channel, routes.current, lastMessage, failed, player?.isPlaying == true)) }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        view.player = null
        val oldPlayer = player
        player = null
        oldPlayer?.release()
    }
}
