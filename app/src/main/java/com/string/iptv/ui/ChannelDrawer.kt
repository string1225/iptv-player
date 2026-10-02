package com.string.iptv.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.text.Editable
import android.text.TextWatcher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.string.iptv.catalog.Channel
import com.string.iptv.ui.TvStyle.dp

class ChannelDrawer(
    private val context: Context,
    private val onSelect: (Channel) -> Unit,
    private val onActions: (Channel?) -> Unit,
    private val onClose: () -> Unit,
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(26), context.dp(22), context.dp(26), context.dp(20))
        setBackgroundColor(0xF8101923.toInt())
        visibility = View.GONE
        elevation = context.dp(8).toFloat()
    }
    private val count = TvStyle.text(context, "正在获取频道", 13f, TvStyle.muted)
    private val search = EditText(context).apply {
        hint = "搜索频道"
        textSize = 15f
        setTextColor(Color.WHITE)
        setHintTextColor(TvStyle.muted)
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
        background = TvStyle.shape(Color.rgb(30, 42, 55), 12f)
        id = View.generateViewId()
    }
    private val groupsView = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); id = View.generateViewId() }
    private val channelsView = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); id = View.generateViewId() }
    private val empty = TvStyle.text(context, "暂无频道，请在菜单中刷新", 16f, TvStyle.muted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private var all = emptyList<Channel>()
    private var channelNumbers = emptyMap<String, Int>()
    private var visibleChannels = emptyList<Channel>()
    private var groups = listOf("全部频道", "我的收藏")
    private var group = "全部频道"
    private var favoriteIds = emptySet<String>()
    private var defaultId: String? = null
    private var playingId: String? = null
    var focusedChannel: Channel? = null
        private set
    private val groupAdapter = GroupsAdapter()
    private val channelAdapter = ChannelsAdapter()

    init {
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TvStyle.text(context, "看电视", 28f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        heading.addView(TvStyle.button(context, "菜单", { onActions(focusedChannel ?: all.firstOrNull { it.id == playingId }) }))
        heading.addView(TvStyle.button(context, "关闭", onClose))
        root.addView(heading)
        root.addView(count, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(4); bottomMargin = context.dp(16) })
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(42)))
        val content = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        content.addView(groupsView, LinearLayout.LayoutParams(context.dp(140), MATCH_PARENT))
        content.addView(channelsView, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = context.dp(16) })
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(16) })
        root.addView(empty, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TvStyle.text(context, "确定 播放   ·   长按确定 / 菜单 设置默认频道   ·   返回 关闭", 12f, TvStyle.muted),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(14) })
        groupsView.adapter = groupAdapter
        channelsView.adapter = channelAdapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    fun update(channels: List<Channel>, favorites: Set<String>, default: String?, playing: String?) {
        all = channels
        channelNumbers = channels.mapIndexed { index, channel -> channel.id to index + 1 }.toMap()
        favoriteIds = favorites
        defaultId = default
        playingId = playing
        groups = listOf("全部频道", "我的收藏") + channels.map { it.group }.distinct().filter { it !in setOf("全部频道", "我的收藏") }
        if (group !in groups) group = "全部频道"
        groupAdapter.notifyDataSetChanged()
        filter()
    }

    private fun filter() {
        val query = search.text.toString().trim()
        val normalizedQuery = query.replace("-", "")
        visibleChannels = all.filter {
            (group == "全部频道" || group == "我的收藏" && it.id in favoriteIds || it.group == group) &&
                (query.isBlank() || it.name.contains(query, true) || it.id.contains(normalizedQuery, true))
        }
        if (visibleChannels.none { it.id == focusedChannel?.id }) focusedChannel = null
        channelAdapter.notifyDataSetChanged()
        count.text = "${all.size} 个频道  ·  当前分类 ${visibleChannels.size} 个  ·  ★ 默认频道"
        empty.visibility = if (visibleChannels.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (group == "我的收藏") "暂无收藏，长按频道添加收藏" else if (search.text.isNotEmpty()) "未找到匹配的频道" else "暂无频道，请在菜单中刷新"
    }

    fun show() {
        root.visibility = View.VISIBLE
        val index = visibleChannels.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
        channelsView.scrollToPosition(index)
        focusChannel(index)
    }

    private fun focusChannel(index: Int = 0) {
        channelsView.post {
            val holder = channelsView.findViewHolderForAdapterPosition(index)
            if (holder != null) holder.itemView.requestFocus()
            else if (visibleChannels.isNotEmpty()) { channelsView.scrollToPosition(index); channelsView.post { channelsView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() } }
            else search.requestFocus()
        }
    }

    fun hide() { root.visibility = View.GONE }
    val isOpen get() = root.visibility == View.VISIBLE

    private inner class GroupsAdapter : RecyclerView.Adapter<TextHolder>() {
        override fun getItemCount() = groups.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextHolder = TextHolder(TvStyle.text(context, "", 15f).apply {
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, context.dp(46)).apply { bottomMargin = context.dp(6) }
            setPadding(context.dp(12), 0, context.dp(8), 0)
            background = TvStyle.focusBackground()
            setTextColor(TvStyle.focusText())
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) { focusChannel(); true } else false
            }
        })
        override fun onBindViewHolder(holder: TextHolder, position: Int) {
            val value = groups[position]
            holder.text.text = value
            holder.text.isSelected = group == value
            fun choose() {
                if (group != value) { group = value; filter(); groupsView.post { notifyDataSetChanged() } }
            }
            holder.text.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) choose() }
            holder.text.setOnClickListener { choose(); focusChannel() }
        }
    }

    private inner class ChannelsAdapter : RecyclerView.Adapter<TextHolder>() {
        override fun getItemCount() = visibleChannels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextHolder = TextHolder(TvStyle.text(context, "", 17f).apply {
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, context.dp(52)).apply { bottomMargin = context.dp(6) }
            setPadding(context.dp(16), 0, context.dp(12), 0)
            background = TvStyle.focusBackground()
            setTextColor(TvStyle.focusText())
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true; isLongClickable = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    val index = groups.indexOf(group).coerceAtLeast(0)
                    groupsView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() ?: groupsView.requestFocus()
                    true
                } else false
            }
        })
        override fun onBindViewHolder(holder: TextHolder, position: Int) {
            val channel = visibleChannels[position]
            holder.text.text = "${channelNumbers[channel.id].toString().padStart(3, '0')}   ${channel.name}${if (channel.id == defaultId) "  ★" else ""}${if (channel.id in favoriteIds) "  ♥" else ""}"
            holder.text.isSelected = channel.id == playingId
            holder.text.contentDescription = "${channel.name}，${channel.streams.size} 条线路${if (channel.id == defaultId) "，默认频道" else ""}"
            holder.text.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focusedChannel = channel }
            holder.text.setOnClickListener { onSelect(channel) }
            holder.text.setOnLongClickListener { onActions(channel); true }
        }
    }

    private class TextHolder(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)
}
