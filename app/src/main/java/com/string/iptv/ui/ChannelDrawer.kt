package com.string.iptv.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.string.iptv.catalog.CatalogIndex
import com.string.iptv.catalog.Channel
import com.string.iptv.catalog.ChannelPage
import com.string.iptv.ui.TvStyle.dp
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var generation = 0
    private var closed = false
    private val count = TvStyle.text(context, "正在获取频道", 13f, TvStyle.muted)
    private val search = EditText(context).apply {
        hint = "按名称搜索所有分组（例如 CCTV1、湖南）"
        textSize = 15f
        setTextColor(Color.WHITE)
        setHintTextColor(TvStyle.muted)
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
        background = TvStyle.shape(Color.rgb(30, 42, 55), 12f)
        id = View.generateViewId()
    }
    private val groupsView = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); id = View.generateViewId(); itemAnimator = null }
    private val channelsView = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); id = View.generateViewId(); itemAnimator = null }
    private val empty = TvStyle.text(context, "暂无频道，请在菜单中刷新", 16f, TvStyle.muted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val pageLabel = TvStyle.text(context, "第 1 / 1 页", 13f, TvStyle.muted).apply { gravity = Gravity.CENTER }
    private val previous = TvStyle.button(context, "上一页") { changePage(-1) }
    private val next = TvStyle.button(context, "下一页") { changePage(1) }
    private var index = CatalogIndex.EMPTY
    private var results = emptyList<Channel>()
    private var page = ChannelPage.from(emptyList(), 0)
    private var groups = emptyList<String>()
    private var group: String? = null
    private var favoriteIds = emptySet<String>()
    private var defaultId: String? = null
    private var playingId: String? = null
    private var dirty = true
    private var loading = false
    private var focusAfterLoad = false
    var focusedChannel: Channel? = null
        private set
    private val groupAdapter = GroupsAdapter()
    private val channelAdapter = ChannelsAdapter()
    private val searchLater = Runnable { filter(focusAfter = false) }

    init {
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TvStyle.text(context, "看电视", 28f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        heading.addView(TvStyle.button(context, "搜索", ::focusSearch))
        heading.addView(TvStyle.button(context, "菜单") { onActions(focusedChannel ?: index.byId[playingId]) })
        heading.addView(TvStyle.button(context, "关闭", onClose))
        root.addView(heading)
        root.addView(count, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(4); bottomMargin = context.dp(12) })
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(42)))
        val content = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        content.addView(groupsView, LinearLayout.LayoutParams(context.dp(140), MATCH_PARENT))
        content.addView(channelsView, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = context.dp(16) })
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(12) })
        root.addView(empty, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val pager = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        pager.addView(previous)
        pager.addView(pageLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pager.addView(next)
        root.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TvStyle.text(context, "确定 播放   ·   菜单 设置默认   ·   频道 +/- 翻页   ·   返回 关闭", 12f, TvStyle.muted))
        groupsView.adapter = groupAdapter
        channelsView.adapter = channelAdapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                invalidateSearch()
                dirty = true
                main.postDelayed(searchLater, 250)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        search.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
                filter(focusAfter = true)
                true
            } else false
        }
    }

    fun update(catalog: CatalogIndex, favorites: Set<String>, default: String?, playing: String?) {
        val changed = index !== catalog || favoriteIds != favorites
        index = catalog
        favoriteIds = favorites
        defaultId = default
        playingId = playing
        if (changed) {
            groups = catalog.groups + FAVORITES
            if (group !in groups) group = catalog.byId[playing]?.group ?: catalog.groups.firstOrNull() ?: FAVORITES
            focusedChannel = focusedChannel?.let { catalog.byId[it.id] }
            dirty = true
            if (isOpen) { groupAdapter.notifyDataSetChanged(); filter() }
        } else if (isOpen) channelAdapter.notifyDataSetChanged() // At most 100 rows, only markers change.
    }

    private fun invalidateSearch() {
        generation++
        task?.cancel(true)
        main.removeCallbacks(searchLater)
    }

    private fun filter(focusAfter: Boolean = false, target: String? = null) {
        invalidateSearch()
        if (!isOpen || closed) return
        dirty = false
        loading = true
        focusAfterLoad = focusAfter
        val revision = generation
        val catalog = index
        val favorites = favoriteIds
        val category = group
        val query = search.text.toString().trim()
        val keep = target ?: focusedChannel?.id
        val oldPage = if (query.isEmpty()) page.number else 0
        count.text = "${catalog.channels.size} 个频道 · 正在查询…"
        task = worker.submit {
            val filtered = if (query.isNotEmpty()) catalog.search(query)
                else if (category == FAVORITES) catalog.favorites(favorites)
                else catalog.byGroup[category].orEmpty()
            val position = filtered.indexOfFirst { it.id == keep }
            val requested = if (position >= 0) position / ChannelPage.SIZE else oldPage
            val resultPage = ChannelPage.from(filtered, requested)
            main.post {
                if (closed || revision != generation || !isOpen) return@post
                val hadChannelFocus = channelsView.hasFocus()
                loading = false
                results = filtered
                page = resultPage
                renderPage(query)
                if (focusAfterLoad || hadChannelFocus) focusChannel((position % ChannelPage.SIZE).coerceAtLeast(0))
                focusAfterLoad = false
            }
        }
    }

    private fun renderPage(query: String = search.text.toString().trim()) {
        focusedChannel = null
        channelAdapter.notifyDataSetChanged()
        count.text = "${index.channels.size} 个频道  ·  ${if (query.isEmpty()) "当前分类" else "名称搜索"} ${page.total} 个  ·  ★ 默认频道"
        pageLabel.text = "第 ${page.number + 1} / ${page.pages} 页 · 每页最多 ${ChannelPage.SIZE} 台"
        previous.isEnabled = page.number > 0
        next.isEnabled = page.number + 1 < page.pages
        previous.alpha = if (previous.isEnabled) 1f else 0.35f
        next.alpha = if (next.isEnabled) 1f else 0.35f
        empty.visibility = if (page.total == 0) View.VISIBLE else View.GONE
        empty.text = if (query.isNotEmpty()) "未找到匹配的频道" else if (group == FAVORITES) "暂无收藏，长按频道添加收藏" else "暂无频道，请在菜单中刷新"
    }

    fun changePage(direction: Int) {
        val number = page.number + direction
        if (number !in 0 until page.pages) return
        page = ChannelPage.from(results, number)
        renderPage()
        channelsView.scrollToPosition(0)
        focusChannel()
    }

    fun show() {
        root.visibility = View.VISIBLE
        // First open follows the playing channel's group, never an unbounded global list.
        if (results.isEmpty() && search.text.isEmpty()) group = index.byId[playingId]?.group ?: group
        groupAdapter.notifyDataSetChanged()
        if (dirty || results.none { it.id == playingId }) filter(focusAfter = true, target = playingId)
        else {
            page = ChannelPage.from(results, results.indexOfFirst { it.id == playingId }.coerceAtLeast(0) / ChannelPage.SIZE)
            renderPage()
            focusChannel(page.channels.indexOfFirst { it.id == playingId }.coerceAtLeast(0))
        }
    }

    fun focusSearch() {
        search.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun focusChannel(index: Int = 0) {
        if (loading) { focusAfterLoad = true; return }
        val position = index.coerceIn(0, (page.channels.size - 1).coerceAtLeast(0))
        channelsView.scrollToPosition(position)
        channelsView.post {
            val holder = channelsView.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                if (holder.itemView.requestFocus()) focusedChannel = page.channels.getOrNull(position)
            }
            else if (page.channels.isEmpty()) search.requestFocus()
            else channelsView.post {
                if (channelsView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() == true)
                    focusedChannel = page.channels.getOrNull(position)
            }
        }
    }

    fun hide() { root.visibility = View.GONE; invalidateSearch(); dirty = true }
    fun close() { closed = true; invalidateSearch(); worker.shutdownNow(); main.removeCallbacksAndMessages(null) }
    val isOpen get() = root.visibility == View.VISIBLE

    private inner class GroupsAdapter : RecyclerView.Adapter<TextHolder>() {
        override fun getItemCount() = groups.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TextHolder(TvStyle.text(context, "", 15f).apply {
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
            holder.text.text = if (value == FAVORITES) "我的收藏" else value
            holder.text.isSelected = group == value
            fun choose() {
                if (group != value || search.text.isNotEmpty()) {
                    group = value
                    search.setText("")
                    focusedChannel = null
                    page = ChannelPage.from(emptyList(), 0)
                    filter()
                    groupsView.post { notifyDataSetChanged() }
                }
            }
            holder.text.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) choose() }
            holder.text.setOnClickListener { choose(); focusChannel() }
        }
    }

    private inner class ChannelsAdapter : RecyclerView.Adapter<TextHolder>() {
        override fun getItemCount() = page.channels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TextHolder(TvStyle.text(context, "", 17f).apply {
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, context.dp(52)).apply { bottomMargin = context.dp(6) }
            setPadding(context.dp(16), 0, context.dp(12), 0)
            background = TvStyle.focusBackground()
            setTextColor(TvStyle.focusText())
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true; isLongClickable = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    val position = groups.indexOf(group).coerceAtLeast(0)
                    val holder = groupsView.findViewHolderForAdapterPosition(position)
                    if (holder != null) holder.itemView.requestFocus()
                    else {
                        groupsView.scrollToPosition(position)
                        groupsView.post { groupsView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
                    }
                    true
                } else false
            }
        })
        override fun onBindViewHolder(holder: TextHolder, position: Int) {
            val channel = page.channels[position]
            holder.text.text = "${index.numbers[channel.id].toString().padStart(3, '0')}   ${channel.name}${if (channel.id == defaultId) "  ★" else ""}${if (channel.id in favoriteIds) "  ♥" else ""}"
            holder.text.isSelected = channel.id == playingId
            if (holder.text.hasFocus() && !loading) focusedChannel = channel
            holder.text.contentDescription = "${channel.name}，${channel.streams.size} 条线路${if (channel.id == defaultId) "，默认频道" else ""}"
            holder.text.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !loading) focusedChannel = channel }
            holder.text.setOnClickListener { if (!loading) onSelect(channel) }
            holder.text.setOnLongClickListener { if (!loading) onActions(channel); true }
        }
    }

    private class TextHolder(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)
    companion object { private const val FAVORITES = "\u0000favorites" }
}
