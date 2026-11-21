package com.flue.launcher.surface.entry

/** 实体按键的触发方式（R34）。 */
enum class KeyPattern(val taps: Int, val long: Boolean) {
    SINGLE(1, false),
    DOUBLE(2, false),
    TRIPLE(3, false),
    LONG(1, true),
    DOUBLE_THEN_LONG(2, true),
    TRIPLE_THEN_LONG(3, true),
    ;

    companion object {
        fun parse(name: String?): KeyPattern? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 按下、抬起的时间序列 → 识别出的按键方式。纯逻辑，时间由调用方传入；
 * 调用方在 [deadline] 时刻调用 [onTimer]（单击要等确认不是双击，长按要等按满时长）。
 *
 * 规则：相邻两次按下的间隔（上一次抬起到这次按下）不超过 [TAP_GAP_MS] 算连按；
 * 最后一次按住达到 [LONG_PRESS_MS] 算“…后长按”；最后一次抬起后 [TAP_GAP_MS] 内没有再按，按连按次数结算。
 * 超过三次的连按不对应任何方式。
 */
class KeyPatternDetector {
    private var taps = 0
    private var down = false
    private var downAt = 0L
    private var lastUpAt = Long.MIN_VALUE / 2
    private var longFired = false

    /** 下一次需要 [onTimer] 的时刻；没有待定的判断时为 null。 */
    var deadline: Long? = null
        private set

    fun onDown(now: Long): KeyPattern? {
        if (down) return null
        if (now - lastUpAt > TAP_GAP_MS) taps = 0
        taps++
        down = true
        downAt = now
        longFired = false
        deadline = now + LONG_PRESS_MS
        return null
    }

    fun onUp(now: Long): KeyPattern? {
        if (!down) return null
        down = false
        lastUpAt = now
        if (longFired) {
            reset()
            return null
        }
        if (now - downAt >= LONG_PRESS_MS) return fireLong()
        deadline = now + TAP_GAP_MS
        return null
    }

    fun onTimer(now: Long): KeyPattern? {
        val due = deadline ?: return null
        if (now < due) return null
        return if (down) {
            if (now - downAt >= LONG_PRESS_MS && !longFired) fireLong() else null
        } else {
            val result = when (taps) {
                1 -> KeyPattern.SINGLE
                2 -> KeyPattern.DOUBLE
                3 -> KeyPattern.TRIPLE
                else -> null
            }
            reset()
            result
        }
    }

    private fun fireLong(): KeyPattern? {
        longFired = true
        deadline = null
        val result = when (taps) {
            1 -> KeyPattern.LONG
            2 -> KeyPattern.DOUBLE_THEN_LONG
            3 -> KeyPattern.TRIPLE_THEN_LONG
            else -> null
        }
        if (!down) reset()
        return result
    }

    private fun reset() {
        taps = 0
        deadline = null
        longFired = false
    }

    companion object {
        const val TAP_GAP_MS = 350L
        const val LONG_PRESS_MS = 700L
    }
}
