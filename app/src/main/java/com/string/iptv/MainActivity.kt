package com.string.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.string.iptv.catalog.Channel
import com.string.iptv.catalog.StartupChannel
import com.string.iptv.data.CatalogSnapshot
import com.string.iptv.data.PlaylistRepository
import com.string.iptv.data.Preferences
import com.string.iptv.playback.PlaybackController
import com.string.iptv.playback.PlaybackStatus
import com.string.iptv.ui.ChannelDrawer
import com.string.iptv.ui.PlayerScreen
import com.string.iptv.ui.TvStyle.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : Activity() {
    private lateinit var preferences: Preferences
    private lateinit var screen: PlayerScreen
    private lateinit var drawer: ChannelDrawer
    private lateinit var playback: PlaybackController
    private lateinit var repository: PlaylistRepository
    private var catalog: CatalogSnapshot? = null
    private var latestStatus: PlaybackStatus? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastBackAt = 0L
    private var dialog: AlertDialog? = null
    private var digits = ""
    private var selectKeyDown = false
    private var ignoreSelectRelease = false
    private val tuneNumber = Runnable {
        val number = digits.toIntOrNull()
        digits = ""
        val channel = number?.let { catalog?.channels?.getOrNull(it - 1) }
        if (channel != null) play(channel) else toast("没有这个频道编号")
    }
    private val hideInfo = Runnable {
        if (latestStatus?.playing == true && !drawer.isOpen) screen.info.visibility = View.GONE
    }
    private val clockTick = object : Runnable {
        override fun run() {
            screen.clock.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        preferences = Preferences(this)
        screen = PlayerScreen(this)
        playback = PlaybackController(this, screen.player, preferences, ::renderPlayback)
        drawer = ChannelDrawer(this, ::play, ::showActions, ::closeDrawer)
        screen.root.addView(drawer.root, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.76f).toInt().coerceAtMost(dp(720)), MATCH_PARENT, Gravity.START))
        setContentView(screen.root)
        screen.root.setOnClickListener { openDrawer() }
        repository = PlaylistRepository(this, ::acceptCatalog)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        handler.post(clockTick)
        repository.refresh()
    }

    override fun onStart() { super.onStart(); playback.start() }
    override fun onStop() {
        handler.removeCallbacks(hideInfo)
        handler.removeCallbacks(tuneNumber)
        digits = ""
        playback.stop()
        super.onStop()
    }
    override fun onDestroy() {
        repository.close()
        playback.stop()
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun acceptCatalog(snapshot: CatalogSnapshot) {
        catalog = snapshot
        updateDrawer()
        if (playback.channel == null) {
            // A saved default must get a chance to arrive from every source on a cold start.
            val configured = preferences.defaultId
            val ready = snapshot.channels.any { it.id == configured } ||
                configured == null && snapshot.channels.any { it.id == "cctv1" } || !snapshot.refreshing
            if (ready) {
                StartupChannel.choose(snapshot.channels, configured)?.let {
                    if (configured != null && it.id != configured) toast("默认频道暂未找到，先播放 ${it.name}；默认设置已保留")
                    play(it)
                }
            }
            if (playback.channel == null) screen.loading(
                if (snapshot.refreshing) "正在更新 ${snapshot.sources.count { it.loading }} 个频道源…"
                else "下载失败或源中没有频道。按菜单查看状态、重试。", true, snapshot.refreshing)
        }
    }

    private fun updateDrawer() {
        drawer.update(catalog?.channels.orEmpty(), preferences.favorites, preferences.defaultId, playback.channel?.id)
    }

    private fun play(channel: Channel) {
        closeDrawer()
        playback.select(channel)
        updateDrawer()
        showInfo()
    }

    private fun renderPlayback(status: PlaybackStatus) {
        latestStatus = status
        screen.render(status, status.channel?.id == preferences.defaultId)
        if (!status.playing) {
            handler.removeCallbacks(hideInfo)
            screen.info.visibility = View.VISIBLE
        } else showInfo()
    }

    private fun showInfo() {
        screen.info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 6_000)
    }

    private fun openDrawer() {
        updateDrawer()
        drawer.show()
        screen.info.visibility = View.GONE
        handler.removeCallbacks(hideInfo)
    }

    private fun closeDrawer() {
        drawer.hide()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(drawer.root.windowToken, 0)
        screen.root.requestFocus()
        showInfo()
    }

    private fun showActions(selected: Channel?) {
        if (dialog?.isShowing == true) return
        if (selectKeyDown) {
            ignoreSelectRelease = true
            currentFocus?.isPressed = false
        }
        val channel = (selected ?: playback.channel)?.let { value ->
            catalog?.channels?.firstOrNull { it.id == value.id } ?: value
        }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (channel != null) {
            items += "播放 ${channel.name}" to { play(channel) }
            items += (if (channel.id == preferences.defaultId) "★ 已是默认频道：${channel.name}" else "设为默认频道：${channel.name}") to {
                preferences.setDefault(channel)
                updateDrawer()
                latestStatus?.let(::renderPlayback)
                toast("下次打开将播放 ${channel.name}")
            }
            items += (if (channel.id in preferences.favorites) "取消收藏" else "添加收藏") to {
                val added = preferences.toggleFavorite(channel)
                updateDrawer()
                toast(if (added) "已收藏 ${channel.name}" else "已取消收藏")
            }
            items += "选择播放线路（${channel.streams.size} 条）" to { showRoutes(channel) }
        }
        if (playback.channel != null) items += "重试当前频道" to {
            playback.channel?.let { current -> play(catalog?.channels?.firstOrNull { it.id == current.id } ?: current) }
        }
        items += "刷新全部频道源" to { repository.refresh(); toast("正在后台刷新频道") }
        items += "频道源状态" to { showSourceStatus() }
        items += "遥控器使用说明" to { showHelp() }
        items += "退出应用" to { finish() }
        val title = "${channel?.name ?: "看电视"}  ·  默认：${preferences.defaultName ?: "CCTV1 / 首个可用频道"}"
        val nextDialog = AlertDialog.Builder(this).setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, index ->
                dialog = null
                items[index].second()
            }.setNegativeButton("返回", null).create()
        showDialog(nextDialog)
    }

    private fun showRoutes(channel: Channel) {
        val choices = channel.streams.mapIndexed { index, stream ->
            val source = catalog?.sources?.firstOrNull { it.source.id == stream.sourceId }?.source?.name ?: stream.sourceId
            val host = runCatching { java.net.URI(stream.url).host }.getOrNull().orEmpty()
            "线路 ${index + 1}  ·  $source\n$host"
        }.toTypedArray()
        val current = if (playback.channel?.id == channel.id) latestStatus?.routeIndex ?: 0 else 0
        showDialog(AlertDialog.Builder(this).setTitle("${channel.name} · 选择线路")
            .setSingleChoiceItems(choices, current) { choiceDialog, index ->
                choiceDialog.dismiss()
                dialog = null
                closeDrawer()
                playback.select(channel, index)
                updateDrawer()
            }.setNegativeButton("返回", null).create())
    }

    private fun showSourceStatus() {
        val snapshot = catalog
        val text = snapshot?.sources?.joinToString("\n\n") { status ->
            val state = when {
                status.loading -> "更新中"
                status.cached -> "使用缓存"
                status.error != null -> "失败"
                else -> "已更新"
            }
            val time = status.updatedAt?.let { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it)) }
            "${status.source.name}\n$state · ${status.entryCount} 条线路${if (time != null) " · $time" else ""}${status.error?.let { "\n$it" }.orEmpty()}"
        } ?: "正在载入频道源"
        showDialog(AlertDialog.Builder(this).setTitle("频道源状态 · ${snapshot?.channels?.size ?: 0} 个频道")
            .setMessage(text).setPositiveButton("刷新") { _, _ -> repository.refresh() }
            .setNegativeButton("返回", null).create())
    }

    private fun showHelp() {
        showDialog(AlertDialog.Builder(this).setTitle("遥控器使用说明")
            .setMessage("打开应用：自动播放指定的默认频道；首次优先 CCTV1。\n\n↑ / ↓：上一台 / 下一台\n确定：打开列表、播放选中频道\n← / →：上一条 / 下一条线路\n菜单：默认频道、收藏、线路、刷新\n列表中长按确定：设置选中频道\n数字键：输入频道编号后自动跳转\n播放 / 暂停键：暂停或继续\n返回：关闭列表；全屏连按两次退出\n\n播放失败会自动尝试同频道其他线路。IPv6 线路需要网络支持 IPv6。缓存可在源下载失败时继续选台，播放仍需要网络。")
            .setPositiveButton("知道了", null).create())
    }

    private fun showDialog(value: AlertDialog) {
        dialog?.dismiss()
        dialog = value
        value.setOnDismissListener { if (dialog === value) dialog = null }
        value.show()
        value.listView?.requestFocus()
    }

    private fun changeChannel(direction: Int) {
        val channels = catalog?.channels.orEmpty()
        if (channels.isEmpty()) { openDrawer(); return }
        val current = channels.indexOfFirst { it.id == playback.channel?.id }.coerceAtLeast(0)
        val index = (current + direction + channels.size) % channels.size
        play(channels[index])
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
        if (select && down && event.repeatCount == 0) selectKeyDown = true
        if (select && event.action == KeyEvent.ACTION_UP) {
            selectKeyDown = false
            if (ignoreSelectRelease) { ignoreSelectRelease = false; return true }
        }
        if (dialog?.isShowing == true) return super.dispatchKeyEvent(event)
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_SETTINGS) {
            if (down && event.repeatCount == 0) showActions(if (drawer.isOpen) drawer.focusedChannel else playback.channel)
            return true
        }
        if (drawer.isOpen) {
            if (select && down && event.isLongPress) {
                showActions(drawer.focusedChannel)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        when (key) {
            // Open on release so the same key-up cannot click the newly focused channel.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) openDrawer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (down) changeChannel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down) changeChannel(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down && event.repeatCount == 0) playback.nextRoute(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down && event.repeatCount == 0) playback.nextRoute(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (down && event.repeatCount == 0) playback.togglePause()
                return true
            }
            KeyEvent.KEYCODE_INFO -> { if (down) showInfo(); return true }
        }
        if (key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (down && event.repeatCount == 0) {
                digits = (digits + (key - KeyEvent.KEYCODE_0)).takeLast(5)
                toast("频道 $digits")
                handler.removeCallbacks(tuneNumber)
                handler.postDelayed(tuneNumber, 1_200)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        if (drawer.isOpen) { closeDrawer(); return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackAt < 2_000) finish()
        else { lastBackAt = now; toast("再按一次返回退出"); showInfo() }
    }

    // Android 13+ uses the registered predictive-back callback; this is for older TVs.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { handleBack() }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
