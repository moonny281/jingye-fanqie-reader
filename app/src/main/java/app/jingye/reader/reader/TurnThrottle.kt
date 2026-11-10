package app.jingye.reader.reader

import android.os.SystemClock

class TurnThrottle(private val minIntervalMs: () -> Int) {
    private var last = 0L

    fun tryAcquire(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - last < minIntervalMs()) return false
        last = now
        return true
    }
}
