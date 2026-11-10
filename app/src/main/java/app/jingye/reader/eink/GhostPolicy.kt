package app.jingye.reader.eink

class GhostPolicy(private var everyNPages: () -> Int) {
    private var counter = 0

    /** 每次翻页调用；返回 true 表示这次需要做局部清影。0 表示从不。 */
    fun onPageTurned(): Boolean {
        val n = everyNPages()
        if (n <= 0) return false
        if (++counter >= n) {
            counter = 0
            return true
        }
        return false
    }
}
