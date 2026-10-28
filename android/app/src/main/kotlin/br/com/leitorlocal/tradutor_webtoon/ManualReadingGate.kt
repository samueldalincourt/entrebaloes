package br.com.leitorlocal.tradutor_webtoon

/** A session starts paused. Page updates cannot cancel a capture or inference. */
class ManualReadingGate {
    var paused = true
        private set
    private var scheduled = false
    private var processing = false
    fun resume() { paused = false }
    fun pause() { paused = true; invalidate() }
    fun invalidate() { scheduled = false; processing = false }
    fun schedule(): Boolean {
        if (paused) return false
        scheduled = true; processing = false
        return true
    }
    fun acceptsContentChange() = !paused && !scheduled && !processing
    fun begin(): Boolean {
        if (paused || !scheduled) return false
        scheduled = false; processing = true
        return true
    }
    fun complete() { processing = false }
}
