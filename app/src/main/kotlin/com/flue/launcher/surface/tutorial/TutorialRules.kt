package com.flue.launcher.surface.tutorial

/**
 * 教程与最终练习的纯规则（R20–R22）。不依赖 Android。
 */
object TutorialRules {
    /** 入口规则改变时加一；旧成绩不能通过新版本门槛（R22）。 */
    const val VERSION = 1

    /** 教学重置关点版本号的次数；实际使用是 [com.flue.launcher.surface.secret.SecretRules.RESET_TAPS]。 */
    const val TEACH_RESET_TAPS = 20

    /** 最终练习：每项任务的时限。 */
    const val TASK_LIMIT_MS = 3 * 60_000L

    /** 最终练习：本轮累计这么多次密码失败或主动取消验证就询问重学。 */
    const val MAX_MISSES = 3

    fun passes(storedVersion: Int): Boolean = storedVersion == VERSION
}

/** 三关教学（R20）。 */
enum class TeachLevel { HIDDEN_DRAWER, MANAGEMENT, RESET }

/**
 * 一关里必须依次真实完成的步骤。顺序之外的事件不推进，也不倒退；
 * 页面回退由宿主调用 [rewindTo]。
 */
enum class TeachStep {
    // 第一关
    OPEN_CONTROL_CENTER, HOLD_BATTERY, DRAWER_SECRET, DRAWER_OPENED, DRAWER_BACK_TO_FACE,

    // 第二、三关共用的前半段
    OPEN_APP_LIST, OPEN_SETTINGS, OPEN_ABOUT,

    // 第二关
    DRAG_RIGHT, MENU_SECRET, OPEN_FULL_SETTINGS,

    // 第三关
    DRAG_DOWN, TAP_BUILD_NUMBER, SET_PRACTICE_SECRET,
}

class TeachTrack(val level: TeachLevel) {
    val steps: List<TeachStep> = when (level) {
        TeachLevel.HIDDEN_DRAWER -> listOf(
            TeachStep.OPEN_CONTROL_CENTER, TeachStep.HOLD_BATTERY, TeachStep.DRAWER_SECRET,
            TeachStep.DRAWER_OPENED, TeachStep.DRAWER_BACK_TO_FACE,
        )
        TeachLevel.MANAGEMENT -> listOf(
            TeachStep.OPEN_APP_LIST, TeachStep.OPEN_SETTINGS, TeachStep.OPEN_ABOUT,
            TeachStep.DRAG_RIGHT, TeachStep.MENU_SECRET, TeachStep.OPEN_FULL_SETTINGS,
        )
        TeachLevel.RESET -> listOf(
            TeachStep.OPEN_APP_LIST, TeachStep.OPEN_SETTINGS, TeachStep.OPEN_ABOUT,
            TeachStep.DRAG_DOWN, TeachStep.TAP_BUILD_NUMBER, TeachStep.SET_PRACTICE_SECRET,
        )
    }

    var index: Int = 0
        private set

    val current: TeachStep? get() = steps.getOrNull(index)
    val completed: Boolean get() = index >= steps.size

    /** 完成了 [step]：正是当前步骤才推进。返回是否推进。 */
    fun report(step: TeachStep): Boolean {
        if (current != step) return false
        index++
        return true
    }

    /** 回到某一步之前（离开了页面、验证失败）。只往回，不往前跳。 */
    fun rewindTo(step: TeachStep) {
        val at = steps.indexOf(step)
        if (at in 0 until index) index = at
    }
}

/** 最终练习的两个任务（R21）。 */
enum class PracticeTask { HIDDEN_DRAWER, FULL_SETTINGS }

/**
 * 最终无提示练习（R21）。时间用调用方给的单调时钟（毫秒）。
 *
 * - 两个任务任意顺序完成，同一轮都完成才通过。
 * - 每完成一项重新计时；3 分钟内没完成下一项就询问重学。
 * - 本轮累计 3 次密码失败或主动取消验证，询问重学；主动取消练习立即询问。
 * - 熄屏、来电暂停计时，不算失败。
 */
class PracticeSession(startAtMs: Long) {
    sealed interface State {
        data object Running : State
        data object Paused : State
        data object Passed : State
        data class AskRelearn(val reason: Reason) : State
    }

    enum class Reason { TIMEOUT, MISSES, CANCELLED }

    private val done = mutableSetOf<PracticeTask>()
    private var elapsedBeforePause = 0L
    private var runningSince: Long? = startAtMs
    var misses: Int = 0
        private set
    var state: State = State.Running
        private set

    val completedTasks: Set<PracticeTask> get() = done

    private fun elapsed(now: Long): Long = elapsedBeforePause + (runningSince?.let { now - it } ?: 0L)

    fun remainingMs(now: Long): Long = (TutorialRules.TASK_LIMIT_MS - elapsed(now)).coerceAtLeast(0L)

    /** 定时调用：超时就转为询问重学。 */
    fun tick(now: Long): State {
        if (state == State.Running && elapsed(now) >= TutorialRules.TASK_LIMIT_MS) {
            state = State.AskRelearn(Reason.TIMEOUT)
        }
        return state
    }

    fun pause(now: Long) {
        if (state != State.Running) return
        tick(now)
        if (state != State.Running) return
        elapsedBeforePause = elapsed(now)
        runningSince = null
        state = State.Paused
    }

    fun resume(now: Long) {
        if (state != State.Paused) return
        runningSince = now
        state = State.Running
    }

    /** 密码输错或主动取消验证（系统中断不调用这个）。 */
    fun miss(now: Long): State {
        if (tick(now) != State.Running) return state
        misses++
        if (misses >= TutorialRules.MAX_MISSES) state = State.AskRelearn(Reason.MISSES)
        return state
    }

    fun complete(task: PracticeTask, now: Long): State {
        if (tick(now) != State.Running) return state
        if (done.add(task)) {
            elapsedBeforePause = 0L
            runningSince = now
        }
        if (done.size == PracticeTask.entries.size) state = State.Passed
        return state
    }

    fun cancel(): State {
        if (state == State.Running || state == State.Paused) state = State.AskRelearn(Reason.CANCELLED)
        return state
    }
}
