package com.flue.launcher.surface.lock

/** R33 桌面进入密码：独立的六位数字（0–9），与五区域管理密码分开。 */
object DesktopPinRules {
    const val LENGTH = 6
    const val MAX_FAILS = 5
    const val COOLDOWN_MS = 30_000L

    fun isValid(digits: List<Int>): Boolean = digits.size == LENGTH && digits.all { it in 0..9 }

    fun encode(digits: List<Int>): String {
        require(isValid(digits)) { "invalid pin" }
        return digits.joinToString("")
    }

    fun decode(text: String?): List<Int>? = text?.map { it - '0' }?.takeIf(::isValid)
}

/**
 * 连续错误计数与冷却（R33）：连续五次错误暂停 30 秒；冷却期间的输入不计数。
 * 时间用开机以来的毫秒数；冷却剩余超过 30 秒说明机器重启过，冷却作废但已错次数保留。
 */
data class LockoutState(val fails: Int = 0, val untilElapsed: Long = 0) {

    fun lockedOut(nowElapsed: Long): Boolean = nowElapsed < untilElapsed

    fun remainingMs(nowElapsed: Long): Long = (untilElapsed - nowElapsed).coerceAtLeast(0)

    fun fail(nowElapsed: Long): LockoutState {
        if (lockedOut(nowElapsed)) return this
        val next = fails + 1
        return if (next >= DesktopPinRules.MAX_FAILS) {
            LockoutState(0, nowElapsed + DesktopPinRules.COOLDOWN_MS)
        } else {
            copy(fails = next)
        }
    }

    fun normalize(nowElapsed: Long): LockoutState =
        if (untilElapsed - nowElapsed > DesktopPinRules.COOLDOWN_MS) copy(untilElapsed = 0) else this

    val triesLeft: Int get() = DesktopPinRules.MAX_FAILS - fails
}

/** 输入一次密码：凑满六位才比较（不像隐藏密码那样逐位失败）。 */
class PinEntry {
    private val digits = mutableListOf<Int>()

    val entered: Int get() = digits.size

    /** 满六位时返回输入内容并清空，否则返回 null。 */
    fun press(digit: Int): List<Int>? {
        require(digit in 0..9)
        if (digits.size >= DesktopPinRules.LENGTH) digits.clear()
        digits += digit
        if (digits.size < DesktopPinRules.LENGTH) return null
        return digits.toList().also { digits.clear() }
    }

    fun delete() {
        if (digits.isNotEmpty()) digits.removeAt(digits.lastIndex)
    }

    fun clear() = digits.clear()
}

/** 设置新密码：输入两次一致才算完成。 */
class PinSetup {
    sealed interface Step {
        data object First : Step
        data object Confirm : Step
        data object Mismatch : Step
        data class Done(val digits: List<Int>) : Step
    }

    var step: Step = Step.First
        private set
    private var first: List<Int>? = null

    fun submit(digits: List<Int>): Step {
        step = when (val pending = first) {
            null -> {
                first = digits
                Step.Confirm
            }
            else -> if (pending == digits) Step.Done(digits) else {
                first = null
                Step.Mismatch
            }
        }
        return step
    }
}
