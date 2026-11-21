package com.flue.launcher.surface.secret

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 五区域隐藏密码的纯规则（R12、R14、R15、R17）。不依赖 Android，全部可单元测试。
 */
object SecretRules {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 16
    const val DEFAULT_LENGTH = 6

    /** 中心圆半径：屏幕短边的 26%。 */
    const val CENTER_RADIUS_FRACTION = 0.26f

    /** 连续这么久没有输入就取消验证。 */
    const val IDLE_TIMEOUT_MS = 10_000L

    /** 电量按钮、返回区域需要按住这么久。 */
    const val HOLD_MS = 2_000L

    /** 拖动终点要越过安全区宽／高的这个比例。 */
    const val DRAG_THRESHOLD_FRACTION = 0.75f

    /** 拖动时另一方向的偏差容差：安全区短边的 15%，且不少于系统触摸容差。 */
    const val DRAG_TOLERANCE_FRACTION = 0.15f

    /** 忘记密码时点系统版本号的次数。 */
    const val RESET_TAPS = 100

    fun isValidLength(length: Int): Boolean = length in MIN_LENGTH..MAX_LENGTH

    fun isValid(digits: List<Int>): Boolean = isValidLength(digits.size) && digits.all { it in 1..5 }

    /**
     * 全屏坐标 → 区域：中心圆 5 优先，其余按象限，左上 1、右上 2、左下 3、右下 4。
     * 坐标原点在屏幕左上角，宽高是整个屏幕（不扣安全区、不受 DPI 影响）。
     */
    fun zoneOf(x: Float, y: Float, width: Float, height: Float): Int {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * CENTER_RADIUS_FRACTION
        if (hypot(x - cx, y - cy) <= radius) return 5
        val left = x < cx
        val top = y < cy
        return when {
            top && left -> 1
            top -> 2
            left -> 3
            else -> 4
        }
    }

    /** 密码存储格式：每位一个字符 '1'..'5'。解出不合法的内容视为读不出来。 */
    fun encode(digits: List<Int>): String {
        require(isValid(digits)) { "invalid secret" }
        return digits.joinToString("")
    }

    fun decode(text: String?): List<Int>? {
        if (text == null) return null
        val digits = text.map { it - '0' }
        return digits.takeIf(::isValid)
    }
}

/** 一次验证里逐位比较：任一位错立即失败，全部正确立即成功，不等确认。 */
class SecretAttempt(private val secret: List<Int>) {
    sealed interface Step {
        data class Continue(val entered: Int) : Step
        data object Success : Step
        data object Failure : Step
    }

    private var index = 0
    private var finished = false

    val entered: Int get() = index

    fun input(digit: Int): Step {
        if (finished) return Step.Failure
        if (secret.isEmpty() || index >= secret.size || secret[index] != digit) {
            finished = true
            return Step.Failure
        }
        index++
        if (index == secret.size) {
            finished = true
            return Step.Success
        }
        return Step.Continue(index)
    }
}

/** 按住再松手的手势分类（R14 电量按钮、R15/R17 返回区域起手）。 */
enum class HoldOutcome {
    /** 没按够就松手，也没移动：普通点击。 */
    TAP,

    /** 按住够久、没移动就松手。 */
    HELD,

    /** 中途移动超过触摸容差：不是这个手势。 */
    MOVED,
}

object HoldGesture {
    /**
     * @param movedBeforeHold 在按满 [SecretRules.HOLD_MS] 之前是否移动超过触摸容差。
     */
    fun classify(pressedMs: Long, movedBeforeHold: Boolean, holdMs: Long = SecretRules.HOLD_MS): HoldOutcome = when {
        movedBeforeHold -> HoldOutcome.MOVED
        pressedMs >= holdMs -> HoldOutcome.HELD
        else -> HoldOutcome.TAP
    }
}

/** 安全区在屏幕上的位置（像素，全屏坐标）。 */
data class SafeBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    val shortSide: Float get() = min(width, height)
}

enum class BackDragResult { NONE, MANAGEMENT, RESET }

object BackAreaDrag {
    /**
     * 关于页返回区域按住 2 秒后的拖动（R15 右拖进管理、R17 下拖进重置）。
     *
     * 终点按安全区判断：右拖终点越过安全区左边 + 3/4 宽，下拖终点越过安全区上边 + 3/4 高；
     * 另一方向的偏差相对按下位置计算，容差为安全区短边 15%，且不少于 [touchSlop]。
     */
    fun classify(
        downX: Float,
        downY: Float,
        upX: Float,
        upY: Float,
        safe: SafeBounds,
        touchSlop: Float,
    ): BackDragResult {
        val tolerance = max(safe.shortSide * SecretRules.DRAG_TOLERANCE_FRACTION, touchSlop)
        val right = upX >= safe.left + safe.width * SecretRules.DRAG_THRESHOLD_FRACTION && abs(upY - downY) <= tolerance
        val down = upY >= safe.top + safe.height * SecretRules.DRAG_THRESHOLD_FRACTION && abs(upX - downX) <= tolerance
        return when {
            right && !down -> BackDragResult.MANAGEMENT
            down && !right -> BackDragResult.RESET
            else -> BackDragResult.NONE
        }
    }
}

/** 重置流程里点系统版本号的计数（R17）：只在本次流程内累计，[clear] 后从零开始。 */
class ResetTapCounter(private val target: Int = SecretRules.RESET_TAPS) {
    var count: Int = 0
        private set

    /** 返回 true 表示刚好达到目标次数。之后继续点不会重复触发。 */
    fun tap(): Boolean {
        if (count >= target) return false
        count++
        return count == target
    }

    fun clear() {
        count = 0
    }
}

/** 设置密码时“输入 → 确认”的状态（R13）。 */
class SecretEntry(val length: Int) {
    init {
        require(SecretRules.isValidLength(length))
    }

    enum class Stage { FIRST, CONFIRM }

    sealed interface Result {
        data class Progress(val stage: Stage, val entered: Int) : Result
        data object StartConfirm : Result
        data class Matched(val digits: List<Int>) : Result
        data object Mismatch : Result
    }

    var stage: Stage = Stage.FIRST
        private set
    private var first: List<Int> = emptyList()
    private val current = mutableListOf<Int>()

    val entered: Int get() = current.size

    fun input(digit: Int): Result {
        require(digit in 1..5)
        current += digit
        if (current.size < length) return Result.Progress(stage, current.size)
        return when (stage) {
            Stage.FIRST -> {
                first = current.toList()
                current.clear()
                stage = Stage.CONFIRM
                Result.StartConfirm
            }
            Stage.CONFIRM -> {
                val second = current.toList()
                if (second == first) {
                    Result.Matched(second)
                } else {
                    clear()
                    Result.Mismatch
                }
            }
        }
    }

    /** 清空未保存的输入，回到第一遍。 */
    fun clear() {
        first = emptyList()
        current.clear()
        stage = Stage.FIRST
    }
}
