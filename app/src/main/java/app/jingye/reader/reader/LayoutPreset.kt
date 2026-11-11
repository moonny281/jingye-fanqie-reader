package app.jingye.reader.reader

enum class LayoutPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val textSizeSp: Int,
    val lineSpacingX10: Int,
    val paraSpacingDp: Int,
    val letterSpacingX100: Int,
    val marginHdp: Int,
    val marginVdp: Int,
    val bold: Boolean,
    val defaultFontId: String
) {
    WEREAD_STD(
        id = "weread",
        title = "微信精排",
        subtitle = "均衡行距与段距，复刻微信读书墨水屏标准观感",
        textSizeSp = 20,
        lineSpacingX10 = 15,
        paraSpacingDp = 10,
        letterSpacingX100 = 2,
        marginHdp = 24,
        marginVdp = 18,
        bold = false,
        defaultFontId = "serif"
    ),
    COMPACT_FAST(
        id = "compact",
        title = "紧凑速读",
        subtitle = "窄边距、密行距，单屏容纳更多字数，减少翻页",
        textSizeSp = 18,
        lineSpacingX10 = 13,
        paraSpacingDp = 6,
        letterSpacingX100 = 0,
        marginHdp = 16,
        marginVdp = 12,
        bold = false,
        defaultFontId = "sans"
    ),
    COMFORT_LARGE(
        id = "comfort",
        title = "舒朗大字",
        subtitle = "大字号、宽行距，关闭前光也能轻松阅读不费眼",
        textSizeSp = 24,
        lineSpacingX10 = 16,
        paraSpacingDp = 14,
        letterSpacingX100 = 3,
        marginHdp = 26,
        marginVdp = 22,
        bold = false,
        defaultFontId = "wenkai"
    ),
    CLASSIC_BOOK(
        id = "classic",
        title = "古籍雅致",
        subtitle = "阔留白、疏字距，模拟传统纸质线装书呼吸感",
        textSizeSp = 21,
        lineSpacingX10 = 17,
        paraSpacingDp = 16,
        letterSpacingX100 = 5,
        marginHdp = 32,
        marginVdp = 26,
        bold = false,
        defaultFontId = "fangsong"
    ),
    EINK_CRISP(
        id = "crisp",
        title = "高对比黑",
        subtitle = "开启墨水屏凝黑笔触加深，字迹浓黑锐利",
        textSizeSp = 20,
        lineSpacingX10 = 15,
        paraSpacingDp = 11,
        letterSpacingX100 = 2,
        marginHdp = 22,
        marginVdp = 18,
        bold = true,
        defaultFontId = "eink_bold"
    );

    companion object {
        fun fromId(id: String): LayoutPreset? = values().firstOrNull { it.id == id }
    }
}
