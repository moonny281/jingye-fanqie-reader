package app.jingye.reader.eink

class GhostPolicy(private var everyNPages: () -> Int) {
    private var counter = 0

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
