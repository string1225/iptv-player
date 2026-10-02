package com.string.iptv.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.string.iptv.playback.PlaybackStatus
import com.string.iptv.ui.TvStyle.dp

@UnstableApi
class PlayerScreen(context: Context) {
    val root = FrameLayout(context).apply { setBackgroundColor(TvStyle.background); isFocusable = true; isFocusableInTouchMode = true }
    val player = PlayerView(context).apply {
        useController = false
        isFocusable = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        setKeepContentOnPlayerReset(false)
    }
    private val center = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(32), context.dp(24), context.dp(32), context.dp(24))
        background = TvStyle.shape(0xE6101923.toInt(), 16f)
    }
    private val centerTitle = TvStyle.text(context, "正在载入频道", 24f, bold = true)
    private val centerDescription = TvStyle.text(context, "首次使用需要下载频道列表", 15f, TvStyle.muted)
    val info = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(26), context.dp(18), context.dp(26), context.dp(18))
        background = TvStyle.shape(0xEC101923.toInt(), 16f)
    }
    private val eyebrow = TvStyle.text(context, "看电视  /  LIVE", 13f, TvStyle.accent, true)
    private val title = TvStyle.text(context, "准备就绪", 28f, bold = true)
    private val detail = TvStyle.text(context, "", 14f, TvStyle.muted)
    private val help = TvStyle.text(context, "↑↓ 换台     确定 频道列表     ←→ 切换线路     菜单 设置", 13f, TvStyle.muted)
    val clock = TvStyle.text(context, "", 16f, Color.WHITE, true)

    init {
        root.addView(player, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        center.addView(centerTitle)
        center.addView(centerDescription, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.dp(12) })
        root.addView(center, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        listOf(eyebrow, title, detail, help).forEachIndexed { index, view ->
            info.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (index > 0) topMargin = context.dp(6) })
        }
        root.addView(info, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = context.dp(32); rightMargin = context.dp(32); bottomMargin = context.dp(26)
        })
        root.addView(clock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = context.dp(28); rightMargin = context.dp(34)
        })
    }

    fun loading(message: String, empty: Boolean, refreshing: Boolean = false) {
        center.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            centerTitle.text = if (refreshing) "正在载入频道" else "暂无可用频道"
            centerDescription.text = message
            title.text = "看电视"
            detail.text = message
            help.text = "确定 查看频道     菜单 刷新频道 / 查看源状态"
        }
    }

    fun render(status: PlaybackStatus, isDefault: Boolean) {
        val channel = status.channel ?: return
        title.text = channel.name
        eyebrow.text = "看电视  /  ${channel.group}${if (isDefault) "  ·  默认频道" else ""}"
        detail.text = if (status.message.contains("线路 ${status.routeIndex + 1}/")) status.message
            else "${status.message}  ·  线路 ${status.routeIndex + 1}/${channel.streams.size}"
        center.visibility = if (status.playing) View.GONE else View.VISIBLE
        centerTitle.text = if (status.failed) "暂时无法播放" else if (status.message == "已暂停") "已暂停" else "正在连接 ${channel.name}"
        centerDescription.text = if (status.failed) "确定 换台  ·  菜单 重试或选择线路" else status.message
        help.text = "↑↓ 换台     确定 频道列表     ←→ 切换线路     菜单 设置"
    }
}
