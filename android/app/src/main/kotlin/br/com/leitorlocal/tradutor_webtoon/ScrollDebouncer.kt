package br.com.leitorlocal.tradutor_webtoon

/** Pure timing policy, independent of Android so race conditions can be tested. */
class ScrollDebouncer(private val quietMs: Long = 550) {
    var generation = 0
        private set
    private var deadline = Long.MAX_VALUE
    fun invalidate() { generation++; deadline = Long.MAX_VALUE }
    fun motion(now: Long): Int { generation++; deadline = now + quietMs; return generation }
    fun stable(now: Long, expected: Int) = expected == generation && now >= deadline
}
