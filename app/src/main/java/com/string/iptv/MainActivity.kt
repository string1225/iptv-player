package com.string.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import com.string.iptv.catalog.CatalogIndex
import com.string.iptv.catalog.ChannelNavigation
import com.string.iptv.catalog.RouteState
import com.string.iptv.catalog.StartupChannel
import com.string.iptv.data.CatalogSnapshot
import com.string.iptv.data.PlaylistRepository
import com.string.iptv.data.Preferences
import com.string.iptv.playback.PlaybackController
import com.string.iptv.playback.PlaybackStatus
import com.string.iptv.ui.ChannelDrawer
import com.string.iptv.ui.PlayerScreen
import com.string.iptv.ui.TvStyle.dp
import com.string.iptv.update.UpdateManager
import com.string.iptv.update.UpdatePhase
import com.string.iptv.update.UpdateState
import androidx.core.content.FileProvider
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
    private lateinit var updater: UpdateManager
    private var updateDialog: AlertDialog? = null
    private var pendingInstallPermission = false
    private var notifiedUpdate: String? = null
    private var catalog: CatalogSnapshot? = null
    private var latestStatus: PlaybackStatus? = null
    private var playingGroup: String? = null
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
        when {
            channel == null -> toast("没有这个频道编号")
            preferences.unavailableLabel(channel) != null -> {
                toast("该频道标记为不可用，请从列表选择重试")
                openDrawer()
            }
            else -> play(channel)
        }
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
        playback = PlaybackController(this, screen.player, preferences, ::renderPlayback, ::updateDrawer)
        drawer = ChannelDrawer(this, { channel, group -> play(channel, group) }, ::showActions, ::closeDrawer, preferences::unavailableLabel)
        screen.root.addView(drawer.root, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.76f).toInt().coerceAtMost(dp(720)), MATCH_PARENT, Gravity.START))
        setContentView(screen.root)
        screen.root.setOnClickListener { openDrawer() }
        repository = PlaylistRepository(this, ::acceptCatalog)
        updater = UpdateManager(this, preferences, ::renderUpdate)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        handler.post(clockTick)
        repository.refresh()
    }

    override fun onStart() { super.onStart(); playback.start(); updater.start() }
    override fun onResume() {
        super.onResume()
        if (pendingInstallPermission) {
            pendingInstallPermission = false
            if (packageManager.canRequestPackageInstalls()) installUpdate()
            else toast("安装权限未开启，稍后可在软件更新中重试")
        }
    }
    override fun onStop() {
        handler.removeCallbacks(hideInfo)
        handler.removeCallbacks(tuneNumber)
        digits = ""
        playback.stop()
        updater.stop()
        super.onStop()
    }
    override fun onDestroy() {
        repository.close()
        drawer.close()
        updater.close()
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
            val ready = snapshot.fromCache || snapshot.index.byId.containsKey(configured) ||
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
        drawer.update(catalog?.index ?: CatalogIndex.EMPTY, preferences.favorites, preferences.defaultId, playback.channel?.id, playingGroup)
    }

    private fun play(channel: Channel, group: String? = playingGroup) {
        playingGroup = group?.takeIf { it in channel.groups } ?: channel.group
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
            catalog?.index?.byId?.get(value.id) ?: value
        }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (channel != null) {
            items += "播放 ${channel.name}" to { play(channel, if (drawer.isOpen) drawer.groupFor(channel) else playingGroup) }
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
            playback.channel?.let { current -> play(catalog?.index?.byId?.get(current.id) ?: current) }
        }
        items += "刷新全部频道源" to { repository.refresh(); toast("正在后台刷新频道") }
        items += "频道源状态" to { showSourceStatus() }
        items += (if (updater.state.phase == UpdatePhase.READY) "软件更新 · 新版已就绪" else "软件更新") to { showUpdateDialog() }
        items += "遥控器使用说明" to { showHelp() }
        if (channel != null) items += (if (channel.id in preferences.disabledChannels) "取消不可用标记（恢复上下换台）" else "标记为不可用（上下换台跳过）") to {
            val disabled = preferences.toggleDisabled(channel)
            updateDrawer()
            toast(if (disabled) "${channel.name} 已停用，仍可从菜单播放" else "${channel.name} 已取消停用标记")
        }
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
            val health = when (preferences.routeHealth.state(stream)) {
                RouteState.UNKNOWN -> "未检测"
                RouteState.AVAILABLE -> "可用"
                RouteState.UNAVAILABLE -> "不可用（可重试）"
            }
            "线路 ${index + 1}  ·  $source  ·  $health\n$host"
        }.toTypedArray()
        val current = if (playback.channel?.id == channel.id) latestStatus?.routeIndex ?: 0 else 0
        showDialog(AlertDialog.Builder(this).setTitle("${channel.name} · 选择线路")
            .setSingleChoiceItems(choices, current) { choiceDialog, index ->
                choiceDialog.dismiss()
                dialog = null
                playingGroup = (if (drawer.isOpen) drawer.groupFor(channel) else playingGroup)?.takeIf { it in channel.groups } ?: channel.group
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
            .setMessage("打开应用：自动播放指定的默认频道；首次优先 CCTV1。\n\n↑ / ↓：当前分组上一台 / 下一台\n确定：打开列表、播放选中频道\n← / →：上一条 / 下一条线路\n菜单：默认频道、收藏、线路、停用、刷新\n列表分类列：上下连续切分类，右键进入频道\n列表第一行向上：搜索按钮，确定输入名称\n数字键：输入频道编号后自动跳转\n播放 / 暂停键：暂停或继续\n返回：关闭列表；全屏连按两次退出\n\nZ 为 zbds，I 为 iptv-org。线路失败或稳定播放 10 秒后记入本地。全部线路失败及手动停用的频道，上下换台会跳过，仍可在列表选择重试。成功播放可恢复自动判断；手动停用需在菜单取消。IPv6 线路需要网络支持 IPv6。观看仍需联网。")
            .setPositiveButton("知道了", null).create())
    }

    private fun renderUpdate(state: UpdateState) {
        updateDialog?.takeIf { it.isShowing }?.let(::renderUpdateDialog)
        if (state.phase == UpdatePhase.READY && state.release?.tag != notifiedUpdate) {
            notifiedUpdate = state.release?.tag
            toast("新版 ${state.release?.version} 已就绪，菜单 → 软件更新可安装")
        }
    }

    private fun showUpdateDialog() {
        val value = AlertDialog.Builder(this).setTitle("软件更新")
            .setMessage("").setPositiveButton("检查更新", null)
            .setNeutralButton("自动更新", null).setNegativeButton("关闭", null).create()
        updateDialog = value
        showDialog(value)
        value.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            preferences.autoUpdate = !preferences.autoUpdate
            renderUpdateDialog(value)
            if (preferences.autoUpdate) updater.check(false)
        }
        value.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when (updater.state.phase) {
                UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING -> Unit
                UpdatePhase.READY -> installUpdate()
                UpdatePhase.AVAILABLE -> updater.download()
                UpdatePhase.ERROR -> if (updater.state.release != null) updater.download() else updater.check()
                else -> updater.check()
            }
        }
        renderUpdateDialog(value)
        value.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }

    private fun renderUpdateDialog(value: AlertDialog) {
        val state = updater.state
        val time = preferences.lastUpdateCheck.takeIf { it > 0 }?.let {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))
        } ?: "尚未检查"
        value.setTitle("软件更新 · 当前 ${BuildConfig.VERSION_NAME}")
        value.setMessage("${state.message}\n\n上次检查：$time\n自动更新开启时，每 6 小时后台检查并下载；安装需系统确认。\n下载优先使用 gh-proxy.org，失败回退 GitHub。${state.release?.notes?.takeIf { it.isNotBlank() }?.let { "\n\n更新说明\n$it" }.orEmpty()}")
        value.getButton(AlertDialog.BUTTON_NEUTRAL).text = "自动更新：${if (preferences.autoUpdate) "已开启" else "已关闭"}"
        value.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            text = when (state.phase) {
                UpdatePhase.READY -> "安装新版"
                UpdatePhase.AVAILABLE -> "下载新版"
                UpdatePhase.ERROR -> "重试"
                UpdatePhase.CHECKING -> "检查中…"
                UpdatePhase.DOWNLOADING -> "下载中…"
                else -> "检查更新"
            }
            // Keep remote focus on the action while it runs; disabling it jumps focus
            // to the automatic-update switch and can accidentally toggle that setting.
            isEnabled = true
        }
    }

    private fun installUpdate() {
        val file = updater.state.file?.takeIf { it.isFile } ?: return
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                pendingInstallPermission = true
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: android.content.ActivityNotFoundException) {
            pendingInstallPermission = false
            toast("设备没有可用的安装 / 授权界面")
        } catch (_: SecurityException) {
            pendingInstallPermission = false
            toast("设备策略阻止安装，请检查系统设置")
        }
    }

    private fun showDialog(value: AlertDialog) {
        dialog?.dismiss()
        dialog = value
        value.setOnDismissListener {
            if (dialog === value) dialog = null
            if (updateDialog === value) updateDialog = null
        }
        value.show()
        value.listView?.requestFocus()
    }

    private fun changeChannel(direction: Int) {
        val index = catalog?.index ?: CatalogIndex.EMPTY
        val currentGroup = playingGroup?.takeIf { it in index.byGroup }
            ?: index.byId[playback.channel?.id]?.group ?: index.groups.firstOrNull()
        val channels = index.byGroup[currentGroup].orEmpty()
        if (channels.isEmpty()) { openDrawer(); return }
        val disabled = preferences.disabledChannels
        val next = ChannelNavigation.next(channels, playback.channel?.id, direction) {
            it.id !in disabled && !preferences.routeHealth.allUnavailable(it)
        }
        if (next != null) play(next)
        else { showInfo(); toast("当前分组没有其他可切换频道，可从菜单选台") }
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
            if (key == KeyEvent.KEYCODE_ENTER && down && currentFocus is android.widget.EditText) ignoreSelectRelease = true
            if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                if (down && event.repeatCount == 0) drawer.changePage(if (key == KeyEvent.KEYCODE_CHANNEL_UP) -1 else 1)
                return true
            }
            if (key == KeyEvent.KEYCODE_SEARCH) { if (down) drawer.focusSearch(); return true }
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
