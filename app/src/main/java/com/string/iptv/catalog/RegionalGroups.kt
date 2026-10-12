package com.string.iptv.catalog

/** Province-level menus; ambiguous county names stay in Other instead of being guessed. */
object RegionalGroups {
    const val LOCAL = "地方频道"
    const val OTHER = "其他地区"
    private const val SEPARATOR = " / "
    val regions = RegionNames.aliases.keys.toList() + OTHER
    private val provinces = RegionNames.aliases.keys + listOf("内蒙", "澳門", "臺灣", "台灣")
    private val special = mapOf(
        "中国蓝" to "浙江", "钱江" to "浙江", "第一财经" to "上海", "东方财经" to "上海",
        "东方影视" to "上海", "五星体育" to "上海", "南国都市" to "广东", "珠江" to "广东",
        "嘉佳" to "广东", "快乐垂钓" to "湖南", "金鹰" to "湖南", "BTV" to "北京", "BRTV" to "北京",
        "延边" to "吉林", "西双版纳" to "云南", "黔东南" to "贵州", "黔西南" to "贵州",
        "伊犁" to "新疆", "博尔塔拉" to "新疆", "巴音郭楞" to "新疆", "克孜勒苏" to "新疆",
        "兵团" to "新疆",
        "西安" to "陕西",
    )
    private val aliases = buildMap<String, String> {
        RegionNames.aliases.forEach { (region, names) -> names.forEach { put(it, region) } }
        RegionNames.aliases.keys.forEach { put(it, it) }
        putAll(special)
        mapOf("内蒙" to "内蒙古", "澳門" to "澳门", "台灣" to "台湾", "臺灣" to "台湾").forEach { (name, region) ->
            put(name, region)
        }
    }.entries.groupBy { it.key.first().uppercaseChar() }.mapValues { (_, values) -> values.sortedByDescending { it.key.length } }

    fun region(name: String): String? {
        val value = name.trim().dropWhile { !it.isLetterOrDigit() }
        val initial = value.firstOrNull()?.uppercaseChar() ?: return null
        return aliases[initial].orEmpty().firstOrNull { value.startsWith(it.key, ignoreCase = true) }?.value
    }

    /** Explicit province metadata wins; CCTV/satellite groups remain ordinary top-level groups. */
    fun category(entry: PlaylistEntry): String {
        val original = entry.group.trim()
        if (original.contains("央视") || original.contains("卫视")) return original
        val explicit = provinces.filter { original.contains(it) }.mapNotNull(::region).distinct().singleOrNull()
        val local = listOf("地方", "地面", "本地").any(original::contains)
        val inferred = if (local || original == "未分类") region(entry.name) else null
        if (explicit != null || local || inferred != null) return "$LOCAL$SEPARATOR${explicit ?: inferred ?: OTHER}"
        return original
    }

    fun parent(group: String): String = if ("$LOCAL$SEPARATOR" in group) group.substringBefore(SEPARATOR) else group
    fun child(group: String): String = group.substringAfter(SEPARATOR, "")
    fun rank(group: String): Int = regions.indexOf(child(group)).takeIf { it >= 0 } ?: Int.MAX_VALUE
}
